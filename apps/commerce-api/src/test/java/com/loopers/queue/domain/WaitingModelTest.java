package com.loopers.queue.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaitingModelTest {

    @DisplayName("대기 모델을 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("정상적인 userId와 position이 주어지면, WaitingModel이 생성된다.")
        @Test
        void createsWaitingModel_whenUserIdAndPositionAreValid() {
            // arrange
            Long userId = 1L;
            Long position = 10L;

            // act
            WaitingModel model = new WaitingModel(userId, position);

            // assert
            assertAll(
                () -> assertThat(model.getUserId()).isEqualTo(userId),
                () -> assertThat(model.getPosition()).isEqualTo(position)
            );
        }

        @DisplayName("userId가 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenUserIdIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new WaitingModel(null, 1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("userId가 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenUserIdIsNonPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new WaitingModel(0L, 1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("position이 0 이하이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenPositionIsNonPositive() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new WaitingModel(1L, 0L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
