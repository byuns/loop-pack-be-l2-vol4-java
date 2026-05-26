package com.loopers.brand.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandModelTest {

    @DisplayName("Brand 객체를 생성할 때,")
    @Nested
    class Create {

        @DisplayName("브랜드명이 유효하면, 정상 생성된다.")
        @Test
        void createsBrandModel_whenNameIsValid() {
            // arrange
            String name = "나이키";

            // act & assert
            assertDoesNotThrow(() -> new BrandModel(name, "스포츠 브랜드"));
        }

        @DisplayName("설명이 null이어도, 정상 생성된다.")
        @Test
        void createsBrandModel_whenDescriptionIsNull() {
            // act & assert
            assertDoesNotThrow(() -> new BrandModel("나이키", null));
        }

        @DisplayName("설명이 빈 문자열이어도, 정상 생성된다.")
        @Test
        void createsBrandModel_whenDescriptionIsEmpty() {
            // act & assert
            assertDoesNotThrow(() -> new BrandModel("나이키", ""));
        }

        @DisplayName("브랜드명이 null이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenNameIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel(null, "설명")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("브랜드명이 빈 문자열이면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenNameIsEmpty() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("", "설명")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("브랜드명이 공백만으로 이루어지면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequest_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () ->
                new BrandModel("   ", "설명")
            );

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }
}
