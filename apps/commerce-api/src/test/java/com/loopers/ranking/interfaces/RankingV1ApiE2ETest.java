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

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String TODAY = LocalDate.now().format(YYYYMMDD);
    private static final String YESTERDAY = LocalDate.now().minusDays(1).format(YYYYMMDD);
    private static final String KEY = "ranking:all:" + TODAY;
    private static final String YESTERDAY_KEY = "ranking:all:" + YESTERDAY;
    private static final String HOURLY_KEY = "ranking:hourly:current";

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
        redisTemplate.delete(YESTERDAY_KEY);
        redisTemplate.delete(HOURLY_KEY);
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

    @DisplayName("GET /api/v1/rankings — 과거 날짜")
    @Nested
    class GetPastRankings {

        @DisplayName("일자가 바뀌어도 date로 어제 키를 지정하면, 어제 랭킹이 정상 반환된다.")
        @Test
        void returnsYesterdayRanking_whenPastDateRequested() {
            // arrange — 어제 키에만 seed
            ProductModel a = productJpaRepository.save(new ProductModel("에어맥스", "나이키 운동화", 150000L, null));
            redisTemplate.opsForZSet().add(YESTERDAY_KEY, String.valueOf(a.getId()), 5.0);

            // act
            ParameterizedTypeReference<ApiResponse<List<RankingV1Dto.RankingResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<RankingV1Dto.RankingResponse>>> response =
                testRestTemplate.exchange("/api/v1/rankings?date=" + YESTERDAY + "&page=1&size=20",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            List<RankingV1Dto.RankingResponse> data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data).hasSize(1),
                () -> assertThat(data.get(0).rank()).isEqualTo(1L),
                () -> assertThat(data.get(0).productId()).isEqualTo(a.getId())
            );
        }

        @DisplayName("오늘·어제 키가 공존해도, date로 지정한 날짜의 랭킹만 반환된다(키 격리).")
        @Test
        void returnsOnlyRequestedDate_whenBothKeysExist() {
            // arrange — 오늘엔 a, 어제엔 b
            ProductModel a = productJpaRepository.save(new ProductModel("에어맥스", "나이키 운동화", 150000L, null));
            ProductModel b = productJpaRepository.save(new ProductModel("조던1", "나이키 농구화", 200000L, null));
            redisTemplate.opsForZSet().add(KEY, String.valueOf(a.getId()), 1.0);
            redisTemplate.opsForZSet().add(YESTERDAY_KEY, String.valueOf(b.getId()), 1.0);

            // act — 어제로 조회
            ParameterizedTypeReference<ApiResponse<List<RankingV1Dto.RankingResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<RankingV1Dto.RankingResponse>>> response =
                testRestTemplate.exchange("/api/v1/rankings?date=" + YESTERDAY + "&page=1&size=20",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert — 어제 키의 b만 반환(오늘 a는 섞이지 않음)
            List<RankingV1Dto.RankingResponse> data = response.getBody().data();
            assertAll(
                () -> assertThat(data).hasSize(1),
                () -> assertThat(data.get(0).productId()).isEqualTo(b.getId())
            );
        }
    }

    @DisplayName("GET /api/v1/rankings — 가중치 순서")
    @Nested
    class GetRankingsByWeight {

        @DisplayName("주문 1건(점수) 상품이 좋아요 3건 상품보다 상위(rank=1)로 반환된다.")
        @Test
        void orderOutranksThreeLikes_whenWeightApplied() {
            // arrange — 설계 가중치식 그대로: 주문 1건(1만원) vs 좋아요 3건
            double orderScore = 0.6 * Math.log10(1 + 10000.0); // ≈ 2.4
            double threeLikesScore = 0.2 * 3;                   // = 0.6
            ProductModel ordered = productJpaRepository.save(new ProductModel("주문상품", "주문 1건", 10000L, null));
            ProductModel liked = productJpaRepository.save(new ProductModel("좋아요상품", "좋아요 3건", 10000L, null));
            redisTemplate.opsForZSet().add(KEY, String.valueOf(ordered.getId()), orderScore);
            redisTemplate.opsForZSet().add(KEY, String.valueOf(liked.getId()), threeLikesScore);

            // act
            ParameterizedTypeReference<ApiResponse<List<RankingV1Dto.RankingResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<RankingV1Dto.RankingResponse>>> response =
                testRestTemplate.exchange("/api/v1/rankings?date=" + TODAY + "&page=1&size=20",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert — 주문상품이 1위
            List<RankingV1Dto.RankingResponse> data = response.getBody().data();
            assertAll(
                () -> assertThat(data).hasSize(2),
                () -> assertThat(data.get(0).productId()).isEqualTo(ordered.getId()),
                () -> assertThat(data.get(0).rank()).isEqualTo(1L),
                () -> assertThat(data.get(1).productId()).isEqualTo(liked.getId())
            );
        }
    }

    @DisplayName("GET /api/v1/rankings/hourly")
    @Nested
    class GetHourlyRankings {

        @DisplayName("롤링 키에 점수가 있으면, 200과 점수 내림차순 랭킹 목록(상품정보 포함)을 반환한다.")
        @Test
        void returnsHourlyRankingList_whenValidRequest() {
            // arrange — 시간 롤링 키에 seed
            ProductModel a = productJpaRepository.save(new ProductModel("에어맥스", "나이키 운동화", 150000L, null));
            ProductModel b = productJpaRepository.save(new ProductModel("조던1", "나이키 농구화", 200000L, null));
            redisTemplate.opsForZSet().add(HOURLY_KEY, String.valueOf(a.getId()), 3.0);
            redisTemplate.opsForZSet().add(HOURLY_KEY, String.valueOf(b.getId()), 9.0);

            // act
            ParameterizedTypeReference<ApiResponse<List<RankingV1Dto.RankingResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<RankingV1Dto.RankingResponse>>> response =
                testRestTemplate.exchange("/api/v1/rankings/hourly?page=1&size=20",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert — b(9.0)가 1위
            List<RankingV1Dto.RankingResponse> data = response.getBody().data();
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(data).hasSize(2),
                () -> assertThat(data.get(0).rank()).isEqualTo(1L),
                () -> assertThat(data.get(0).productId()).isEqualTo(b.getId()),
                () -> assertThat(data.get(1).productId()).isEqualTo(a.getId())
            );
        }

        @DisplayName("롤링 키가 비어 있으면, 200과 빈 목록을 반환한다.")
        @Test
        void returnsEmptyList_whenNoHourlyRanking() {
            // act
            ParameterizedTypeReference<ApiResponse<List<RankingV1Dto.RankingResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<RankingV1Dto.RankingResponse>>> response =
                testRestTemplate.exchange("/api/v1/rankings/hourly?page=1&size=20",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(response.getBody().data()).isEmpty()
            );
        }

        @DisplayName("page/size로 페이징하면, 해당 구간의 랭킹만 반환된다.")
        @Test
        void paginates_whenPageAndSizeGiven() {
            // arrange — 3개 상품을 점수순으로 seed (c=9 > b=5 > a=1)
            ProductModel a = productJpaRepository.save(new ProductModel("A", "1위 아님", 1000L, null));
            ProductModel b = productJpaRepository.save(new ProductModel("B", "2위", 1000L, null));
            ProductModel c = productJpaRepository.save(new ProductModel("C", "1위", 1000L, null));
            redisTemplate.opsForZSet().add(HOURLY_KEY, String.valueOf(a.getId()), 1.0);
            redisTemplate.opsForZSet().add(HOURLY_KEY, String.valueOf(b.getId()), 5.0);
            redisTemplate.opsForZSet().add(HOURLY_KEY, String.valueOf(c.getId()), 9.0);

            // act — size=1, page=2 → 2위(b)만
            ParameterizedTypeReference<ApiResponse<List<RankingV1Dto.RankingResponse>>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<List<RankingV1Dto.RankingResponse>>> response =
                testRestTemplate.exchange("/api/v1/rankings/hourly?page=2&size=1",
                    HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert — 2위 b, rank=2
            List<RankingV1Dto.RankingResponse> data = response.getBody().data();
            assertAll(
                () -> assertThat(data).hasSize(1),
                () -> assertThat(data.get(0).productId()).isEqualTo(b.getId()),
                () -> assertThat(data.get(0).rank()).isEqualTo(2L)
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
