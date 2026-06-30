# 08. Event & Kafka Pipeline

## 판단 기준

> "이게 실패하면 사용자 요청 자체가 실패해야 하나?"
> - Yes → 같은 TX
> - No, 유실 허용 → `@TransactionalEventListener(AFTER_COMMIT)`
> - No, 유실 불가 (다른 시스템) → Outbox + Kafka

---

## 좋아요 (LIKE_ADDED / LIKE_CANCELLED)

### ApplicationEvent — 완료

#### 왜 분리했는가

원래는 `LikeFacade.addLike()` 안에서 좋아요 저장과 likeCount 업데이트가 같은 TX로 묶여 있었다.

```
[TX 시작]
  likes INSERT
  likeCount UPDATE  ← 같은 TX
[TX 커밋]
```

원자적이라 데이터 정합성은 보장됐지만, 두 가지 문제가 있었다.

첫째, likeCount 업데이트가 실패하면 좋아요 저장 자체도 롤백됐다. 집계 실패가 사용자의 좋아요 행위 실패로 이어지는 것은 과도한 결합이다.

둘째, 같은 TX 안에 두 책임이 묶여 있으면 분리하기 어렵다. 나중에 likeCount를 Redis나 외부 집계 서버로 옮기고 싶어도, 좋아요 저장 로직과 강하게 결합되어 있어서 한쪽만 바꾸기가 힘들다.

"좋아요 저장"과 "집계"는 중요도가 다르고, 변경 이유도 다르다. 집계가 틀려도 좋아요는 성공해야 한다. 그래서 TX를 분리했다.

```
[TX 시작]              [별도 TX, 별도 스레드]
  likes INSERT    →    likeCount UPDATE
[TX 커밋]
  ↓ AFTER_COMMIT
  LikeAddedEvent 발행
```

이로써 집계가 실패해도 좋아요는 유지된다. 대신 likeCount 업데이트는 **eventual consistency**로 처리된다.

#### ApplicationEvent의 한계

이벤트가 메모리 위에만 존재한다. TX 커밋 직후 서버가 죽으면 이벤트는 사라지고 likeCount 업데이트는 영영 실행되지 않는다.

#### @Async가 필요한 이유

`@TransactionalEventListener(AFTER_COMMIT)` 단독으로는 커밋 이후 원본 커넥션이 아직 반환되지 않은 상태에서 `@Transactional(REQUIRES_NEW)`가 새 커넥션을 요청 → 동시 요청 시 커넥션 풀 고갈.

→ `@Async` 추가로 별도 스레드에서 실행. 원본 커넥션 반환 후 새 커넥션 획득하므로 풀 고갈 없음.

---

### Outbox + Kafka Producer — 완료

ApplicationEvent 방식의 유실 문제를 해결하기 위해 Outbox로 교체했다.

좋아요 저장과 같은 TX 안에서 `outbox_events`에 INSERT → TX가 커밋되면 이벤트도 DB에 보장됨 → Poller가 Kafka로 발행.

| | ApplicationEvent | Outbox + Kafka |
|---|---|---|
| 서버 크래시 시 | 이벤트 유실 | DB에 남아 복구 가능 |
| likeCount 반영 속도 | 커밋 직후 즉시 | Poller 주기만큼 지연 |
| 구현 복잡도 | 낮음 | 높음 |

**생성 파일**
- `support/outbox/OutboxEvent.java`
- `support/outbox/OutboxRepository.java`, `OutboxRepositoryImpl.java`, `OutboxJpaRepository.java`
- `support/outbox/OutboxPoller.java` — 5초마다 미발행 이벤트 Kafka로 발행

**수정 파일**
- `LikeFacade.addLike/cancelLike` — `eventPublisher` 제거 → `outboxRepository.save()` 로 교체
- `LikeEventListener.java` — 삭제
- `build.gradle.kts` — `:modules:kafka` 의존성 추가
- `application.yml` — Kafka producer 설정 (`acks=all`, `enable-idempotence=true`)
- `AsyncConfig.java` — `@EnableScheduling` 추가

**토픽:** `catalog-events` / Partition Key: `productId`

---

### Consumer — 완료

`commerce-streamer` 모듈에서 `catalog-events` 구독, `product_metrics.like_count` upsert.

