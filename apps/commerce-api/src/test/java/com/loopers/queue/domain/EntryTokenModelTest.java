package com.loopers.queue.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntryTokenModelTest {

    @DisplayName("입장 토큰을 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("정상 userId·토큰·visibleAt이면, 생성에 성공한다.")
        @Test
        void createsEntryToken_whenValidFieldsProvided() {
            // arrange
            Instant visibleAt = Instant.now();

            // act
            EntryTokenModel model = new EntryTokenModel(1L, "token-value", visibleAt);

            // assert
            assertAll(
                () -> assertThat(model.getUserId()).isEqualTo(1L),
                () -> assertThat(model.getToken()).isEqualTo("token-value"),
                () -> assertThat(model.getVisibleAt()).isEqualTo(visibleAt)
            );
        }

        @DisplayName("userId가 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenUserIdIsNull() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                new EntryTokenModel(null, "token-value", Instant.now())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("userId가 0 이하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenUserIdIsNotPositive() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                new EntryTokenModel(0L, "token-value", Instant.now())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("토큰값이 null이거나 빈 문자열이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenTokenIsNullOrBlank() {
            // act
            CoreException nullToken = assertThrows(CoreException.class, () ->
                new EntryTokenModel(1L, null, Instant.now())
            );
            CoreException blankToken = assertThrows(CoreException.class, () ->
                new EntryTokenModel(1L, " ", Instant.now())
            );

            // assert
            assertAll(
                () -> assertThat(nullToken.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(blankToken.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST)
            );
        }

        @DisplayName("visibleAt이 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenVisibleAtIsNull() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                new EntryTokenModel(1L, "token-value", null)
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("입장 토큰을 발급할 때, ")
    @Nested
    class Issue {

        @DisplayName("issue를 호출하면, 비어있지 않은 토큰값이 생성된다.")
        @Test
        void generatesNonBlankToken_whenIssued() {
            // act
            EntryTokenModel model = EntryTokenModel.issue(1L, 0L);

            // assert
            assertAll(
                () -> assertThat(model.getUserId()).isEqualTo(1L),
                () -> assertThat(model.getToken()).isNotBlank()
            );
        }

        @DisplayName("여러 번 발급하면, 서로 다른 토큰값이 생성된다.")
        @Test
        void generatesUniqueTokens_whenIssuedMultipleTimes() {
            // act
            EntryTokenModel first = EntryTokenModel.issue(1L, 0L);
            EntryTokenModel second = EntryTokenModel.issue(1L, 0L);

            // assert
            assertThat(first.getToken()).isNotEqualTo(second.getToken());
        }

        @DisplayName("jitterMs가 0이면, visibleAt이 발급 시각과 사실상 같다 (즉시 노출).")
        @Test
        void setsVisibleAtToNow_whenJitterIsZero() {
            // arrange
            Instant before = Instant.now();

            // act
            EntryTokenModel model = EntryTokenModel.issue(1L, 0L);

            // assert
            Instant after = Instant.now();
            assertAll(
                () -> assertThat(model.getVisibleAt()).isBetween(before, after),
                () -> assertThat(model.isVisible(after)).isTrue()
            );
        }

        @DisplayName("jitterMs가 양수이면, visibleAt이 발급 시각 + [0, jitterMs] 범위 내다.")
        @Test
        void setsVisibleAtWithinJitterRange_whenJitterIsPositive() {
            // arrange
            long jitterMs = 500L;
            Instant before = Instant.now();

            // act
            EntryTokenModel model = EntryTokenModel.issue(1L, jitterMs);

            // assert — 시계 오차 여유로 상한에 10ms 여유
            Instant upperBound = Instant.now().plusMillis(jitterMs + 10);
            assertAll(
                () -> assertThat(model.getVisibleAt()).isAfterOrEqualTo(before),
                () -> assertThat(model.getVisibleAt()).isBeforeOrEqualTo(upperBound)
            );
        }
    }

    @DisplayName("isVisible을 호출할 때, ")
    @Nested
    class IsVisible {

        @DisplayName("visibleAt이 now 이전이거나 같으면, true를 반환한다.")
        @Test
        void returnsTrue_whenVisibleAtIsBeforeOrEqualNow() {
            // arrange
            Instant visibleAt = Instant.now().minusMillis(100);
            EntryTokenModel model = new EntryTokenModel(1L, "token", visibleAt);

            // act & assert
            assertThat(model.isVisible(Instant.now())).isTrue();
        }

        @DisplayName("visibleAt이 now 이후면, false를 반환한다.")
        @Test
        void returnsFalse_whenVisibleAtIsAfterNow() {
            // arrange
            Instant visibleAt = Instant.now().plusSeconds(60);
            EntryTokenModel model = new EntryTokenModel(1L, "token", visibleAt);

            // act & assert
            assertThat(model.isVisible(Instant.now())).isFalse();
        }
    }
}
