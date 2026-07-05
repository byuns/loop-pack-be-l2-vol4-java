package com.loopers.coupon.interfaces;

import com.loopers.coupon.application.CouponFacade;
import com.loopers.coupon.domain.CouponIssueRequestStatus;
import com.loopers.coupon.domain.CouponType;
import com.loopers.coupon.infrastructure.CouponIssueJpaRepository;
import com.loopers.coupon.domain.CouponIssueModel;
import com.loopers.support.response.ApiResponse;
import com.loopers.user.domain.Gender;
import com.loopers.user.interfaces.UserV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponV1ApiE2ETest {

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private CouponFacade couponFacade;

    @Autowired
    private CouponIssueJpaRepository couponIssueJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private HttpHeaders authHeaders;
    private Long userId;

    @BeforeEach
    void setUp() {
        testRestTemplate.exchange("/api/v1/users", HttpMethod.POST,
            new HttpEntity<>(new UserV1Dto.SignUpRequest(
                "testuser", "Pass123!", "홍길동", "test@example.com", "2000-01-01", Gender.MALE
            )), Void.class);

        authHeaders = new HttpHeaders();
        authHeaders.set("X-Loopers-LoginId", "testuser");
        authHeaders.set("X-Loopers-LoginPw", "Pass123!");

        var meResponse = testRestTemplate.exchange(
            "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(authHeaders),
            new ParameterizedTypeReference<ApiResponse<UserV1Dto.UserResponse>>() {}
        );
        userId = meResponse.getBody().data().id();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api/v1/coupons/{couponId}/issue")
    @Nested
    class IssueCoupon {

        @DisplayName("선착순 쿠폰에 정상 요청이면, 202 Accepted와 PENDING 상태의 CouponIssueRequestResponse를 반환한다.")
        @Test
        void returnsCouponIssueRequestResponse_withPendingStatus_whenRequestIsValid() {
            // arrange
            var coupon = couponFacade.createCoupon("선착순 쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30), 100);

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> response =
                testRestTemplate.exchange(
                    "/api/v1/coupons/" + coupon.id() + "/issue",
                    HttpMethod.POST, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED),
                () -> assertThat(response.getBody().data().couponId()).isEqualTo(coupon.id()),
                () -> assertThat(response.getBody().data().status()).isEqualTo(CouponIssueRequestStatus.PENDING)
            );
        }

        @DisplayName("존재하지 않는 couponId이면, 404 Not Found를 반환한다.")
        @Test
        void returnsNotFound_whenCouponNotExists() {
            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> response =
                testRestTemplate.exchange(
                    "/api/v1/coupons/999/issue",
                    HttpMethod.POST, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @DisplayName("이미 발급 요청이 존재하는 쿠폰에 재요청하면, 409 Conflict를 반환한다.")
        @Test
        void returnsConflict_whenIssueRequestAlreadyExists() {
            // arrange
            var coupon = couponFacade.createCoupon("선착순 쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30), 100);
            testRestTemplate.exchange(
                "/api/v1/coupons/" + coupon.id() + "/issue",
                HttpMethod.POST, new HttpEntity<>(authHeaders), Void.class
            );

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> response =
                testRestTemplate.exchange(
                    "/api/v1/coupons/" + coupon.id() + "/issue",
                    HttpMethod.POST, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @DisplayName("만료된 쿠폰에 발급 시도하면, 400 Bad Request를 반환한다.")
        @Test
        void returnsBadRequest_whenCouponIsExpired() {
            // arrange
            var coupon = couponFacade.createCoupon("만료 쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().minusDays(1), 100);

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> response =
                testRestTemplate.exchange(
                    "/api/v1/coupons/" + coupon.id() + "/issue",
                    HttpMethod.POST, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("선착순 쿠폰이 아니면(maxCount 없음), 400 Bad Request를 반환한다.")
        @Test
        void returnsBadRequest_whenCouponHasNoMaxCount() {
            // arrange
            var coupon = couponFacade.createCoupon("일반 쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30));

            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> response =
                testRestTemplate.exchange(
                    "/api/v1/coupons/" + coupon.id() + "/issue",
                    HttpMethod.POST, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @DisplayName("GET /api/v1/coupons/issue-requests/{requestId}")
    @Nested
    class GetIssueRequestStatus {

        @DisplayName("본인의 발급 요청 조회이면, 200 OK와 요청 상태를 반환한다.")
        @Test
        void returnsIssueRequestStatus_whenOwnRequest() {
            // arrange
            var coupon = couponFacade.createCoupon("선착순 쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30), 100);
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            var issueResponse = testRestTemplate.exchange(
                "/api/v1/coupons/" + coupon.id() + "/issue",
                HttpMethod.POST, new HttpEntity<>(authHeaders), responseType
            );
            Long requestId = issueResponse.getBody().data().requestId();

            // act
            ResponseEntity<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> response =
                testRestTemplate.exchange(
                    "/api/v1/coupons/issue-requests/" + requestId,
                    HttpMethod.GET, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().requestId()).isEqualTo(requestId),
                () -> assertThat(response.getBody().data().couponId()).isEqualTo(coupon.id())
            );
        }

        @DisplayName("존재하지 않는 요청 ID이면, 404 Not Found를 반환한다.")
        @Test
        void returnsNotFound_whenRequestNotExists() {
            // act
            ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<CouponV1Dto.CouponIssueRequestResponse>> response =
                testRestTemplate.exchange(
                    "/api/v1/coupons/issue-requests/999",
                    HttpMethod.GET, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("GET /api/v1/users/me/coupons")
    @Nested
    class GetMyCoupons {

        @DisplayName("발급된 쿠폰이 있으면, 200 OK와 내 쿠폰 목록을 반환한다.")
        @Test
        void returnsCouponIssueList_whenCouponsExist() {
            // arrange — 발급 요청이 Consumer에 의해 처리된 결과(coupon_issues)를 직접 삽입
            var coupon = couponFacade.createCoupon("할인 쿠폰", CouponType.FIXED, 1000L, null, ZonedDateTime.now().plusDays(30));
            couponIssueJpaRepository.save(new CouponIssueModel(coupon.id(), userId));

            // act
            ParameterizedTypeReference<ApiResponse<List<CouponV1Dto.CouponIssueResponse>>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<CouponV1Dto.CouponIssueResponse>>> response =
                testRestTemplate.exchange(
                    "/api/v1/users/me/coupons",
                    HttpMethod.GET, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data()).hasSize(1)
            );
        }

        @DisplayName("발급된 쿠폰이 없으면, 200 OK와 빈 목록을 반환한다.")
        @Test
        void returnsEmptyList_whenNoCouponsIssued() {
            // act
            ParameterizedTypeReference<ApiResponse<List<CouponV1Dto.CouponIssueResponse>>> responseType =
                new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<CouponV1Dto.CouponIssueResponse>>> response =
                testRestTemplate.exchange(
                    "/api/v1/users/me/coupons",
                    HttpMethod.GET, new HttpEntity<>(authHeaders), responseType
                );

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data()).isEmpty()
            );
        }
    }
}
