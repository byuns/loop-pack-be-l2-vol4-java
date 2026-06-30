package com.loopers.consumer;

import com.loopers.eventhandled.infrastructure.EventHandledJpaRepository;
import com.loopers.metrics.domain.ProductMetricsModel;
import com.loopers.metrics.infrastructure.ProductMetricsJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class EventConsumerIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // 테스트는 메시지 발행 후 Consumer가 붙으므로 earliest로 처음부터 읽게 한다 (운영 default는 latest)
        registry.add("spring.kafka.properties.auto.offset.reset", () -> "earliest");
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private ProductMetricsJpaRepository productMetricsJpaRepository;

    @Autowired
    private EventHandledJpaRepository eventHandledJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("catalog-events에 LIKE_ADDED를 발행하면, like-aggregator Consumer가 product_metrics.like_count를 증가시킨다.")
    @Test
    void incrementsLikeCount_whenLikeAddedPublished() {
        // arrange
        long productId = 1L;
        String payload = "{\"eventType\":\"LIKE_ADDED\",\"productId\":" + productId + "}";

        // act — OutboxPoller와 동일하게 (topic, key, payload문자열) 발행
        kafkaTemplate.send("catalog-events", String.valueOf(productId), payload);

        // assert
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ProductMetricsModel metrics = productMetricsJpaRepository.findByProductId(productId).orElse(null);
            assertThat(metrics).isNotNull();
            assertThat(metrics.getLikeCount()).isEqualTo(1L);
        });
        assertThat(eventHandledJpaRepository.count()).isEqualTo(1L);
    }

    @DisplayName("order-events에 ORDER_CONFIRMED를 발행하면, sales-aggregator Consumer가 items별 sales_count를 증가시키고 멱등 키로 orderId를 기록한다.")
    @Test
    void incrementsSalesCount_whenOrderConfirmedPublished() {
        // arrange
        long orderId = 123L;
        String payload = orderConfirmedPayload(orderId);

        // act
        kafkaTemplate.send("order-events", String.valueOf(orderId), payload);

        // assert
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ProductMetricsModel m1 = productMetricsJpaRepository.findByProductId(1L).orElse(null);
            ProductMetricsModel m2 = productMetricsJpaRepository.findByProductId(2L).orElse(null);
            assertThat(m1).isNotNull();
            assertThat(m2).isNotNull();
            assertThat(m1.getSalesCount()).isEqualTo(2L);
            assertThat(m2.getSalesCount()).isEqualTo(1L);
        });
        assertThat(eventHandledJpaRepository.existsByEventId("order:" + orderId)).isTrue();
        assertThat(eventHandledJpaRepository.count()).isEqualTo(1L);
    }

    @DisplayName("같은 orderId의 ORDER_CONFIRMED가 (Kafka 좌표가 다른) 2건 도착해도, orderId 멱등 키 덕분에 sales_count는 1회만 반영된다.")
    @Test
    void appliesSalesCountOnce_whenSameOrderIdPublishedTwice() {
        // arrange — 동일 payload를 두 번 발행 → 서로 다른 offset(=과거 좌표 멱등이라면 못 막던 케이스)
        long orderId = 777L;
        String payload = orderConfirmedPayload(orderId);

        // act
        kafkaTemplate.send("order-events", String.valueOf(orderId), payload);
        kafkaTemplate.send("order-events", String.valueOf(orderId), payload);

        // assert — 두 번째는 event_handled("order:777") 존재로 skip되어 합산되지 않는다
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ProductMetricsModel m1 = productMetricsJpaRepository.findByProductId(1L).orElse(null);
            assertThat(m1).isNotNull();
            assertThat(m1.getSalesCount()).isEqualTo(2L);
        });
        // 잠시 더 기다려도 중복 반영이 없음을 확인 (두 번째 메시지 처리 시간 확보)
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ProductMetricsModel m1 = productMetricsJpaRepository.findByProductId(1L).orElseThrow();
            assertThat(m1.getSalesCount()).isEqualTo(2L);
        });
        assertThat(eventHandledJpaRepository.count()).isEqualTo(1L);
    }

    @DisplayName("catalog-events에 PRODUCT_VIEWED를 발행하면, view-aggregator Consumer가 product_metrics.view_count를 증가시킨다.")
    @Test
    void incrementsViewCount_whenProductViewedPublished() {
        // arrange
        long productId = 5L;
        String payload = productViewedPayload(productId, 1000L);

        // act
        kafkaTemplate.send("catalog-events", String.valueOf(productId), payload);

        // assert
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ProductMetricsModel metrics = productMetricsJpaRepository.findByProductId(productId).orElse(null);
            assertThat(metrics).isNotNull();
            assertThat(metrics.getViewCount()).isEqualTo(1L);
        });
    }

    @DisplayName("더 오래된 occurredAt의 PRODUCT_VIEWED가 뒤늦게 도착하면, occurredAt 가드로 view_count에 반영되지 않는다.")
    @Test
    void ignoresStaleView_whenOlderOccurredAtArrivesLater() {
        // arrange — 먼저 최신(2000) 발행 후, 더 오래된(1000)을 발행 (서로 다른 offset = 다른 eventId)
        long productId = 6L;

        // act
        kafkaTemplate.send("catalog-events", String.valueOf(productId), productViewedPayload(productId, 2000L));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ProductMetricsModel m = productMetricsJpaRepository.findByProductId(productId).orElse(null);
            assertThat(m).isNotNull();
            assertThat(m.getViewCount()).isEqualTo(1L);
        });
        kafkaTemplate.send("catalog-events", String.valueOf(productId), productViewedPayload(productId, 1000L));

        // assert — stale 이벤트는 처리(event_handled 기록)되더라도 view_count는 그대로 1
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ProductMetricsModel m = productMetricsJpaRepository.findByProductId(productId).orElseThrow();
            assertThat(m.getViewCount()).isEqualTo(1L);
        });
    }

    private String orderConfirmedPayload(long orderId) {
        return "{\"eventType\":\"ORDER_CONFIRMED\",\"orderId\":" + orderId + ","
            + "\"items\":[{\"productId\":1,\"quantity\":2},{\"productId\":2,\"quantity\":1}]}";
    }

    private String productViewedPayload(long productId, long occurredAtMillis) {
        return "{\"eventType\":\"PRODUCT_VIEWED\",\"productId\":" + productId + ",\"occurredAt\":" + occurredAtMillis + "}";
    }
}
