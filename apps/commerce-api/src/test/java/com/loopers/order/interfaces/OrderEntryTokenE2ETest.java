package com.loopers.order.interfaces;

import com.loopers.product.domain.ProductModel;
import com.loopers.product.infrastructure.ProductJpaRepository;
import com.loopers.queue.domain.EntryTokenModel;
import com.loopers.queue.domain.EntryTokenRepository;
import com.loopers.stock.domain.StockModel;
import com.loopers.stock.infrastructure.StockJpaRepository;
import com.loopers.support.response.ApiResponse;
import com.loopers.user.domain.Gender;
import com.loopers.user.infrastructure.UserJpaRepository;
import com.loopers.user.interfaces.UserV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
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

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderEntryTokenE2ETest {

    private static final String ENDPOINT = "/api/v1/orders";
    private static final String LOGIN_ID = "user1";
    private static final String PASSWORD = "Pass123!";
    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private StockJpaRepository stockJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private EntryTokenRepository entryTokenRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long userId;

    @BeforeEach
    void setUp() {
        testRestTemplate.exchange(
            "/api/v1/users", HttpMethod.POST,
            new HttpEntity<>(new UserV1Dto.SignUpRequest(LOGIN_ID, PASSWORD, "홍길동", "test@example.com", "2000-01-01", Gender.MALE)),
            Void.class
        );
        userId = userJpaRepository.findByLoginId(LOGIN_ID).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Loopers-LoginId", LOGIN_ID);
        headers.set("X-Loopers-LoginPw", PASSWORD);
        return headers;
    }

    private ProductModel savedProduct(int totalStock) {
        ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", "나이키 운동화", 150000L, null));
        stockJpaRepository.save(new StockModel(product.getId(), totalStock));
        return product;
    }

    private OrderV1Dto.CreateRequest createRequest(Long productId) {
        return new OrderV1Dto.CreateRequest(List.of(new OrderV1Dto.OrderItemRequest(productId, 1)));
    }

    @DisplayName("POST /api/v1/orders (X-Entry-Token 검증)")
    @Nested
    class CreateOrderWithEntryToken {

        @DisplayName("유효한 토큰을 헤더에 포함하면, 200 OK를 반환한다.")
        @Test
        void returnsOk_whenValidTokenProvided() {
            // arrange
            ProductModel product = savedProduct(100);
            entryTokenRepository.save(new EntryTokenModel(userId, "valid-token"), TTL);
            HttpHeaders headers = authHeaders();
            headers.set("X-Entry-Token", "valid-token");

            // act
            ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ENDPOINT, HttpMethod.POST, new HttpEntity<>(createRequest(product.getId()), headers), responseType);

            // assert
            assertTrue(response.getStatusCode().is2xxSuccessful());
        }

        @DisplayName("토큰 헤더가 없으면, 403 Forbidden을 반환한다.")
        @Test
        void returnsForbidden_whenTokenHeaderIsMissing() {
            // arrange
            ProductModel product = savedProduct(100);

            // act
            ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ENDPOINT, HttpMethod.POST, new HttpEntity<>(createRequest(product.getId()), authHeaders()), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @DisplayName("잘못된 토큰이면, 403 Forbidden을 반환한다.")
        @Test
        void returnsForbidden_whenTokenIsInvalid() {
            // arrange
            ProductModel product = savedProduct(100);
            entryTokenRepository.save(new EntryTokenModel(userId, "valid-token"), TTL);
            HttpHeaders headers = authHeaders();
            headers.set("X-Entry-Token", "wrong-token");

            // act
            ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<OrderV1Dto.OrderResponse>> response =
                testRestTemplate.exchange(ENDPOINT, HttpMethod.POST, new HttpEntity<>(createRequest(product.getId()), headers), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