**생성 파일**
- `metrics/domain/ProductMetricsModel.java` — `product_metrics` 엔티티 (`productId` unique, `likeCount`, `salesCount`)
- `metrics/domain/ProductMetricsRepository.java`, `infrastructure/ProductMetricsJpaRepository.java`, `infrastructure/ProductMetricsRepositoryImpl.java`
- `eventhandled/domain/EventHandledModel.java` — `event_id` PK, `handled_at`
- `eventhandled/domain/EventHandledRepository.java`, `infrastructure/EventHandledJpaRepository.java`, `infrastructure/EventHandledRepositoryImpl.java`
- `like/application/LikeAggregatorService.java` — `@Transactional` 안에서 멱등 체크 + like_count 증감
- `like/interfaces/consumer/LikeEventConsumer.java` — `@KafkaListener(topics="catalog-events", groupId="like-aggregator")`

**수정 파일**
- `modules/kafka/src/main/resources/kafka.yml`
  - 오타 수정: `value-serializer` → `value-deserializer` (Consumer엔 serializer가 없음. 그동안 무시되고 default 적용되던 버그)
  - local/test 프로필 bootstrap-servers: `localhost:19092` → `localhost:9092` (docker-compose 환경에 맞춤)
- `apps/commerce-streamer/src/main/resources/application.yml`
  - `server.port: 8085` (commerce-api의 8080·management 8081과 충돌 회피)
  - `application.name: commerce-api → commerce-streamer` (copy-paste 흔적 정리)
  - `monitoring.yml` import 제거 — 이걸 import하면 actuator port 8081로 강제되어 commerce-api의 management와 충돌

**처리 흐름**

```
1. catalog-events에 LIKE_ADDED 도착
2. Consumer 수신 → eventId = "catalog-events:" + partition + ":" + offset
3. @Transactional 내부:
   a. event_handled에 eventId 존재? → 있으면 skip + ack
   b. event_handled INSERT (멱등 기록)
   c. product_metrics.like_count +1 (없으면 새로 생성)
4. ack.acknowledge() → offset commit
5. 처리 실패 → ack 안 함 → 다음 poll에 재시도 (영속 실패 시 lag 누적)
```

**Consumer group**: `like-aggregator`
- catalog-events 3 partition 모두 단일 consumer가 처리 (현재 인스턴스 1개)
- 향후 부하 증가 시 인스턴스 수 늘려서 partition 분담 가능 (최대 3개)
- 결제 집계용 sales-aggregator group은 같은 토픽이 아니라 order-events 구독이라 무관

**알려진 한계 (후속 보강)**

1. **Producer 재시작 후 중복**: outbox poller가 broker write 성공 → published_at 갱신 전 크래시 → 재시작 후 같은 outbox 이벤트를 새 (partition, offset)으로 재발행. 현재 eventId가 Kafka 좌표 기반이라 중복 감지 불가. **해결**: OutboxPoller가 outbox.id를 Kafka header로 함께 전달, Consumer가 그걸 eventId로 사용.
2. **DLT 미구현**: 영속 실패 시 lag 무한 누적. production 가기 전 도입 필요.

---

## 결제 (ORDER_CONFIRMED)

### ApplicationEvent — 완료

`PaymentFacade.applyPgResult()` 에서 결제 결과에 따라 이벤트 발행.

- SUCCESS → `PaymentConfirmedEvent(orderId, finalAmount)` — 알림 목적
- FAIL → `PaymentFailedEvent(orderId)`

---

### Outbox + Kafka Producer — 완료

`PaymentFacade.applyPgResult()` SUCCESS 분기에 `order-events` outbox INSERT 추가.

`PaymentConfirmedEvent`(알림용)와 Outbox INSERT(집계용)는 목적이 달라 공존한다.

**토픽:** `order-events` / Partition Key: `orderId`

**payload 구조** (self-contained — Consumer가 추가 API 호출 없이 sales_count upsert 가능):
```json
{
  "eventType": "ORDER_CONFIRMED",
  "orderId": 123,
  "items": [
    {"productId": 1, "quantity": 2},
    {"productId": 2, "quantity": 1}
  ]
}
```

ObjectMapper로 직렬화 (LikeFacade의 문자열 concat과 달리 items가 있어 escape 문제 방지).

---

### Consumer — 완료

좋아요 Consumer와 같은 패턴으로 `commerce-streamer` 모듈에 구현. `commerce-collector`는 별도 모듈로 분리하지 않고, 재사용 대상(`ProductMetricsModel`, `event_handled`)과 좋아요 Consumer가 이미 있는 `commerce-streamer`에 함께 두었다.

