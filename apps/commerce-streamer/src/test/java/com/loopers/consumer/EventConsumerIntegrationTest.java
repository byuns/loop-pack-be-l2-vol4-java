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

    @DisplayName("order-events에 ORDER_CONFIRMED를 발행하면, sales-aggregator Consumer가 items별 sales_count를 증가시킨다.")
    @Test
    void incrementsSalesCount_whenOrderConfirmedPublished() {
        // arrange
        long orderId = 123L;
        String payload = "{\"eventType\":\"ORDER_CONFIRMED\",\"orderId\":" + orderId + ","
            + "\"items\":[{\"productId\":1,\"quantity\":2},{\"productId\":2,\"quantity\":1}]}";

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
        assertThat(eventHandledJpaRepository.count()).isEqualTo(1L);
    }
}
