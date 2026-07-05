package com.loopers.consumer;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kafka Consumer 옵션의 동작을 raw client로 격리 검증한다 (Spring 컨텍스트 없이 브로커에 직접 연결).
 * 문서 08의 Producer 옵션 테스트(acks/linger.ms/num.partitions)와 같은 "기대 vs 실제" 관찰 목적.
 */
@Testcontainers
class ConsumerOptionsExperimentTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    private static String bootstrap;

    @BeforeAll
    static void setUp() {
        bootstrap = KAFKA.getBootstrapServers();
    }

    // ---- helpers -------------------------------------------------------------

    private void createTopic(String topic, int partitions) throws ExecutionException, InterruptedException {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrap);
        try (Admin admin = Admin.create(props)) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        }
    }

    private KafkaProducer<String, String> newProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }

    private void produce(String topic, Integer partition, String key, String value) {
        try (KafkaProducer<String, String> p = newProducer()) {
            p.send(new ProducerRecord<>(topic, partition, key, value));
            p.flush();
        }
    }

    private KafkaConsumer<String, String> newConsumer(String groupId, Map<String, Object> overrides) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.putAll(overrides);
        return new KafkaConsumer<>(props);
    }

    private List<ConsumerRecord<String, String>> drain(KafkaConsumer<String, String> c, int max, Duration timeout) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline && out.size() < max) {
            ConsumerRecords<String, String> recs = c.poll(Duration.ofMillis(300));
            recs.forEach(out::add);
        }
        return out;
    }

    private void awaitAssignment(KafkaConsumer<String, String> c) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (c.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            c.poll(Duration.ofMillis(200));
        }
    }

    private String uniqueGroup() {
        return "exp-" + UUID.randomUUID();
    }

    // ---- 1. auto.offset.reset -------------------------------------------------

    @DisplayName("auto.offset.reset")
    @Nested
    class AutoOffsetReset {

        @DisplayName("earliest: 새 그룹은 발행되어 있던 과거 메시지를 처음부터 모두 읽는다.")
        @Test
        void earliest_readsFromBeginning() throws Exception {
            // arrange
            String topic = "aor-earliest-" + UUID.randomUUID();
            createTopic(topic, 1);
            produce(topic, null, "k", "m1");
            produce(topic, null, "k", "m2");
            produce(topic, null, "k", "m3");

            // act — 메시지 발행 '후'에 새 그룹이 구독
            try (KafkaConsumer<String, String> c = newConsumer(uniqueGroup(), Map.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
                c.subscribe(List.of(topic));
                List<ConsumerRecord<String, String>> got = drain(c, 3, Duration.ofSeconds(15));

                // assert
                assertThat(got).extracting(ConsumerRecord::value).containsExactly("m1", "m2", "m3");
            }
        }

        @DisplayName("latest: 새 그룹은 구독 시점 이전 메시지를 건너뛰고, 이후 발행분만 읽는다.")
        @Test
        void latest_skipsPastMessages() throws Exception {
            // arrange
            String topic = "aor-latest-" + UUID.randomUUID();
            createTopic(topic, 1);
            produce(topic, null, "k", "before-1");
            produce(topic, null, "k", "before-2");

            try (KafkaConsumer<String, String> c = newConsumer(uniqueGroup(), Map.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"))) {
                c.subscribe(List.of(topic));
                awaitAssignment(c); // 할당 후 latest로 끝 위치에 자리잡게 한다

                // act — 구독/할당 '후'에 발행한 것만 읽혀야 한다
                produce(topic, null, "k", "after-1");
                List<ConsumerRecord<String, String>> got = drain(c, 1, Duration.ofSeconds(15));

                // assert
                assertThat(got).extracting(ConsumerRecord::value).containsExactly("after-1");
            }
        }
    }

    // ---- 2. enable.auto.commit ------------------------------------------------

    @DisplayName("enable.auto.commit")
    @Nested
    class EnableAutoCommit {

        @DisplayName("true: poll 후 처리 전에 죽어도 offset이 커밋되어, 같은 그룹의 다음 Consumer가 메시지를 다시 받지 못한다 (유실).")
        @Test
        void autoCommitTrue_losesMessageOnCrashBeforeProcessing() throws Exception {
            // arrange
            String topic = "eac-true-" + UUID.randomUUID();
            String group = uniqueGroup();
            createTopic(topic, 1);
            produce(topic, null, "k", "m1");

            // act — Consumer1: poll만 하고 '처리 전에' close (auto-commit이 close 시 offset 커밋)
            try (KafkaConsumer<String, String> c1 = newConsumer(group, Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true))) {
                c1.subscribe(List.of(topic));
                assertThat(drain(c1, 1, Duration.ofSeconds(15))).hasSize(1);
            } // close → auto-commit

            // assert — Consumer2(같은 그룹): 이미 커밋된 offset 뒤라 받을 게 없음
            try (KafkaConsumer<String, String> c2 = newConsumer(group, Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true))) {
                c2.subscribe(List.of(topic));
                assertThat(drain(c2, 1, Duration.ofSeconds(5))).isEmpty();
            }
        }

        @DisplayName("false(manual): 처리 전에 죽고 ack 안 하면 offset이 커밋되지 않아, 같은 그룹의 다음 Consumer가 메시지를 다시 받는다 (재시도).")
        @Test
        void autoCommitFalse_redeliversMessageOnCrashWithoutAck() throws Exception {
            // arrange
            String topic = "eac-false-" + UUID.randomUUID();
            String group = uniqueGroup();
            createTopic(topic, 1);
            produce(topic, null, "k", "m1");

            // act — Consumer1: poll만 하고 commit 없이 close
            try (KafkaConsumer<String, String> c1 = newConsumer(group, Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
                c1.subscribe(List.of(topic));
                assertThat(drain(c1, 1, Duration.ofSeconds(15))).hasSize(1);
            } // close → 커밋 안 함

            // assert — Consumer2(같은 그룹): 커밋이 없어 earliest로 m1을 재처리
            try (KafkaConsumer<String, String> c2 = newConsumer(group, Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
                c2.subscribe(List.of(topic));
                assertThat(drain(c2, 1, Duration.ofSeconds(15))).extracting(ConsumerRecord::value).containsExactly("m1");
            }
        }
    }

    // ---- 3. max.poll.interval.ms ----------------------------------------------

    @DisplayName("max.poll.interval.ms")
    @Nested
    class MaxPollInterval {

        @DisplayName("처리가 max.poll.interval.ms를 초과하면 Consumer가 그룹에서 추방되어, 이후 commitSync가 CommitFailedException으로 실패한다 (→ 재처리 유발).")
        @Test
        void exceedingInterval_evictsConsumerAndFailsCommit() throws Exception {
            // arrange — 처리 한도를 2초로 짧게 설정
            String topic = "mpi-" + UUID.randomUUID();
            createTopic(topic, 1);
            produce(topic, null, "k", "m1");

            Map<String, Object> overrides = new HashMap<>();
            overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            overrides.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 2000);
            overrides.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6000);
            overrides.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2000);

            try (KafkaConsumer<String, String> c = newConsumer(uniqueGroup(), overrides)) {
                c.subscribe(List.of(topic));
                assertThat(drain(c, 1, Duration.ofSeconds(15))).hasSize(1);

                // act — 한도(2s)를 넘겨 '처리'가 오래 걸리는 상황 모사
                Thread.sleep(4000);

                // assert — 추방되어 커밋 실패
                assertThatThrownBy(c::commitSync).isInstanceOf(CommitFailedException.class);
            }
        }
    }

    // ---- 4. group.id ----------------------------------------------------------

    @DisplayName("group.id")
    @Nested
    class GroupId {

        @DisplayName("서로 다른 그룹은 같은 토픽의 모든 메시지를 각자 전부 받는다 (fan-out).")
        @Test
        void differentGroups_eachReceivesAll() throws Exception {
            // arrange — 2 partition, 양쪽에 1건씩
            String topic = "gid-fanout-" + UUID.randomUUID();
            createTopic(topic, 2);
            produce(topic, 0, "a", "p0");
            produce(topic, 1, "b", "p1");

            try (KafkaConsumer<String, String> g1 = newConsumer(uniqueGroup(), Map.of());
                 KafkaConsumer<String, String> g2 = newConsumer(uniqueGroup(), Map.of())) {
                g1.subscribe(List.of(topic));
                g2.subscribe(List.of(topic));

                // act
                List<ConsumerRecord<String, String>> got1 = drain(g1, 2, Duration.ofSeconds(15));
                List<ConsumerRecord<String, String>> got2 = drain(g2, 2, Duration.ofSeconds(15));

                // assert — 두 그룹 모두 2건 전부 수신
                assertThat(got1).extracting(ConsumerRecord::value).containsExactlyInAnyOrder("p0", "p1");
                assertThat(got2).extracting(ConsumerRecord::value).containsExactlyInAnyOrder("p0", "p1");
            }
        }

        @DisplayName("같은 그룹의 두 Consumer는 partition을 나눠 갖는다 — 합치면 전체, 각자는 일부만 (분담).")
        @Test
        void sameGroup_splitsPartitions() throws Exception {
            // arrange — 2 partition, 두 Consumer가 같은 그룹
            String topic = "gid-split-" + UUID.randomUUID();
            String group = uniqueGroup();
            createTopic(topic, 2);

            try (KafkaConsumer<String, String> c1 = newConsumer(group, Map.of());
                 KafkaConsumer<String, String> c2 = newConsumer(group, Map.of())) {
                c1.subscribe(List.of(topic));
                c2.subscribe(List.of(topic));

                // 두 Consumer가 함께 그룹에 조인해 1:1로 안정될 때까지 대기 (먼저 조인한 쪽이 둘 다 먹는 레이스 방지)
                long deadline = System.currentTimeMillis() + 20_000;
                while (System.currentTimeMillis() < deadline
                    && !(c1.assignment().size() == 1 && c2.assignment().size() == 1)) {
                    c1.poll(Duration.ofMillis(300));
                    c2.poll(Duration.ofMillis(300));
                }
                assertThat(c1.assignment()).hasSize(1);
                assertThat(c2.assignment()).hasSize(1);

                // act — 안정 할당 '후' 양쪽 partition에 1건씩 발행
                produce(topic, 0, "a", "p0");
                produce(topic, 1, "b", "p1");

                List<ConsumerRecord<String, String>> got1 = new ArrayList<>();
                List<ConsumerRecord<String, String>> got2 = new ArrayList<>();
                deadline = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < deadline && got1.size() + got2.size() < 2) {
                    c1.poll(Duration.ofMillis(300)).forEach(got1::add);
                    c2.poll(Duration.ofMillis(300)).forEach(got2::add);
                }

                // assert — 합치면 전체(2건), 각자는 1건씩 (disjoint)
                List<String> union = new ArrayList<>();
                got1.forEach(r -> union.add(r.value()));
                got2.forEach(r -> union.add(r.value()));
                assertThat(union).containsExactlyInAnyOrder("p0", "p1");
                assertThat(got1).hasSize(1);
                assertThat(got2).hasSize(1);
            }
        }
    }
}
