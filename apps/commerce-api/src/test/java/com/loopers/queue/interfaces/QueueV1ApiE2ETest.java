package com.loopers.queue.interfaces;

import com.loopers.queue.application.QueueFacade;
import com.loopers.queue.domain.QueueStatus;
import com.loopers.support.response.ApiResponse;
import com.loopers.utils.RedisCleanUp;
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
import org.springframework.test.context.TestPropertySource;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 스케줄러가 대기 유저를 꺼내가면 순번 검증이 깨지므로 비활성화 (READY 검증은 admitNextBatch 수동 호출)
@TestPropertySource(properties = {"queue.max-size=5", "queue.scheduler.enabled=false"})
class QueueV1ApiE2ETest {

    private static final String ENTER_URL = "/api/v1/queue/enter";
    private static final String POSITION_URL = "/api/v1/queue/position";
    private static final String SIZE_URL = "/api/v1/queue/size";

    private final TestRestTemplate testRestTemplate;
    private final QueueFacade queueFacade;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    public QueueV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        QueueFacade queueFacade,
        RedisCleanUp redisCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.queueFacade = queueFacade;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("POST /api/v1/queue/enter")
    @Nested
    class Enter {

        @DisplayName("정상 요청이면, 200과 함께 순번을 반환한다.")
        @Test
        void returnsPosition_whenRequestIsValid() {
            // arrange
            QueueV1Dto.EnterRequest request = new QueueV1Dto.EnterRequest(1L);

            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response =
                testRestTemplate.exchange(ENTER_URL, HttpMethod.POST, new HttpEntity<>(request), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(response.getBody().data().position()).isEqualTo(1L)
            );
        }

        @DisplayName("이미 대기 중인 유저가 재요청하면, 200과 함께 기존 순번을 반환한다.")
        @Test
        void returnsExistingPosition_whenUserAlreadyInQueue() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.enter(2L);
            QueueV1Dto.EnterRequest request = new QueueV1Dto.EnterRequest(1L);

            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response =
                testRestTemplate.exchange(ENTER_URL, HttpMethod.POST, new HttpEntity<>(request), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().position()).isEqualTo(1L)
            );
        }

        @DisplayName("userId가 null이면, 400 BAD_REQUEST를 반환한다.")
        @Test
        void returnsBadRequest_whenUserIdIsNull() {
            // arrange
            QueueV1Dto.EnterRequest request = new QueueV1Dto.EnterRequest(null);

            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response =
                testRestTemplate.exchange(ENTER_URL, HttpMethod.POST, new HttpEntity<>(request), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("대기열 상한(5명)을 초과하면, 429 TOO_MANY_REQUESTS를 반환한다.")
        @Test
        void returnsTooManyRequests_whenQueueIsFull() {
            // arrange
            IntStream.rangeClosed(1, 5).forEach(i -> queueFacade.enter((long) i));
            QueueV1Dto.EnterRequest request = new QueueV1Dto.EnterRequest(6L);

            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response =
                testRestTemplate.exchange(ENTER_URL, HttpMethod.POST, new HttpEntity<>(request), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @DisplayName("GET /api/v1/queue/position")
    @Nested
    class GetPosition {

        @DisplayName("대기 중인 유저를 조회하면, 200과 함께 WAITING 상태를 반환한다.")
        @Test
        void returnsWaitingStatus_whenUserIsInQueue() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.enter(2L);

            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response = testRestTemplate.exchange(
                POSITION_URL + "?userId=2", HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().status()).isEqualTo(QueueStatus.WAITING),
                () -> assertThat(response.getBody().data().position()).isEqualTo(2L)
            );
        }

        @DisplayName("대기열에 없는 유저를 조회하면, 200과 함께 NOT_IN_QUEUE 상태를 반환한다.")
        @Test
        void returnsNotInQueueStatus_whenUserIsNotInQueue() {
            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response = testRestTemplate.exchange(
                POSITION_URL + "?userId=999", HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().status()).isEqualTo(QueueStatus.NOT_IN_QUEUE),
                () -> assertThat(response.getBody().data().position()).isNull()
            );
        }

        @DisplayName("userId 파라미터가 없으면, 400 BAD_REQUEST를 반환한다.")
        @Test
        void returnsBadRequest_whenUserIdIsMissing() {
            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response = testRestTemplate.exchange(
                POSITION_URL, HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("토큰이 발급된 유저를 조회하면, 200과 함께 READY 상태와 토큰을 반환한다.")
        @Test
        void returnsReadyStatusWithToken_whenTokenIsIssued() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.admitNextBatch();

            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.WaitingResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.WaitingResponse>> response = testRestTemplate.exchange(
                POSITION_URL + "?userId=1", HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().status()).isEqualTo(QueueStatus.READY),
                () -> assertThat(response.getBody().data().token()).isNotBlank(),
                () -> assertThat(response.getBody().data().position()).isNull()
            );
        }
    }

    @DisplayName("GET /api/v1/queue/size")
    @Nested
    class GetSize {

        @DisplayName("전체 대기 인원을 조회하면, 200과 함께 전체 인원을 반환한다.")
        @Test
        void returnsSize_whenRequestIsValid() {
            // arrange
            queueFacade.enter(1L);
            queueFacade.enter(2L);
            queueFacade.enter(3L);

            // act
            ParameterizedTypeReference<ApiResponse<QueueV1Dto.SizeResponse>> responseType = new ParameterizedTypeReference<>() {};
            ResponseEntity<ApiResponse<QueueV1Dto.SizeResponse>> response = testRestTemplate.exchange(
                SIZE_URL, HttpMethod.GET, new HttpEntity<>(null), responseType);

            // assert
            assertAll(
                () -> assertTrue(response.getStatusCode().is2xxSuccessful()),
                () -> assertThat(response.getBody().data().size()).isEqualTo(3L)
            );
        }
    }
}
