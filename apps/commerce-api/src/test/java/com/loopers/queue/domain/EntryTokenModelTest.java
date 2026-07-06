package com.loopers.queue.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntryTokenModelTest {

    @DisplayName("입장 토큰을 생성할 때, ")
    @Nested
    class Create {

        @DisplayName("정상 userId와 토큰값이면, 생성에 성공한다.")
        @Test
        void createsEntryToken_whenValidUserIdAndTokenProvided() {
            // act
            EntryTokenModel model = new EntryTokenModel(1L, "token-value");

            // assert
            assertAll(
                () -> assertThat(model.getUserId()).isEqualTo(1L),
                () -> assertThat(model.getToken()).isEqualTo("token-value")
            );
        }

        @DisplayName("userId가 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenUserIdIsNull() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                new EntryTokenModel(null, "token-value")
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("userId가 0 이하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenUserIdIsNotPositive() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                new EntryTokenModel(0L, "token-value")
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("토큰값이 null이거나 빈 문자열이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenTokenIsNullOrBlank() {
            // act
            CoreException nullToken = assertThrows(CoreException.class, () ->
                new EntryTokenModel(1L, null)
            );
            CoreException blankToken = assertThrows(CoreException.class, () ->
                new EntryTokenModel(1L, " ")
            );

            // assert
            assertAll(
                () -> assertThat(nullToken.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST),
                () -> assertThat(blankToken.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST)
            );
        }
    }

    @DisplayName("입장 토큰을 발급할 때, ")
    @Nested
    class Issue {

        @DisplayName("issue를 호출하면, 비어있지 않은 토큰값이 생성된다.")
        @Test
        void generatesNonBlankToken_whenIssued() {
            // act
            EntryTokenModel model = EntryTokenModel.issue(1L);

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
            EntryTokenModel first = EntryTokenModel.issue(1L);
            EntryTokenModel second = EntryTokenModel.issue(1L);

            // assert
            assertThat(first.getToken()).isNotEqualTo(second.getToken());
        }
    }
}
