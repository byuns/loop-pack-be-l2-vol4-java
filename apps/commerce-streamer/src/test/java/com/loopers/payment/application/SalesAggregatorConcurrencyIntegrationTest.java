package com.loopers.payment.application;

import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.infrastructure.ProductMetricsJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SalesAggregatorConcurrencyIntegrationTest {

    // 브로커 없이 DB만 검증 — Kafka 리스너가 뜨지 않게 한다
    @DynamicPropertySource
    static void disableKafkaListener(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private SalesAggregatorService salesAggregatorService;

    @Autowired
    private ProductMetricsJpaRepository productMetricsJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("같은 productId를 가진 서로 다른 주문(다른 eventId)을 동시에 처리해도, 원자 UPSERT로 sales_count가 합계와 일치한다 (lost update 없음).")
    @Test
    void noLostUpdate_whenConcurrentOrdersShareProduct() throws InterruptedException {
        // arrange
        int threads = 10;
        long productId = 1L;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        // act — 동시에 각자 다른 주문(order:0 ~ order:9)을 같은 상품에 대해 처리
        for (int i = 0; i < threads; i++) {
            final long orderId = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    salesAggregatorService.handleOrderConfirmed(
                        "order:" + orderId, List.of(new SalesItem(productId, 1L)));
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await();
        start.countDown(); // 동시 출발
        done.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // assert
        assertThat(failures.get()).isZero();
        ProductMetricsModel metrics = productMetricsJpaRepository.findByProductId(productId).orElseThrow();
        assertThat(metrics.getSalesCount()).isEqualTo(threads);
    }

    @DisplayName("product_metrics가 없는 productId면, UPSERT로 새 row를 만들고 sales_count를 반영한다.")
    @Test
    void createsRow_whenProductMetricsNotExists() {
        // act
        salesAggregatorService.handleOrderConfirmed("order:1000", List.of(new SalesItem(42L, 3L)));

        // assert
        ProductMetricsModel metrics = productMetricsJpaRepository.findByProductId(42L).orElseThrow();
        assertThat(metrics.getSalesCount()).isEqualTo(3L);
        assertThat(metrics.getLikeCount()).isZero();
    }
}