`order-events` 구독 → payload의 `items`를 순회하며 `product_metrics.sales_count`를 `quantity`만큼 upsert. payload가 self-contained라 추가 API 호출 없음.

**생성 파일**
- `payment/application/SalesItem.java` — payload `items` 한 건 (`productId`, `quantity`) record
- `payment/application/SalesAggregatorService.java` — `@Transactional` 안에서 멱등 체크 + items별 `sales_count` 원자 증가
- `payment/interfaces/consumer/PaymentEventConsumer.java` — `@KafkaListener(topics="order-events", groupId="sales-aggregator")`, manual Ack

**테스트 파일**
- `SalesAggregatorServiceTest` (단위) — 신규 eventId 집계 호출 / 멱등 skip
- `PaymentEventConsumerTest` (단위) — ORDER_CONFIRMED 파싱+호출+ack / 미지원 eventType skip+ack / 서비스 예외 시 ack 안 함
- `SalesAggregatorConcurrencyIntegrationTest` (통합) — 같은 상품을 가진 주문 10건 동시 처리 → sales_count 합계 일치(lost update 없음) / 미존재 상품 UPSERT 생성

**처리 흐름**

```
1. order-events에 ORDER_CONFIRMED 도착
2. Consumer 수신 → eventId = "order:" + orderId   (비즈니스 키)
3. @Transactional 내부:
   a. event_handled에 eventId 존재? → 있으면 skip
   b. event_handled INSERT (멱등 기록)
   c. items 순회: incrementSalesCount(productId, quantity)  ← 원자 UPSERT
4. ack.acknowledge() → offset commit
5. 처리 실패 → ack 안 함 → 다음 poll에 재시도
```

**Consumer group**: `sales-aggregator` (like-aggregator와 group·토픽 모두 분리)

#### 좋아요와 다르게 강화한 두 가지 (결제 고유 트레이드오프)

좋아요 Consumer를 미러링했지만, 결제는 구조가 달라 두 지점을 보강했다.

**1. 원자 UPSERT (동시성)** — `catalog-events`는 `productId`로 파티셔닝돼 같은 상품이 항상 같은 파티션에서 순차 처리되지만, `order-events`는 `orderId`로 파티셔닝되는데 집계 단위는 `productId`다. 한 주문이 여러 상품을 포함하고 같은 상품이 다른 주문(=다른 파티션·인스턴스)에서 동시에 팔릴 수 있어, `find → addSalesCount → save`(read-modify-write)는 인스턴스를 늘리는 순간 lost update가 난다(`@Version`도 없음). 그래서 `INSERT ... ON DUPLICATE KEY UPDATE sales_count = sales_count + :delta` 원자 UPSERT(`ProductMetricsRepository.incrementSalesCount`)로 교체했다. 멱등은 `event_handled`가 따로 책임지므로 역할이 분리된다. 회귀를 막기 위해 엔티티의 `addSalesCount`는 제거했다.

**2. orderId 멱등 키** — `ORDER_CONFIRMED`는 주문당 평생 1회뿐이라 비즈니스 키 `"order:" + orderId`를 멱등 키로 쓴다. 좌표(`topic:partition:offset`) 기반과 달리 outbox 재발행·리밸런싱·리플레이로 Kafka 좌표가 바뀌어도 중복을 정확히 감지한다 → 좋아요 Consumer의 "Producer 재시작 후 중복" 한계를 결제는 구조적으로 회피. (좋아요는 한 상품에 LIKE_ADDED가 여러 번이라 비즈니스 키를 못 쓰고 좌표를 쓸 수밖에 없다.)

**알려진 한계**: DLT 미구현(영구 실패 시 파티션 blocking → lag 누적). 취소/환불 시 `sales_count` 감소 경로 없음 → 현재 `sales_count`는 "확정 누적"이며 "순매출"이 아님. 후속 보강 대상.

---

### Consumer 통합 검증 (Testcontainers) — 완료

좋아요·결제 Consumer를 실제 Kafka(`apache/kafka:3.8.1` Testcontainer) + MySQL로 end-to-end 검증.

