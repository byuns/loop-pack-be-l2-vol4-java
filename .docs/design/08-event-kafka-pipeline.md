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

### Consumer — 미구현

- `commerce-collector` 모듈에서 `catalog-events` 구독
- `product_metrics` 테이블에 like_count upsert
- manual Ack + `event_handled` 테이블로 멱등 처리

---

## 결제 (ORDER_CONFIRMED)

### ApplicationEvent — 완료

`PaymentFacade.applyPgResult()` 에서 결제 결과에 따라 이벤트 발행.

- SUCCESS → `PaymentConfirmedEvent(orderId, finalAmount)` — 알림 목적
- FAIL → `PaymentFailedEvent(orderId)`

---

### Outbox + Kafka Producer — 미구현

`PaymentFacade.applyPgResult()` SUCCESS 분기에 `order-events` outbox INSERT 추가.

`PaymentConfirmedEvent`(알림용)와 Outbox INSERT(집계용)는 목적이 달라 공존한다.

**토픽:** `order-events` / Partition Key: `orderId`

---

### Consumer — 미구현

- `commerce-collector` 모듈에서 `order-events` 구독
- `product_metrics` 테이블에 sales_count upsert
- manual Ack + `event_handled` 테이블로 멱등 처리

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

**Test A — 정상 상태 지연 측정 (단일 broker 한계로 비교 불가)**

`outbox INSERT → published_at` delta 측정했으나 Poller 5초 폴링 주기가 변동을 다 흡수해서 acks 차이가 묻힘. 단일 broker에선 acks=1과 acks=all도 사실상 동일 (기다릴 follower가 없음). **다중 broker 환경에서 재측정 예정.**

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
| 좋아요 | ✅ 완료 | ✅ 완료 | ⬜ 미구현 |
| 결제 | ✅ 완료 | ⬜ 미구현 | ⬜ 미구현 |
| 쿠폰 | — | — | ⬜ 미구현 |
