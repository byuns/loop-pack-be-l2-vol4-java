package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponIssueRequestModel;
import com.loopers.coupon.domain.CouponIssueRequestStatus;
import com.loopers.coupon.infrastructure.CouponIssueJpaRepository;
import com.loopers.coupon.infrastructure.CouponIssueRequestJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class CouponIssueConcurrencyIntegrationTest {

    // 브로커 없이 DB만 검증 — Kafka 리스너가 뜨지 않게 한다
    @DynamicPropertySource
    static void disableKafkaListener(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeAll
    static void ensureCouponsSchema(@Autowired JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
            "CREATE TABLE IF NOT EXISTS coupons (" +
            "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "  name VARCHAR(255) NOT NULL," +
            "  type VARCHAR(50) NOT NULL," +
            "  value BIGINT NOT NULL," +
            "  min_order_amount BIGINT," +
            "  max_count INT," +
            "  remaining_count INT," +
            "  expired_at DATETIME(6)," +
            "  created_at DATETIME(6) NOT NULL," +
            "  updated_at DATETIME(6) NOT NULL," +
            "  deleted_at DATETIME(6)" +
            ")"
        );
        // 기존 테이블에 컬럼이 없는 경우 추가
        try {
            jdbcTemplate.execute("ALTER TABLE coupons ADD COLUMN max_count INT");
        } catch (Exception ignored) {}
        try {
            jdbcTemplate.execute("ALTER TABLE coupons ADD COLUMN remaining_count INT");
        } catch (Exception ignored) {}
    }

    @Autowired private CouponIssueService couponIssueService;
    @Autowired private CouponIssueRequestJpaRepository couponIssueRequestJpaRepository;
    @Autowired private CouponIssueJpaRepository couponIssueJpaRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        // DatabaseCleanUp이 알고 있는 테이블(coupon_issues, coupon_issue_requests 등) 먼저 정리
        databaseCleanUp.truncateAllTables();
        // coupons는 streamer의 JPA 스캔 대상이 아니라 별도 정리
        jdbcTemplate.update("SET FOREIGN_KEY_CHECKS = 0");
        jdbcTemplate.update("TRUNCATE TABLE coupons");
        jdbcTemplate.update("SET FOREIGN_KEY_CHECKS = 1");
    }

    private Long insertCoupon(int maxCount) {
        jdbcTemplate.update(
            "INSERT INTO coupons (name, type, value, max_count, remaining_count, expired_at, created_at, updated_at) "
                + "VALUES ('선착순 쿠폰', 'FIXED', 1000, ?, ?, DATE_ADD(NOW(), INTERVAL 30 DAY), NOW(), NOW())",
            maxCount, maxCount
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Long insertPendingRequest(Long couponId, Long userId) {
        jdbcTemplate.update(
            "INSERT INTO coupon_issue_requests (coupon_id, user_id, status, created_at, updated_at) "
                + "VALUES (?, ?, 'PENDING', NOW(), NOW())",
            couponId, userId
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @DisplayName("선착순 쿠폰 수량 제한 — 동시 요청이 maxCount를 초과해도 정확히 maxCount만 발급된다.")
    @Nested
    class CountLimit {

        @DisplayName("선착순 10장 쿠폰에 20명이 동시에 요청하면, 정확히 10건만 ISSUED되고 나머지 10건은 FAILED다.")
        @Test
        void exactlyMaxCount_isIssued_whenConcurrentRequestsExceedLimit() throws InterruptedException {
            // arrange
            int maxCount = 10;
            int threadCount = 20;

            Long couponId = insertCoupon(maxCount);

            List<long[]> requests = new ArrayList<>();
            for (int i = 1; i <= threadCount; i++) {
                Long requestId = insertPendingRequest(couponId, (long) i);
                requests.add(new long[]{requestId, i});
            }

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger();

            // act — 20개 스레드 동시 출발
            for (long[] req : requests) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        couponIssueService.handleCouponIssueRequest(req[0], couponId, req[1]);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            // assert
            List<CouponIssueRequestModel> allRequests = couponIssueRequestJpaRepository.findAll();
            long issuedCount = allRequests.stream().filter(r -> r.getStatus() == CouponIssueRequestStatus.ISSUED).count();
            long failedCount = allRequests.stream().filter(r -> r.getStatus() == CouponIssueRequestStatus.FAILED).count();
            int couponIssueCount = couponIssueJpaRepository.findAll().size();
            Integer remainingCount = jdbcTemplate.queryForObject(
                "SELECT remaining_count FROM coupons WHERE id = ?", Integer.class, couponId);

            assertAll(
                () -> assertThat(errors.get()).as("시스템 에러 없음").isZero(),
                () -> assertThat(couponIssueCount).as("수량 초과 발급 없음").isEqualTo(maxCount),
                () -> assertThat(remainingCount).as("잔여 수량 정확히 0").isZero(),
                () -> assertThat(issuedCount).as("발급 성공 건수").isEqualTo(maxCount),
                () -> assertThat(failedCount).as("발급 실패 건수").isEqualTo(threadCount - maxCount)
            );
        }
    }

    @DisplayName("멱등성 — 동일한 발급 요청이 두 번 처리돼도 한 번만 발급된다.")
    @Nested
    class Idempotency {

        @DisplayName("이미 ISSUED 처리된 요청을 다시 처리하면, 추가 발급 없이 skip된다.")
        @Test
        void issuedOnlyOnce_whenSameRequestProcessedTwice() {
            // arrange
            Long couponId = insertCoupon(10);
            Long requestId = insertPendingRequest(couponId, 1L);

            // act
            couponIssueService.handleCouponIssueRequest(requestId, couponId, 1L); // 첫 번째: ISSUED
            couponIssueService.handleCouponIssueRequest(requestId, couponId, 1L); // 두 번째: skip

            // assert
            int couponIssueCount = couponIssueJpaRepository.findAll().size();
            Integer remainingCount = jdbcTemplate.queryForObject(
                "SELECT remaining_count FROM coupons WHERE id = ?", Integer.class, couponId);
            CouponIssueRequestModel request = couponIssueRequestJpaRepository.findById(requestId).orElseThrow();

            assertAll(
                () -> assertThat(couponIssueCount).as("중복 발급 없음").isEqualTo(1),
                () -> assertThat(remainingCount).as("1건만 감소").isEqualTo(9),
                () -> assertThat(request.getStatus()).as("최종 상태 ISSUED").isEqualTo(CouponIssueRequestStatus.ISSUED)
            );
        }
    }
}