**테스트 파일**: `apps/commerce-streamer/.../consumer/EventConsumerIntegrationTest.java`
- `catalog-events`에 `LIKE_ADDED` 발행 → `product_metrics.like_count == 1`, `event_handled` 1건
- `order-events`에 `ORDER_CONFIRMED`(items 2건) 발행 → 각 productId의 `sales_count` 증가, `event_handled` 키가 `"order:{id}"` 1건
- 같은 orderId의 `ORDER_CONFIRMED`를 2회 발행 → orderId 멱등 키로 `sales_count`는 1회만 반영
- `OutboxPoller`와 동일하게 `kafkaTemplate.send(topic, key, payload문자열)`로 발행, Awaitility로 비동기 반영 대기
- 테스트는 발행 후 Consumer가 붙으므로 `auto.offset.reset=earliest`로 override (운영 default는 `latest`)

**검증으로 발견·수정한 버그 — 이중 직렬화 (둘 다 깨져 있었음)**

Producer의 `value-serializer`가 `JsonSerializer`였는데, Outbox `payload`는 이미 `ObjectMapper`로 직렬화된 **JSON 문자열**이다. JsonSerializer가 이 문자열을 한 번 더 직렬화 → `"{\"eventType\":...}"`처럼 바깥에 따옴표가 덧씌워진 채 발행됨.

Consumer는 `StringDeserializer`로 받아 `objectMapper.readTree(value)` 했으나, 값이 JSON 객체가 아니라 **JSON 문자열 리터럴**이라 `TextNode`로 파싱됨 → `.get("eventType")`이 `null` → NPE → ack 안 됨 → lag 무한 누적.

→ **수정**: `kafka.yml` producer `value-serializer`를 `JsonSerializer` → `StringSerializer`로 변경. payload가 이미 JSON 문자열이므로 Consumer(`StringDeserializer`)와 String↔String 대칭이 맞다. 유일한 실제 Producer는 `OutboxPoller`(문자열 발행)라 부작용 없음.

> 교훈: Producer/Consumer 직렬화 짝은 항상 같이 맞춰야 한다. payload를 애플리케이션에서 직접 문자열로 만들면 serializer는 String 계열이어야 하고, 객체를 그대로 넘기면 Json 계열이어야 한다. 섞이면 조용히 이중 인코딩된다.

---

## 쿠폰 (선착순 발급)

### 현재 문제

`CouponFacade.issueCoupon()` 동기 TX → 동시 요청 시 DB 락 경합, 수량 제한 없음

### 변경 흐름 — 미구현

```
POST /api/v1/coupons/{couponId}/issue  →  Kafka 발행만  →  202 Accepted
GET  /api/v1/coupons/{couponId}/issue/status  →  PENDING / ISSUED / FAILED
```

Consumer가 `couponId` 파티션 키 덕분에 같은 쿠폰 요청을 직렬로 처리 → 수량 초과 방지

**신규 파일**
- `coupon_issue_requests` 테이블 (status: PENDING / ISSUED / FAILED)
- `CouponFacade.requestCouponIssue()` — 만료 여부만 확인 후 Kafka 발행
- `CouponIssueConsumer.java` — 수량 제한 + 중복 방지 + manual Ack

---

## 공통 — Kafka Producer 옵션

| 옵션 | 기본값 | 현재 설정 | 역할 | 테스트 |
|---|---|---|---|---|
| `acks` | `1` | `all` | 브로커 응답 수준 (0=없음, 1=리더, all=전체 ISR) | ★ |
| `enable.idempotence` | `false` | `true` | 재시도 시 중복 발행 방지 | ★ |
| `retries` | `2147483647` | 기본값 | 발행 실패 시 재시도 횟수 | ★ |
| `retry.backoff.ms` | `100` | 기본값 | 재시도 간격 (ms) | |
| `linger.ms` | `0` | 기본값 | 배치 전송 대기 시간 (0=즉시 전송) | ★ |
| `batch.size` | `16384` | 기본값 | 배치 최대 크기 (bytes) | |
| `compression.type` | `none` | 기본값 | 압축 방식 (none, gzip, snappy, lz4) | |
| `max.in.flight.requests.per.connection` | `5` | 기본값 | 응답 대기 중 최대 요청 수 (순서 보장 시 1로 설정) | |
| `request.timeout.ms` | `30000` | 기본값 | 브로커 응답 대기 타임아웃 | |
| `delivery.timeout.ms` | `120000` | 기본값 | 발행 최종 타임아웃 (retries 포함) | |

★ 표시가 테스트해볼 만한 옵션.

### 옵션 테스트 결과

#### acks (단일 broker 환경)

