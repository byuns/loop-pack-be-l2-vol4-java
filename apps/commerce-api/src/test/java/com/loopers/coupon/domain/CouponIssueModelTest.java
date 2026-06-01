package com.loopers.coupon.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CouponIssueModelTest {

    @DisplayName("CouponIssueModel을 생성할 때,")
    @Nested
    class Create {

        @DisplayName("정상 요청이면, status가 AVAILABLE인 CouponIssueModel이 생성된다.")
        @Test
        void createsCouponIssueModel_withAvailableStatus_whenRequestIsValid() {
            // arrange & act
            CouponIssueModel issue = new CouponIssueModel(1L, 1L);

            // assert
            assertThat(issue.getStatus()).isEqualTo(CouponStatus.AVAILABLE);
        }

        @DisplayName("couponId가 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenCouponIdIsNull() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                new CouponIssueModel(null, 1L)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("userId가 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenUserIdIsNull() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                new CouponIssueModel(1L, null)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("use를 호출할 때,")
    @Nested
    class Use {

        @DisplayName("AVAILABLE 상태이면, status가 USED로 변경된다.")
        @Test
        void changesStatusToUsed_whenStatusIsAvailable() {
            // arrange
            CouponIssueModel issue = new CouponIssueModel(1L, 1L);

            // act
            issue.use();

            // assert
            assertThat(issue.getStatus()).isEqualTo(CouponStatus.USED);
        }

        @DisplayName("이미 USED 상태이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenAlreadyUsed() {
            // arrange
            CouponIssueModel issue = new CouponIssueModel(1L, 1L);
            issue.use();

            // act
            CoreException exception = assertThrows(CoreException.class, issue::use);

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
