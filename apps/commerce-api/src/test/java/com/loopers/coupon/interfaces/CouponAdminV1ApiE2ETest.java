package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponFacade;
import com.loopers.coupon.domain.CouponType;
import com.loopers.coupon.infrastructure.CouponJpaRepository;
import com.loopers.support.response.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponAdminV1ApiE2ETest {

    private static final String ENDPOINT = "/api-admin/v1/coupons";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private CouponFacade couponFacade;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private CouponAdminV1Dto.CreateRequest validCreateRequest() {
        return new CouponAdminV1Dto.CreateRequest(
            "10% 할인 쿠폰", CouponType.RATE, 10L, null, ZonedDateTime.now().plusDays(30)
        );
    }

    @DisplayName("POST /api-admin/v1/coupons")
    @Nested
    class CreateCoupon {

        @DisplayName("정상 요청이면, 200 OK와 CouponResponse를 반환한다.")
        @Test
        void returnsCouponResponse_whenRequestIsValid() {
            // arrange
            CouponAdminV1Dto.CreateRequest request = validCreateRequest();

            // act
            ParameterizedTypeReference<ApiResponse<CouponAdminV1Dto.CouponResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponAdminV1Dto.CouponResponse>> response =
                testRestTemplate.exchange(ENDPOINT, HttpMethod.POST, new HttpEntity<>(request), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().id()).isNotNull(),
                () -> assertThat(response.getBody().data().name()).isEqualTo("10% 할인 쿠폰"),
                () -> assertThat(response.getBody().data().type()).isEqualTo(CouponType.RATE),
                () -> assertThat(response.getBody().data().value()).isEqualTo(10L)
            );
        }

        @DisplayName("name이 blank이면, 400 Bad Request를 반환한다.")
        @Test
        void returnsBadRequest_whenNameIsBlank() {
            // arrange
            CouponAdminV1Dto.CreateRequest request = new CouponAdminV1Dto.CreateRequest(
                "", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30)
            );

            // act
            ParameterizedTypeReference<ApiResponse<CouponAdminV1Dto.CouponResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponAdminV1Dto.CouponResponse>> response =
                testRestTemplate.exchange(ENDPOINT, HttpMethod.POST, new HttpEntity<>(request), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @DisplayName("GET /api-admin/v1/coupons")
    @Nested
    class GetCoupons {

        @DisplayName("쿠폰이 존재하면, 200 OK와 목록을 반환한다.")
        @Test
        void returnsCouponList_whenCouponsExist() {
            // arrange
            couponFacade.createCoupon("쿠폰1", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30));

            // act
            ParameterizedTypeReference<ApiResponse<List<CouponAdminV1Dto.CouponResponse>>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<CouponAdminV1Dto.CouponResponse>>> response =
                testRestTemplate.exchange(ENDPOINT + "?page=0&size=20", HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data()).hasSize(1)
            );
        }
    }

    @DisplayName("GET /api-admin/v1/coupons/{couponId}")
    @Nested
    class GetCoupon {

        @DisplayName("존재하는 couponId이면, 200 OK와 CouponResponse를 반환한다.")
        @Test
        void returnsCouponResponse_whenCouponExists() {
            // arrange
            var coupon = couponFacade.createCoupon("쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30));

            // act
            ParameterizedTypeReference<ApiResponse<CouponAdminV1Dto.CouponResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponAdminV1Dto.CouponResponse>> response =
                testRestTemplate.exchange(ENDPOINT + "/" + coupon.id(), HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().id()).isEqualTo(coupon.id())
            );
        }

        @DisplayName("존재하지 않는 couponId이면, 404 Not Found를 반환한다.")
        @Test
        void returnsNotFound_whenCouponNotExists() {
            // act
            ParameterizedTypeReference<ApiResponse<CouponAdminV1Dto.CouponResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponAdminV1Dto.CouponResponse>> response =
                testRestTemplate.exchange(ENDPOINT + "/999", HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("PUT /api-admin/v1/coupons/{couponId}")
    @Nested
    class UpdateCoupon {

        @DisplayName("정상 요청이면, 200 OK와 수정된 CouponResponse를 반환한다.")
        @Test
        void returnsUpdatedCouponResponse_whenRequestIsValid() {
            // arrange
            var coupon = couponFacade.createCoupon("쿠폰", CouponType.RATE, 10L, null, ZonedDateTime.now().plusDays(30));
            CouponAdminV1Dto.UpdateRequest request = new CouponAdminV1Dto.UpdateRequest(
                "수정된 쿠폰", 20L, 5000L, ZonedDateTime.now().plusDays(60)
            );

            // act
            ParameterizedTypeReference<ApiResponse<CouponAdminV1Dto.CouponResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponAdminV1Dto.CouponResponse>> response =
                testRestTemplate.exchange(ENDPOINT + "/" + coupon.id(), HttpMethod.PUT, new HttpEntity<>(request), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().name()).isEqualTo("수정된 쿠폰"),
                () -> assertThat(response.getBody().data().value()).isEqualTo(20L)
            );
        }

        @DisplayName("존재하지 않는 couponId이면, 404 Not Found를 반환한다.")
        @Test
        void returnsNotFound_whenCouponNotExists() {
            // arrange
            CouponAdminV1Dto.UpdateRequest request = new CouponAdminV1Dto.UpdateRequest(
                "수정", 10L, null, ZonedDateTime.now().plusDays(30)
            );

            // act
            ParameterizedTypeReference<ApiResponse<CouponAdminV1Dto.CouponResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponAdminV1Dto.CouponResponse>> response =
                testRestTemplate.exchange(ENDPOINT + "/999", HttpMethod.PUT, new HttpEntity<>(request), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("DELETE /api-admin/v1/coupons/{couponId}")
    @Nested
    class DeleteCoupon {

        @DisplayName("정상 요청이면, 200 OK를 반환한다.")
        @Test
        void returnsOk_whenRequestIsValid() {
            // arrange
            var coupon = couponFacade.createCoupon("쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30));

            // act
            ResponseEntity<Void> response =
                testRestTemplate.exchange(ENDPOINT + "/" + coupon.id(), HttpMethod.DELETE, new HttpEntity<>(null), Void.class);

            // assert
            assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @DisplayName("존재하지 않는 couponId이면, 404 Not Found를 반환한다.")
        @Test
        void returnsNotFound_whenCouponNotExists() {
            // act
            ParameterizedTypeReference<ApiResponse<Void>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<Void>> response =
                testRestTemplate.exchange(ENDPOINT + "/999", HttpMethod.DELETE, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api-admin/v1/coupons/{couponId}/issues")
    @Nested
    class GetCouponIssues {

        @DisplayName("발급 내역이 있으면, 200 OK와 목록을 반환한다.")
        @Test
        void returnsCouponIssueList_whenIssuesExist() {
            // arrange
            var coupon = couponFacade.createCoupon("쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30));
            couponFacade.issueCoupon(coupon.id(), 1L);

            // act
            ParameterizedTypeReference<ApiResponse<List<CouponAdminV1Dto.CouponIssueResponse>>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<CouponAdminV1Dto.CouponIssueResponse>>> response =
                testRestTemplate.exchange(ENDPOINT + "/" + coupon.id() + "/issues?page=0&size=20",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data()).hasSize(1)
            );
        }
    }
}