**Test A — 정상 상태 지연 측정 (단일 broker 한계로 비교 불가 → 다중 broker에서 재측정 완료)**

`outbox INSERT → published_at` delta 측정했으나 Poller 5초 폴링 주기가 변동을 다 흡수해서 acks 차이가 묻힘. 단일 broker에선 acks=1과 acks=all도 사실상 동일 (기다릴 follower가 없음). → 3-broker 클러스터에서 재측정 완료 (아래 [다중 브로커 실험](#다중-브로커-실험-acks--mininsyncreplicas--outbox-복구) 참고).

**Test B — `acks=0` + `enable.idempotence=true` (의도적 모순 조합)**

기대: Kafka 클라이언트가 ConfigException으로 시작 실패.
실제: 에러 없이 시작. 실제 ProducerConfig 로그에 `enable.idempotence = false`로 찍힘 — 조용히 disable됨.

원인: Kafka 3.0+에서 `enable.idempotence` 기본값이 `true`로 바뀌었다. YAML에 `true`를 명시해도 클라이언트는 "기본값 그대로 = 사용자가 명시적으로 override한 게 아님"으로 간주해서, `acks`와 충돌하면 throw 대신 silent disable로 처리한다.

→ idempotence를 보장하려면 `acks=all`도 같이 명시. 한쪽만 바꾸면 의도와 다르게 동작할 수 있다.

**Test C — Kafka down 시 acks=0 vs acks=all**

기대: acks=0은 broker 응답 안 기다리니 발행 성공으로 처리될 줄 알았음.
실제: 둘 다 `published_at=NULL`, send 실패.

원인: `acks`는 TCP 연결이 잡힌 후 "broker 응답 기다림" 옵션이지 "TCP 없이도 OK"가 아니다. broker가 죽으면 TCP handshake부터 실패해서 `acks` 값과 무관하게 send() 자체가 깨진다.

**부가 검증 — Outbox 복구 동작**

Kafka down → outbox에 이벤트 쌓임 → Kafka 복구 → Poller가 자동 retry → 발행 성공. Outbox의 At Least Once 보장이 실제 동작으로 확인됨.

#### linger.ms (Outbox 동기 Poller 환경)

20건을 outbox에 한 번에 INSERT → Poller가 처리하는 시간(first published_at → last published_at)을 비교.

| 설정 | 20건 처리 시간 | event당 평균 |
|---|---|---|
| `linger.ms=0` (default) | 320 ms | ~16 ms |
| `linger.ms=100` | 2533 ms | ~127 ms |

linger.ms=100이 **약 8배 느림**.

원인: 우리 Poller가 `for (event) { send().get(10s) }` 동기 패턴이라 매 iteration에 producer 버퍼엔 항상 1건만 들어감 → linger.ms 만큼 끝까지 대기 후 단일 메시지 flush → 매 event에 100ms 추가.

→ linger.ms는 **여러 send()가 짧은 시간에 동시 발생**하는 환경(여러 producer 동시 호출, 비동기 send + flush 패턴)에서 batch 효과로 처리량↑·네트워크 RTT↓를 얻는 옵션. **현재 우리 Poller엔 안 맞음 — 0(default) 유지.**

향후 개선: Poller를 비동기 send + 마지막에 한 번 flush() + 일괄 markAsPublished 패턴으로 바꾸면 linger.ms를 활용할 수 있음 (별도 작업).

#### num.partitions (Broker 옵션)

`catalog-events` 토픽을 `kafka-topics.sh --alter --partitions 3`으로 1→3 변경 후, productId 1~5에 대해 각 2건씩(=10건) 발행. partition별 분포 관찰.

**결과**

| Partition | 메시지 |
|---|---|
| 0 | productId=1, productId=5 (각 2건) |
| 1 | productId=4 (2건) |
| 2 | productId=2, productId=3 (각 2건) |

**관찰**

1. **같은 key는 항상 같은 partition** — productId=1의 LIKE_ADDED/LIKE_CANCELLED가 둘 다 partition 0. Consumer가 partition 단위 순차 처리하므로 같은 productId의 이벤트 순서 보장됨.
2. **다른 key는 분산** — 5개 productId가 3 partition에 흩어짐 → Consumer 여러 인스턴스가 partition 나눠 처리 가능 → 처리량 증가.
3. **해시 분산은 완벽 균등 아님** — key 개수가 적으면 hash collision으로 한쪽에 몰릴 수 있음. 운영에선 key 수가 많아 통계적으로 균등.
4. **partition 늘려도 같은 key는 같은 partition 유지** — alter 전후 productId=1은 일관되게 partition 0. 해시 함수가 deterministic.

**Outbox 설계와의 연결**

- `catalog-events` partition key = `productId` → 한 상품의 좋아요 변동이 순서 보장되어야 likeCount 정확
- `order-events` partition key = `orderId` → 같은 주문 이벤트가 같은 consumer로 가서 멱등 처리 편리

**트레이드오프**

- partition 많을수록 처리량↑, 같은 key 내 순서 보장은 유지
- 너무 많으면 broker 메모리/디스크 부담↑, leader election/리밸런싱 비용↑
- 적정선: 예상 throughput ÷ 단일 partition 처리량으로 추정

---

## 공통 — Kafka Broker 옵션

브로커는 직접 구현하지 않고 Kafka 설정값으로만 동작을 조정한다.

| 옵션 | 기본값 | 역할 | 테스트 |
|---|---|---|---|
| `min.insync.replicas` | `1` | `acks=all` 시 최소 동기화 복제본 수. 이 수 미만이면 발행 거부 | ★ |
| `auto.create.topics.enable` | `true` | 존재하지 않는 토픽에 발행 시 자동 생성 여부 | ★ |
| `num.partitions` | `1` | 토픽 기본 파티션 수 | ★ |
| `default.replication.factor` | `1` | 토픽 기본 복제본 수 | |
| `log.retention.hours` | `168` | 메시지 보존 기간 (7일) | |
| `log.retention.bytes` | `-1` | 파티션당 최대 보존 크기 (-1=무제한) | |
| `message.max.bytes` | `1048576` | 브로커가 허용하는 최대 메시지 크기 (1MB) | |

★ 표시가 테스트해볼 만한 옵션.

### 다중 브로커 실험 (acks · min.insync.replicas · outbox 복구)

우리 outbox → Kafka 설계의 At-Least-Once 보장은 전부 `acks=all`에 걸려 있다. 그런데 단일 broker에선 `acks=all`이 사실상 `acks=1`이라(기다릴 follower 없음) **그 보장이 한 번도 실제로 검증된 적이 없었다.** 3-broker 클러스터로 재현.

> **컨슈머는 다중 broker가 거의 불필요하다.** 컨슈머 동작(group/partition/offset)은 broker 1대 + 컨슈머 여러 개로 충분히 재현된다. 다중 broker는 **프로듀서/내구성 축**의 주제다.

**토폴로지 — 전용 controller 1 + broker 3** (`docker-compose.multi-broker.yml`)

combined(`broker,controller`) 모드로 3노드를 두면 broker 2대를 죽일 때 controller 쿼럼(3중 2)도 함께 깨져 ISR 변경이 메타데이터에 반영되지 않는다. 전용 controller를 분리하면 broker를 죽여도 쿼럼이 유지돼 `min.insync.replicas` 발동을 깔끔히 관찰할 수 있다. 핵심 설정: 내부 토픽 RF=3(`offsets.topic.replication.factor` 등), `default.replication.factor=3`, `min.insync.replicas=2`. 토픽은 `--replication-factor 3 --config min.insync.replicas=2`로 생성.

> 실행 명령은 docker exec로 broker 컨테이너 내 CLI(`kafka-topics.sh`, `kafka-producer-perf-test.sh`)를 쓴다. Windows Git Bash는 `/opt/...` 경로를 변환하므로 **PowerShell**에서 실행.

#### ① acks=1 vs acks=all 지연 (Test A 재측정)

클러스터 정상(ISR=3)일 때 `kafka-producer-perf-test`로 2만 건 발행.

| acks | 처리량 | 평균 지연 | p99 |
|---|---|---|---|
| `1` (리더만) | ~26,500 rec/s | ~219 ms | ~410 ms |
| `all` (ISR=2 ack 대기) | ~18,900 rec/s | ~467 ms | ~747 ms |

→ `acks=all`은 follower ack 대기로 **지연 약 2배, 처리량 약 30%↓**. 단일 broker에선 묻혔던 내구성의 비용이 드러난다.

#### ② min.insync.replicas 발동

리더(broker2)는 살려둔 채 follower만 하나씩 죽이며 `acks=all` 발행을 관찰.

| 상태 | ISR | acks=all 발행 |
|---|---|---|
| 전체 정상 | 3 | 성공 |
| broker 1대 down | 2 | **성공** (2 ≥ min 2) |
| broker 2대 down | 1 | **거부** — `NotEnoughReplicasException`: "fewer in-sync replicas than required" (0 records sent) |

→ 리더가 **살아있어도** ISR이 `min.insync.replicas` 미만이면 `acks=all` 쓰기를 거부한다. RF=3 / min.insync=2 = "1대 장애는 버티고, 2대 장애면 차라리 멈춘다"는 가용성↔내구성 균형점. `min.insync.replicas=1`이었다면 ISR=1에서도 썼겠지만 그 1벌이 죽으면 유실.

#### ③ outbox 복구 안전망

죽인 broker를 복구(`docker start`)하면 ISR이 회복되고, (Poller가 재시도할) `acks=all` 발행이 **다시 성공**한다.

→ ISR 부족으로 발행이 거부되는 동안 이벤트는 `outbox_events`에 `published_at=NULL`로 남고, `OutboxPoller`가 5초마다 미발행분을 재시도한다. broker 복구 후 같은 재시도가 성공 → **유실 없음(At Least Once)**. "Kafka down → outbox 잔류 → 복구 → 자동 retry"를 다중 broker ISR 부족 상황으로 확장 검증.

**교훈**: ①`acks=all`은 다중 broker에서만 진짜 의미를 갖는다. ②`min.insync.replicas`는 가용성↔내구성 다이얼이다. ③내구성은 공짜가 아니다(지연 ~2배) — 그래서 좋아요(유실 허용)는 느슨해도 되고 결제(유실 불가)는 이 비용을 받아들인다. ④outbox가 ISR 부족까지 흡수한다.

---

## 공통 — Kafka Consumer 옵션

| 옵션 | 기본값 | 현재 설정 | 역할 | 테스트 |
|---|---|---|---|---|
| `group.id` | 없음 | 설정 필요 | Consumer 그룹 식별자. 같은 그룹은 파티션을 나눠서 처리 | ★ |
| `auto.offset.reset` | `latest` | 설정 필요 | 오프셋 없을 때 시작 위치 (earliest=처음부터, latest=이후부터) | ★ |
| `enable.auto.commit` | `true` | `false` | 오프셋 자동 커밋 여부 (manual Ack 사용 시 false) | ★ |
| `max.poll.records` | `500` | 기본값 | 한 번 poll 시 가져올 최대 메시지 수 | ★ |
| `max.poll.interval.ms` | `300000` | 기본값 | poll 간 최대 처리 시간. 초과 시 리밸런싱 발생 | ★ |
| `session.timeout.ms` | `45000` | 기본값 | 브로커가 Consumer 장애로 판단하는 기준 시간 | |
| `heartbeat.interval.ms` | `3000` | 기본값 | Consumer → 브로커 생존 신호 주기 (session.timeout의 1/3 권장) | |
| `fetch.min.bytes` | `1` | 기본값 | 브로커가 응답하기 위한 최소 데이터 크기 | |
| `fetch.max.wait.ms` | `500` | 기본값 | fetch.min.bytes 충족 전 최대 대기 시간 | |
| `isolation.level` | `read_uncommitted` | 기본값 | 트랜잭션 메시지 읽기 수준 | |

★ 표시가 테스트해볼 만한 옵션.

### 옵션 테스트 결과

raw Kafka client로 옵션을 격리해 동작을 관찰 (`ConsumerOptionsExperimentTest`, Testcontainer `apache/kafka:3.8.1`). 우리 `@KafkaListener`는 default factory 위에서 같은 의미로 동작한다.

#### auto.offset.reset (earliest vs latest)

새 그룹(커밋된 offset 없음)이 토픽을 처음 구독할 때 시작 위치.

| 설정 | 시나리오 | 결과 |
|---|---|---|
| `earliest` | 메시지 3건 발행 **후** 새 그룹 구독 | 3건 모두 읽음 (m1, m2, m3) |
| `latest` | 2건 발행 후 구독·할당 → 1건 추가 발행 | 추가분 1건만 읽음 (이전 2건 skip) |

→ **통합 테스트가 `earliest`를 쓰는 이유**: 테스트는 발행 후 Consumer가 붙으므로 `latest`면 메시지를 놓친다. 운영 default는 `latest`(과거 폭주분 재처리 방지)지만, 우리 Consumer는 `kafka.yml`에서 명시적으로 설정해 의도를 고정한다.

#### enable.auto.commit (true vs false)

poll만 하고 **처리 전에 죽는** 상황(crash before processing)을 모사한 뒤, 같은 그룹의 다음 Consumer가 그 메시지를 다시 받는지 관찰.

| 설정 | crash 후 재구독 시 | 의미 |
|---|---|---|
| `true` | 못 받음 (0건) | offset이 (poll/close 시점에) 자동 커밋돼 **유실** |
| `false` (manual) | 다시 받음 (1건) | ack 안 했으니 미커밋 → **재시도** |

→ at-least-once를 보장하려면 `false` + manual Ack가 필수다. 우리 설정(`enable.auto.commit=false`, `ack-mode=manual`)이 이 동작을 보장한다. `true`였다면 처리 실패가 곧 메시지 유실이다.

#### max.poll.interval.ms (리밸런싱)

`max.poll.interval.ms=2000`으로 짧게 두고, poll 후 4초간 처리(sleep)해서 한도를 초과시킴.

기대: 한도 초과 시 Consumer가 그룹에서 추방.
실제: 이후 `commitSync()`가 **`CommitFailedException`**으로 실패 — 이미 그룹에서 빠져 파티션 소유권을 잃었기 때문. 커밋이 실패하면 offset이 안 올라가므로 그 메시지는 리밸런싱 후 **재처리**된다.

→ 처리 시간이 긴 작업은 `max.poll.interval.ms`를 늘리거나 `max.poll.records`를 줄여야 한다. 안 그러면 "느린 처리 → 추방 → 재처리 → 또 느림"의 악순환과 중복 처리가 생긴다. (우리 `KafkaConfig.BATCH_LISTENER`는 2분으로 늘려둠.)

#### group.id (fan-out vs 분담)

2 partition 토픽에 양쪽 1건씩 발행.

| 구성 | 결과 |
|---|---|
| **다른 그룹** 2개가 같은 토픽 구독 | 각 그룹이 **전부**(2건) 수신 — fan-out |
| **같은 그룹** Consumer 2개 | partition을 나눠 가짐 — 각자 1건씩 (합치면 전체) |

→ `like-aggregator`와 `sales-aggregator`가 다른 그룹이라 서로 영향 없이 각자 집계한다(설령 같은 토픽이어도 fan-out). 같은 그룹 안에서 인스턴스를 늘리면 partition을 분담해 처리량이 오른다(최대 partition 수까지). **관찰된 레이스**: 두 Consumer가 동시에 안정적으로 조인하기 전, 먼저 조인한 쪽이 두 partition을 잠깐 다 가질 수 있다 → 테스트는 1:1 할당이 안정된 뒤 발행하도록 했다.

---

## 공통 — Outbox 설계

### 테이블 구조

```sql
outbox_events (id, aggregate_id, topic, event_type, payload JSON, created_at, published_at)
event_handled (event_id PK, handled_at)  -- Consumer 멱등 처리용
```

### Outbox 사용 시 주의사항

| 문제 | 원인 | 해결 |
|---|---|---|
| 최대 N초 지연 | Poller 실행 주기만큼 늦게 전달됨 | Eventual Consistency 허용 (집계 특성상 무방) |
| Poller 중단 시 이벤트 누적 | outbox_events 테이블이 계속 쌓임 | 모니터링 + 알람 |
| 중복 발행 | Kafka 발행 후 `published_at` 업데이트 전 크래시 시 재발행 | Consumer `event_handled` 멱등 처리로 흡수 |
| 다중 인스턴스 중복 처리 | 서버마다 Poller가 같은 이벤트를 동시에 가져감 | `SELECT ... SKIP LOCKED` 또는 분산 락 |

> Outbox는 **유실 방지(At Least Once)** 는 보장하지만 **정확히 한 번(Exactly Once)** 은 보장하지 않는다.
> 중복 처리 방어는 Consumer 책임.

---

## 진행 현황

| 도메인 | ApplicationEvent | Outbox Producer | Consumer |
|---|---|---|---|
| 좋아요 | ✅ 완료 | ✅ 완료 | ✅ 완료 (Testcontainers 검증) |
| 결제 | ✅ 완료 | ✅ 완료 | ✅ 완료 (Testcontainers 검증) |
| 쿠폰 | — | — | ⬜ 미구현 |
