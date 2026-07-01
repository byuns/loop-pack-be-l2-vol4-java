package com.loopers.support.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 스트리머 전역 DLT(Dead Letter Topic) 설정.
 * - 1초 간격 3회 재시도 후 원본 토픽명 + ".DLT"로 라우팅
 * - auto.create.topics.enable=false이므로 각 DLT 토픽을 명시 선언
 * <p>
 * FixedBackOff 선택 이유: ExponentialBackOff는 같은 파티션의 다음 메시지가 백오프 동안 막혀
 * 처리량이 밀린다. 짧은 고정 간격으로 빠르게 재시도하고 영속 실패는 DLT로 옮겨 파티션을 해방한다.
 */
@Configuration
public class DltKafkaConfig {

    public static final String DLT_LISTENER_FACTORY = "dltListenerContainerFactory";

    public static final String CATALOG_EVENTS_DLT = "catalog-events.DLT";
    public static final String ORDER_EVENTS_DLT = "order-events.DLT";
    public static final String COUPON_ISSUE_REQUESTS_DLT = "coupon-issue-requests.DLT";

    @Bean
    public NewTopic catalogEventsDlt() {
        return TopicBuilder.name(CATALOG_EVENTS_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsDlt() {
        return TopicBuilder.name(ORDER_EVENTS_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic couponIssueRequestsDlt() {
        return TopicBuilder.name(COUPON_ISSUE_REQUESTS_DLT).partitions(1).replicas(1).build();
    }

    @Bean(name = DLT_LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> dltListenerContainerFactory(
        KafkaProperties kafkaProperties,
        KafkaTemplate<Object, Object> kafkaTemplate
    ) {
        Map<String, Object> consumerConfig = new HashMap<>(kafkaProperties.buildConsumerProperties());

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerConfig));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(kafkaTemplate),
            new FixedBackOff(1000L, 3L)
        );
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
