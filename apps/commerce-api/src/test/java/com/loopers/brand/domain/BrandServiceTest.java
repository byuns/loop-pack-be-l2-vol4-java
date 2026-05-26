package com.loopers.brand.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrandServiceTest {

    private BrandService brandService;

    @BeforeEach
    void setUp() {
        brandService = new BrandService();
    }

    @DisplayName("getOrThrow를 호출할 때,")
    @Nested
    class GetOrThrow {

        @DisplayName("brand가 존재하면, 해당 brand를 반환한다.")
        @Test
        void returnsBrand_whenBrandExists() {
            // arrange
            BrandModel brand = new BrandModel("나이키", "스포츠 브랜드");

            // act
            BrandModel result = brandService.getOrThrow(Optional.of(brand));

            // assert
            assertThat(result).isEqualTo(brand);
        }

        @DisplayName("brand가 존재하지 않으면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFound_whenBrandNotExists() {
            // act
            CoreException exception = assertThrows(CoreException.class, () ->
                brandService.getOrThrow(Optional.empty())
            );

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
