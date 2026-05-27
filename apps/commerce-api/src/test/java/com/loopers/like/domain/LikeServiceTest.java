package com.loopers.like.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LikeServiceTest {

    private LikeService likeService;

    @BeforeEach
    void setUp() {
        likeService = new LikeService();
    }

    @DisplayName("getOrThrow를 호출할 때,")
    @Nested
    class GetOrThrow {

        @DisplayName("like가 존재하면, 해당 like를 반환한다.")
        @Test
        void returnsLike_whenLikeExists() {
            // arrange
            LikeModel like = new LikeModel(1L, 2L);

            // act
            LikeModel result = likeService.getOrThrow(Optional.of(like));

            // assert
            assertThat(result).isEqualTo(like);
        }

        @DisplayName("like가 존재하지 않으면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenLikeNotExists() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                likeService.getOrThrow(Optional.empty())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
