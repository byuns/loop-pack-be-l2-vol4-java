# 08. Event & Kafka Pipeline

## 판단 기준

> "이게 실패하면 사용자 요청 자체가 실패해야 하나?"
> - Yes → 같은 TX
> - No, 유실 허용 → `@TransactionalEventListener(AFTER_COMMIT)`
> - No, 유실 불가 (다른 시스템) → Outbox + Kafka

---

## Step 1 — ApplicationEvent

| 위치 | 현재 문제 | 분리할 로직 |
|---|---|---|
| `LikeFacade.addLike()` | `incrementLikeCount`가 같은 TX → 집계 실패 시 좋아요 롤백 | `LikeAddedEvent` 발행, AFTER_COMMIT 리스너에서 처리 |
| `LikeFacade.cancelLike()` | 동일 | `LikeCancelledEvent` |
| `OrderFacade.createOrder()` | 행동 로깅 없음 | `UserActionEvent` |
| `PaymentFacade.applyPgResult()` | 알림 없음 | `PaymentConfirmedEvent` / `PaymentFailedEvent` |

**생성 파일**
- `like/domain/event/LikeAddedEvent.java`, `LikeCancelledEvent.java`
- `like/application/LikeEventListener.java`
- `support/event/UserActionEvent.java`, `UserActionEventListener.java`
- `payment/domain/event/PaymentConfirmedEvent.java`, `PaymentFailedEvent.java`
- `payment/application/PaymentEventListener.java`

---

## Step 2 — Outbox + Kafka

**토픽**

| 토픽 | Partition Key | 이벤트 | Consumer |
|---|---|---|---|
| `catalog-events` | `productId` | LIKE_ADDED, LIKE_CANCELLED, PRODUCT_VIEWED | `product_metrics` like_count / view_count upsert |
| `order-events` | `orderId` | ORDER_CONFIRMED | `product_metrics` sales_count upsert |

**Outbox 테이블**
```sql
outbox_events (id, aggregate_id, topic, event_type, payload JSON, created_at, published_at)
event_handled (event_id PK, handled_at)  -- Consumer 멱등 처리용
```

**수정 파일**
- `LikeFacade` — Step 1 리스너 대신 outbox INSERT로 교체
- `PaymentFacade.applyPgResult()` — SUCCESS 시 `order-events` outbox INSERT 추가

**신규 파일**
- `support/outbox/OutboxEvent.java`, `OutboxRepository.java`, `OutboxPoller.java`
- `collector/application/MetricsConsumer.java` — manual Ack + `event_handled` 멱등 처리

**Producer 설정:** `acks=all`, `enable-idempotence=true`

**OutboxPoller 트레이드오프**

| 문제 | 원인 | 해결 |
|---|---|---|
| 최대 N초 지연 | Poller 실행 주기만큼 늦게 전달됨 | Eventual Consistency 허용 (집계 특성상 무방) |
| Poller 중단 시 이벤트 누적 | outbox_events 테이블이 계속 쌓임 | 모니터링 + 알람 |
| 중복 발행 | Kafka 발행 후 `published_at` 업데이트 전 크래시 시 재발행 | Consumer `event_handled` 멱등 처리로 흡수 |
| 다중 인스턴스 중복 처리 | 서버마다 Poller가 같은 이벤트를 동시에 가져감 | `SELECT ... SKIP LOCKED` 또는 분산 락 |

> Outbox는 **유실 방지(At Least Once)** 는 보장하지만 **정확히 한 번(Exactly Once)** 은 보장하지 않는다.
> 중복 처리 방어는 Consumer 책임.

---

## Step 3 — 선착순 쿠폰 (Kafka 비동기)

**현재 문제:** `CouponFacade.issueCoupon()` 동기 TX → 동시 요청 시 DB 락 경합, 수량 제한 없음

**변경 흐름**
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

## 구현 순서

- [ ] Step 1: 이벤트 클래스 + 리스너 작성, 각 Facade 수정
- [ ] Step 2: outbox 테이블 생성, OutboxPoller, MetricsConsumer
- [ ] Step 3: coupon_issue_requests 테이블, CouponIssueConsumer, 결과 조회 API
