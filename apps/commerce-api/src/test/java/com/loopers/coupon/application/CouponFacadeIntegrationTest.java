package com.loopers.coupon.application;

import com.loopers.coupon.domain.CouponStatus;
import com.loopers.coupon.domain.CouponType;
import com.loopers.coupon.infrastructure.CouponIssueJpaRepository;
import com.loopers.coupon.infrastructure.CouponJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class CouponFacadeIntegrationTest {

    @Autowired
    private CouponFacade couponFacade;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private CouponIssueJpaRepository couponIssueJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private CouponInfo savedCoupon(ZonedDateTime expiredAt) {
        return couponFacade.createCoupon("10% 할인", CouponType.RATE, 10L, null, expiredAt);
    }

    @DisplayName("쿠폰을 생성할 때,")
    @Nested
    class CreateCoupon {

        @DisplayName("정상 FIXED 요청이면, DB에 저장되고 CouponInfo를 반환한다.")
        @Test
        void returnsCouponInfo_whenFixedCouponRequestIsValid() {
            // arrange
            ZonedDateTime expiredAt = ZonedDateTime.now().plusDays(30);

            // act
            CouponInfo result = couponFacade.createCoupon("1000원 할인", CouponType.FIXED, 1000L, 5000L, expiredAt);

            // assert
            assertAll(
                () -> assertThat(result.id()).isNotNull(),
                () -> assertThat(result.name()).isEqualTo("1000원 할인"),
                () -> assertThat(result.type()).isEqualTo(CouponType.FIXED),
                () -> assertThat(result.value()).isEqualTo(1000L),
                () -> assertThat(result.minOrderAmount()).isEqualTo(5000L)
            );
        }

        @DisplayName("정상 RATE 요청이면, DB에 저장되고 CouponInfo를 반환한다.")
        @Test
        void returnsCouponInfo_whenRateCouponRequestIsValid() {
            // arrange
            ZonedDateTime expiredAt = ZonedDateTime.now().plusDays(30);

            // act
            CouponInfo result = couponFacade.createCoupon("10% 할인", CouponType.RATE, 10L, null, expiredAt);

            // assert
            assertAll(
                () -> assertThat(result.id()).isNotNull(),
                () -> assertThat(result.type()).isEqualTo(CouponType.RATE),
                () -> assertThat(result.value()).isEqualTo(10L),
                () -> assertThat(result.minOrderAmount()).isNull()
            );
        }

        @DisplayName("name이 blank이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenNameIsBlank() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.createCoupon("", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30))
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("쿠폰 목록을 조회할 때,")
    @Nested
    class GetCoupons {

        @DisplayName("쿠폰이 존재하면, 페이지별 CouponInfo 목록을 반환한다.")
        @Test
        void returnsCouponInfoList_whenCouponsExist() {
            // arrange
            ZonedDateTime expiredAt = ZonedDateTime.now().plusDays(30);
            couponFacade.createCoupon("쿠폰1", CouponType.FIXED, 1000L, null, expiredAt);
            couponFacade.createCoupon("쿠폰2", CouponType.RATE, 10L, null, expiredAt);

            // act
            List<CouponInfo> result = couponFacade.getCoupons(0, 20);

            // assert
            assertThat(result).hasSize(2);
        }

        @DisplayName("삭제된 쿠폰은 목록에 포함되지 않는다.")
        @Test
        void excludesDeletedCoupons_fromList() {
            // arrange
            ZonedDateTime expiredAt = ZonedDateTime.now().plusDays(30);
            CouponInfo coupon = couponFacade.createCoupon("삭제될 쿠폰", CouponType.FIXED, 1000L, null, expiredAt);
            couponFacade.deleteCoupon(coupon.id());

            // act
            List<CouponInfo> result = couponFacade.getCoupons(0, 20);

            // assert
            assertThat(result).isEmpty();
        }
    }

    @DisplayName("쿠폰을 단건 조회할 때,")
    @Nested
    class GetCoupon {

        @DisplayName("존재하는 couponId이면, CouponInfo를 반환한다.")
        @Test
        void returnsCouponInfo_whenCouponExists() {
            // arrange
            CouponInfo created = savedCoupon(ZonedDateTime.now().plusDays(30));

            // act
            CouponInfo result = couponFacade.getCoupon(created.id());

            // assert
            assertThat(result.id()).isEqualTo(created.id());
        }

        @DisplayName("존재하지 않는 couponId이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenCouponNotExists() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.getCoupon(999L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 couponId이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenCouponIsDeleted() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));
            couponFacade.deleteCoupon(coupon.id());

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.getCoupon(coupon.id())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("쿠폰을 수정할 때,")
    @Nested
    class UpdateCoupon {

        @DisplayName("정상 요청이면, 수정된 CouponInfo를 반환한다.")
        @Test
        void returnsUpdatedCouponInfo_whenRequestIsValid() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));
            ZonedDateTime newExpiredAt = ZonedDateTime.now().plusDays(60);

            // act
            CouponInfo result = couponFacade.updateCoupon(coupon.id(), "수정된 쿠폰", 20L, 10000L, newExpiredAt);

            // assert
            assertAll(
                () -> assertThat(result.id()).isEqualTo(coupon.id()),
                () -> assertThat(result.name()).isEqualTo("수정된 쿠폰"),
                () -> assertThat(result.value()).isEqualTo(20L),
                () -> assertThat(result.minOrderAmount()).isEqualTo(10000L)
            );
        }

        @DisplayName("존재하지 않는 couponId이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenCouponNotExists() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.updateCoupon(999L, "수정", 10L, null, ZonedDateTime.now().plusDays(30))
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("쿠폰을 삭제할 때,")
    @Nested
    class DeleteCoupon {

        @DisplayName("정상 요청이면, soft delete 처리된다.")
        @Test
        void softDeletesCoupon_whenRequestIsValid() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));

            // act
            couponFacade.deleteCoupon(coupon.id());

            // assert
            assertThat(couponJpaRepository.findById(coupon.id()))
                .isPresent()
                .get()
                .satisfies(c -> assertThat(c.getDeletedAt()).isNotNull());
        }

        @DisplayName("존재하지 않는 couponId이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenCouponNotExists() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.deleteCoupon(999L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("발급 내역을 조회할 때,")
    @Nested
    class GetCouponIssues {

        @DisplayName("발급 내역이 있으면, 페이지별 CouponIssueInfo 목록을 반환한다.")
        @Test
        void returnsCouponIssueInfoList_whenIssuesExist() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));
            couponFacade.issueCoupon(coupon.id(), 1L);
            couponFacade.issueCoupon(coupon.id(), 2L);

            // act
            List<CouponIssueInfo> result = couponFacade.getCouponIssues(coupon.id(), 0, 20);

            // assert
            assertThat(result).hasSize(2);
        }

        @DisplayName("존재하지 않는 couponId이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenCouponNotExists() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.getCouponIssues(999L, 0, 20)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("쿠폰을 발급할 때,")
    @Nested
    class IssueCoupon {

        @DisplayName("정상 요청이면, AVAILABLE 상태의 CouponIssueInfo를 반환한다.")
        @Test
        void returnsCouponIssueInfo_withAvailableStatus_whenRequestIsValid() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));

            // act
            CouponIssueInfo result = couponFacade.issueCoupon(coupon.id(), 1L);

            // assert
            assertAll(
                () -> assertThat(result.id()).isNotNull(),
                () -> assertThat(result.couponId()).isEqualTo(coupon.id()),
                () -> assertThat(result.userId()).isEqualTo(1L),
                () -> assertThat(result.status()).isEqualTo(CouponStatus.AVAILABLE)
            );
        }

        @DisplayName("존재하지 않는 couponId이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenCouponNotExists() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.issueCoupon(999L, 1L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 쿠폰에 발급 시도하면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenCouponIsDeleted() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));
            couponFacade.deleteCoupon(coupon.id());

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.issueCoupon(coupon.id(), 1L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("만료된 쿠폰에 발급 시도하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenCouponIsExpired() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().minusDays(1));

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.issueCoupon(coupon.id(), 1L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("동일 쿠폰을 중복 발급 시도하면, CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflict_whenCouponAlreadyIssued() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));
            couponFacade.issueCoupon(coupon.id(), 1L);

            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                couponFacade.issueCoupon(coupon.id(), 1L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }
    }

    @DisplayName("내 쿠폰 목록을 조회할 때,")
    @Nested
    class GetMyCoupons {

        @DisplayName("발급된 쿠폰이 있으면, 최신 발급순으로 CouponIssueInfo 목록을 반환한다.")
        @Test
        void returnsCouponIssueInfoList_sortedByLatest_whenCouponsExist() {
            // arrange
            ZonedDateTime expiredAt = ZonedDateTime.now().plusDays(30);
            CouponInfo coupon1 = couponFacade.createCoupon("쿠폰1", CouponType.FIXED, 1000L, null, expiredAt);
            CouponInfo coupon2 = couponFacade.createCoupon("쿠폰2", CouponType.RATE, 10L, null, expiredAt);
            couponFacade.issueCoupon(coupon1.id(), 1L);
            couponFacade.issueCoupon(coupon2.id(), 1L);

            // act
            List<CouponIssueInfo> result = couponFacade.getMyCoupons(1L);

            // assert
            assertThat(result).hasSize(2);
        }

        @DisplayName("발급받은 쿠폰이 만료되면, EXPIRED 상태로 반환한다.")
        @Test
        void returnsExpiredStatus_whenCouponTemplateIsExpired() {
            // arrange
            CouponInfo expiredCoupon = savedCoupon(ZonedDateTime.now().minusDays(1));
            couponIssueJpaRepository.save(
                new com.loopers.coupon.domain.CouponIssueModel(expiredCoupon.id(), 1L)
            );

            // act
            List<CouponIssueInfo> result = couponFacade.getMyCoupons(1L);

            // assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).status()).isEqualTo(CouponStatus.EXPIRED);
        }

        @DisplayName("사용한 쿠폰은, USED 상태로 반환한다.")
        @Test
        void returnsUsedStatus_whenCouponIsUsed() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));
            CouponIssueInfo issued = couponFacade.issueCoupon(coupon.id(), 1L);
            com.loopers.coupon.domain.CouponIssueModel issueModel =
                couponIssueJpaRepository.findById(issued.id()).orElseThrow();
            couponIssueJpaRepository.updateStatusIfAvailable(issueModel.getId(), CouponStatus.USED, CouponStatus.AVAILABLE);

            // act
            List<CouponIssueInfo> result = couponFacade.getMyCoupons(1L);

            // assert
            assertThat(result.get(0).status()).isEqualTo(CouponStatus.USED);
        }

        @DisplayName("삭제된 쿠폰 템플릿을 참조하는 발급 쿠폰도 정상 조회된다.")
        @Test
        void returnsIssueInfo_evenIfCouponTemplateIsDeleted() {
            // arrange
            CouponInfo coupon = savedCoupon(ZonedDateTime.now().plusDays(30));
            couponFacade.issueCoupon(coupon.id(), 1L);
            couponFacade.deleteCoupon(coupon.id());

            // act
            List<CouponIssueInfo> result = couponFacade.getMyCoupons(1L);

            // assert
            assertThat(result).hasSize(1);
        }
    }
}
