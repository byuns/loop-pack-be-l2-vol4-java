package com.loopers.ranking.interfaces;

import com.loopers.product.domain.ProductModel;
import com.loopers.product.infrastructure.ProductJpaRepository;
import com.loopers.product.interfaces.ProductV1Dto;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RankingV1ApiE2ETest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    private static final String KEY = "ranking:all:" + TODAY;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisTemplate.delete(KEY);
    }

    @DisplayName("GET /api/v1/rankings")
    @Nested
    class GetRankings {

        @DisplayName("정상 요청이면, 200과 점수 내림차순 랭킹 목록(상품정보 포함)을 반환한다.")
        @Test
        void returnsRankingList_whenValidRequest() {
            // arrange
            ProductModel a = productJpaRepository.save(new ProductModel("에어맥스", "나이키 운동화", 150000L, null));
            ProductModel b = productJpaRepository.save(new ProductModel("조던1", "나이키 농구화", 200000L, null));
            redisTemplate.opsForZSet().add(KEY, String.valueOf(a.getId()), 3.0);
            redisTemplate.opsForZSet().add(KEY, String.valueOf(b.getId()), 9.0);

            // act
            ParameterizedTypeReference<ApiResponse<List<RankingV1Dto.RankingResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<RankingV1Dto.RankingResponse>>> response =
                testRestTemplate.exchange("/api/v1/rankings?date=" + TODAY + "&page=1&size=20",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            List<RankingV1Dto.RankingResponse> data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data).hasSize(2),
                () -> assertThat(data.get(0).rank()).isEqualTo(1L),
                () -> assertThat(data.get(0).productId()).isEqualTo(b.getId()),
                () -> assertThat(data.get(0).name()).isEqualTo("조던1"),
                () -> assertThat(data.get(1).rank()).isEqualTo(2L),
                () -> assertThat(data.get(1).productId()).isEqualTo(a.getId())
            );
        }
    }

    @DisplayName("GET /api/v1/products/{id}")
    @Nested
    class GetProductWithRank {

        @DisplayName("상품이 순위권에 있으면, 상세 응답에 rank가 포함된다.")
        @Test
        void includesRank_whenProductIsRanked() {
            // arrange
            ProductModel a = productJpaRepository.save(new ProductModel("에어맥스", "나이키 운동화", 150000L, null));
            ProductModel b = productJpaRepository.save(new ProductModel("조던1", "나이키 농구화", 200000L, null));
            redisTemplate.opsForZSet().add(KEY, String.valueOf(a.getId()), 3.0);
            redisTemplate.opsForZSet().add(KEY, String.valueOf(b.getId()), 9.0);

            // act
            ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response =
                testRestTemplate.exchange("/api/v1/products/" + a.getId(),
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert (a는 b보다 점수가 낮아 2위)
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data().rank()).isEqualTo(2L)
            );
        }
    }
}
