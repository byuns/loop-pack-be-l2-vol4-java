# 08. Event & Kafka Pipeline

## 판단 기준

### 왜 분리하는가

**트랜잭션 경계는 기술이 아니라 비즈니스 불변식이 결정한다.**

트랜잭션을 어디서 끊을지는 "구현이 편한가"가 아니라 "이 두 가지가 항상 동시에 일관해야 하는 비즈니스 규칙이 있는가?"로 판단한다.

```
주문 생성 + 재고 예약
→ "재고 없는데 주문이 생성되면 안 된다"는 불변식 존재 → 같은 TX

주문 생성 + 행동 로깅
→ "로깅 실패 시 주문도 실패해야 한다"는 불변식 없음 → 같은 TX일 이유 없음
```

**Event는 "과거 사실"이다, Command가 아니다.**

```
Command: UpdateLikeCount(productId, +1)  ← "집계 업데이트를 해줘"
→ 발행자가 구독자에게 지시. 실패 시 보상 책임도 발행자에게 있음.

Event: LikeAdded(productId)  ← "좋아요가 저장됐다"
→ 이미 일어난 사실. 구독자는 그 사실을 바탕으로 각자 할 일만 함.
→ 구독자가 몇 개인지, 뭘 하는지 발행자는 모른다.
```

Event로 설계하면 `LikeFacade`는 구독자(집계, 알림, 추천...)가 무엇을 하는지 모른 채 이벤트만 남긴다. 나중에 구독자가 늘어나도 발행자 코드는 바뀌지 않는다.

---

### 분리할 것인가

**첫 번째 컷: "이게 실패하면 사용자 요청 자체가 실패해야 하나?"**

- **Yes** → 이벤트 분리 X, 같은 TX 안에 둔다.
- **No** → 분리 후보. 다음 질문으로 넘어간다.

**분리하면 안 될 때**

이벤트가 항상 답은 아니다.

- **결과를 즉시 응답에 담아야 할 때** — 주문 생성 응답에 `finalAmount`를 바로 담아야 한다면 비동기 불가.
- **이벤트 체인이 깊어질 때** — `주문 → [이벤트] → 결제 → [이벤트] → 재고 → [이벤트] → 배송`처럼 이어지면 어느 단계에서 실패했는지 추적하기 어렵고 보상 트랜잭션이 복잡해진다.
- **구독자가 늘어날 여지 없는 단순 처리** — 이벤트로 분리하면 복잡도만 올라간다.

---

### 분리한다면 어떤 방식으로

두 가지 질문으로 구현 방식을 결정한다.

> 1. 유실돼도 괜찮나?
> 2. 다른 시스템이 알아야 하나?

```
유실 허용? × 다른 시스템?
├── Yes × No  → @TransactionalEventListener(AFTER_COMMIT)
├── Yes × Yes → Kafka 직접 (fire-and-forget)
└── No  × Yes → Outbox + Kafka
```

| 부수효과 | 요청 실패시켜야? | 유실 허용? | 다른 시스템? | 선택 |
|---|---|---|---|---|
| likeCount 집계 | No | No | Yes | Outbox + Kafka |
| 판매량 집계 | No | No | Yes | Outbox + Kafka |
| 결제 알림 | No | Yes | No | AFTER_COMMIT |
| 행동 로깅 | No | Yes | No | AFTER_COMMIT |
| **조회수 집계** | No | **Yes** | **Yes** | **Kafka 직접(fire-and-forget)** |

"유실 허용"의 판단 기준: 단순히 "한 건쯤 빠져도 괜찮다"가 아니라 **"누락이 영구적으로 굳는가"** 를 본다. `likeCount`는 한 건 유실되면 DB에 틀린 숫자가 남는다 — 유실 불가. `조회수`는 통계라 한두 건 빠져도 전체 추세에 영향 없다 — 유실 허용.

---

### 분리 후 고려사항

**Eventual Consistency — 읽기 시점 문제**

이벤트로 분리하는 순간 "언제 어디서 읽느냐"가 설계 문제가 된다.

```
좋아요 누름 → 200 OK
화면 갱신   → GET /products/{id} → likeCount = ?
                                     아직 집계 안 됐을 수 있음
```

선택지:
- API 응답에 `likeCount` 포함하지 않고 별도 집계 API 분리
- 클라이언트가 좋아요 성공 시 로컬 상태로 +1 반영 (낙관적 UI)
- 읽기는 DB 직접, 쓰기만 이벤트 경유 (CQRS 방향)

이벤트 분리는 코드 분리로 끝나는 게 아니라, "언제 정확한 값을 보장하는가"까지 설계에 영향을 준다.

---

## 좋아요 (LIKE_ADDED / LIKE_CANCELLED)

좋아요 도메인이 이벤트 파이프라인의 첫 사례. 여기서 만든 Outbox + Consumer 패턴이 이후 결제, 조회수에 적용된다.

### ApplicationEvent → Outbox로 교체한 이유

처음엔 likeCount를 ApplicationEvent로 업데이트했다. 문제는 이벤트가 **메모리에만** 존재한다는 것 — TX 커밋 직후 서버가 죽으면 이벤트가 사라지고 likeCount 업데이트는 영영 실행되지 않는다.

→ 좋아요 저장과 **같은 TX 안에서** `outbox_events`에 INSERT하는 방식으로 교체. TX가 커밋되면 이벤트도 DB에 보장된다. Poller가 5초마다 미발행 이벤트를 Kafka로 전달한다.

| | ApplicationEvent | Outbox + Kafka |
|---|---|---|
| 서버 크래시 시 | 이벤트 유실 | DB에 남아 복구 가능 |
| likeCount 반영 속도 | 커밋 직후 즉시 | Poller 주기만큼 지연 |
| 구현 복잡도 | 낮음 | 높음 |

**토픽:** `catalog-events` / Partition Key: `productId`

### @Async가 필요한 이유

AFTER_COMMIT 리스너에서 DB를 써야 할 때(REQUIRES_NEW) 새 커넥션이 필요하다. 그런데 AFTER_COMMIT이 실행되는 시점에 원본 커넥션이 아직 반환되지 않은 상태다.

동시 요청이 많으면 "새 커넥션을 기다리는 스레드들"이 쌓이면서 커넥션 풀이 바닥난다.

`@Async`를 붙이면 별도 스레드에서 실행되어, 원본 스레드가 커넥션을 먼저 반환하고 그 뒤에 새 커넥션을 가져오게 된다.

> 현재 AFTER_COMMIT 리스너들은 로깅만 해서 @Async 없이도 괜찮다. 나중에 리스너에 DB 쓰기를 추가하면 이 이슈가 그대로 발생한다.

### Consumer 처리 흐름

```
1. catalog-events에 LIKE_ADDED 도착
2. Consumer 수신 → eventId = "catalog-events:" + partition + ":" + offset
3. @Transactional 내부:
   a. event_handled에 eventId 이미 있으면 skip + ack (멱등)
   b. event_handled INSERT
   c. product_metrics.like_count +1 (없으면 새로 생성)
4. ack.acknowledge() → offset commit
```

**Consumer group:** `like-aggregator` (인스턴스를 늘리면 partition 분담, 최대 3개까지)

시스템 실패는 전역 DLT 정책(공통 — 실패 처리 참조)이 3회 재시도 후 `catalog-events.DLT`로 격리한다.

### 알려진 한계

- **중복 발행 감지 한계**: Poller가 발행 성공 후 `published_at` 갱신 전 크래시하면, 재시작 시 같은 이벤트가 새 (partition, offset)으로 재발행된다. eventId가 좌표 기반이라 이 경우 중복 감지 불가. 해결책: outbox.id를 Kafka header로 넘겨 Consumer가 eventId로 사용.

---

## 결제 (ORDER_CONFIRMED)

### ApplicationEvent

`PaymentFacade.applyPgResult()`에서 결제 결과에 따라 이벤트 발행.

- SUCCESS → `PaymentConfirmedEvent(orderId, finalAmount)` — 알림용 (AFTER_COMMIT)
- FAIL → `PaymentFailedEvent(orderId)` — 알림용 (AFTER_COMMIT)

알림(AFTER_COMMIT)과 집계(Outbox INSERT)가 같은 SUCCESS 분기에 공존한다 — 목적이 달라 둘 다 필요하다.

### Outbox + Kafka Producer

**토픽:** `order-events` / Partition Key: `orderId`

payload에 items 배열을 포함해 Consumer가 추가 API 호출 없이 집계할 수 있게 했다(self-contained):

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

### Consumer

`order-events` 구독 → items를 순회하며 `product_metrics.sales_count`를 quantity만큼 증가.

**처리 흐름**

```
1. order-events에 ORDER_CONFIRMED 도착
2. Consumer 수신 → eventId = "order:" + orderId  (비즈니스 키)
3. @Transactional 내부:
   a. event_handled에 eventId 이미 있으면 skip (멱등)
   b. event_handled INSERT
   c. items 순회: incrementSalesCount(productId, quantity) ← 원자 UPSERT
4. ack.acknowledge() → offset commit
```

**Consumer group:** `sales-aggregator`

#### 좋아요와 다른 두 가지

**1. 원자 UPSERT — 동시성 문제 해결**

좋아요는 `productId`로 파티셔닝되어 같은 상품 이벤트가 항상 같은 파티션에서 순서대로 처리된다. 동시에 같은 행을 건드리는 Consumer가 없다.

결제는 `orderId`로 파티셔닝되는데 집계 단위는 `productId`다. 상품 A가 들어간 주문 10개가 동시에 처리되면 Consumer 10개가 `product_metrics`의 같은 행을 동시에 업데이트한다.

이때 `find → count + delta → save` 패턴은 lost update가 발생한다. 두 Consumer가 동시에 `sales_count = 5`를 읽고 각각 +2, +3을 쓰면 마지막에 쓴 값으로 덮어써진다. 실제로는 10이어야 하는데.

→ `INSERT ... ON DUPLICATE KEY UPDATE sales_count = sales_count + :delta` 원자 UPSERT로 교체. DB가 읽기-수정-쓰기를 한 번의 쿼리로 처리해 동시 업데이트에도 안전하다.

**2. 비즈니스 키 멱등**

좋아요는 같은 상품에 LIKE_ADDED가 여러 번 올 수 있어서 Kafka 좌표(`topic:partition:offset`)를 멱등 키로 쓴다.

결제는 주문당 ORDER_CONFIRMED가 평생 1번뿐이라 `"order:" + orderId`를 멱등 키로 쓴다. Outbox 재발행이나 리밸런싱으로 Kafka 좌표가 바뀌어도 orderId가 같으면 중복을 정확히 잡는다. 좋아요의 "Producer 재시작 후 중복" 한계를 구조적으로 피한 것.

### 알려진 한계

- 취소/환불 시 `sales_count` 감소 경로 없음 → 현재는 "확정 누적"이며 "순매출"이 아님

---

## 조회수 (PRODUCT_VIEWED)

상품 조회는 **유실 허용 + 다른 시스템 전파**라 Outbox 없이 Kafka로 직접 발행한다. 같은 이벤트를 두 리스너가 다른 목적으로 구독한다.

### ApplicationEvent + Kafka 직접 발행

`ProductFacade.getProduct`(readOnly TX)에서 `ProductViewedEvent(productId, occurredAt)` 발행.

- `UserActionLogListener` — 행동 로깅. `OrderCreatedEvent`도 함께 받아 한곳에서 통합 로깅.
- `ProductViewEventPublisher` — `@Async` + AFTER_COMMIT으로 `catalog-events`에 직접 send. 발행 실패해도 흘려보낸다(`.get()` 안 함).

> readOnly TX 안에서 이벤트를 발행하는 이유: TX 밖에서 publish하면 `@TransactionalEventListener`가 발화하지 않는다. readOnly TX도 커밋되므로 AFTER_COMMIT이 정상 동작한다.

**토픽:** `catalog-events` / Partition Key: `productId` (좋아요와 동일 토픽·키)

### Consumer

`view-aggregator` 그룹으로 `catalog-events` 구독. `PRODUCT_VIEWED`만 처리하고 나머지(LIKE_*)는 흘려보낸다. `like-aggregator`와 다른 그룹이라 같은 토픽에서 각자 독립적으로 메시지를 받는다(fan-out).

### occurredAt 가드 — 최신 이벤트만 반영

`applyView`는 이전에 반영한 occurredAt보다 오래된 이벤트를 무시한다.

```java
if (lastViewEventAt != null && occurredAt < lastViewEventAt) return;  // stale → skip
viewCount++;
lastViewEventAt = occurredAt;
```

이 가드가 안전한 이유: `catalog-events`가 `productId` 키로 파티셔닝되어 **같은 상품 이벤트가 단일 파티션에서 순서대로** 처리된다. 정상 조회 이벤트는 항상 이전 것보다 occurredAt이 크다. 가드는 Outbox 재발행 같은 진짜 stale 이벤트만 떨군다.

반대로 판매량(`order-events`)은 `orderId` 키라 같은 상품이 여러 파티션에 흩어져 순서가 보장되지 않는다 → occurredAt 가드 대신 orderId 비즈니스 키 멱등을 쓴다. **파티션 키 설계가 멱등/순서 전략을 결정한다.**

---

## 쿠폰 (선착순 발급)

### 왜 Kafka인가

기존 `issueCoupon()`은 동기 TX 안에서 발급까지 완료한다. 선착순 100장에 1만 명이 동시 요청하면 DB row lock이 직렬화 지점이 되고 대부분의 요청이 lock wait로 쌓인다. DB가 병목이다.

Kafka `couponId` 파티션 키를 쓰면 같은 쿠폰에 대한 요청이 단일 파티션에서 직렬로 처리된다. API는 Outbox INSERT만 하고 202를 돌려주므로 응답이 빠르고, Consumer가 순서대로 처리하므로 DB 경합이 없다.

단, "발급됐나요?"는 즉시 알 수 없다. 결과를 polling으로 확인해야 한다. 이것이 이 방식의 핵심 트레이드오프다.

### API 흐름

```
POST /api/v1/coupons/{couponId}/issue
  → 만료 체크 + 중복 요청 체크
  → coupon_issue_requests(PENDING) INSERT
  → outbox_events INSERT
  → 202 Accepted + { requestId, status: PENDING }

GET /api/v1/coupons/issue-requests/{requestId}
  → { requestId, couponId, userId, status: PENDING | ISSUED | FAILED }
```

### 데이터 모델 — 두 테이블 분리

**`coupon_issue_requests` (신규)**

```sql
coupon_issue_requests (
  id PK,
  coupon_id, user_id,
  status ENUM('PENDING', 'ISSUED', 'FAILED'),
  UNIQUE (coupon_id, user_id)
)
```

UNIQUE 제약이 두 가지 일을 한다:
1. **DB 수준 중복 차단** — 같은 (couponId, userId) 조합의 두 번째 INSERT를 막는다.
2. **상태의 단일 진실점** — 한 유저가 한 쿠폰에 대해 갖는 요청이 반드시 하나뿐임을 보장한다.

**`coupon_issues`와 합치지 않은 이유**

| | `coupon_issue_requests` | `coupon_issues` |
|---|---|---|
| 소유자 | 요청(비동기 흐름) | 발급 사실(비즈니스 완료) |
| 주체 | API가 쓰고 Consumer가 업데이트 | Consumer만 씀 |
| 기존 코드 영향 | 신규 | 주문, 결제 등이 이미 참조 중 |

`coupon_issues`는 "쿠폰이 발급됐다"는 비즈니스 사실이고, `coupon_issue_requests`는 "비동기 발급 요청의 진행 상태"다. 의미가 달라 합치면 기존 코드가 비동기 흐름을 모르는 채로 `coupon_issues`를 읽다가 PENDING 상태를 발급된 것으로 오인할 수 있다.

### Outbox payload

```json
{
  "requestId": 123,
  "couponId": 456,
  "userId": 789,
  "eventType": "COUPON_ISSUE_REQUESTED"
}
```

**토픽:** `coupon-issue-requests` / Partition Key: `couponId`

`couponId` 파티션 키 → 같은 쿠폰 요청이 단일 파티션에서 순서대로 처리 → Consumer 직렬화 보장.

### Consumer 처리 흐름

```
1. Kafka 메시지 수신 (requestId, couponId, userId)
2. @Transactional 내부:
   a. coupon_issue_requests.status == PENDING?
      → No (이미 ISSUED or FAILED) : return  ← 멱등성
   b. coupon_issues에 (couponId, userId) 이미 있나?
      → Yes : status = FAILED, save, return  ← 중복 차단
   c. UPDATE coupons SET remaining_count = remaining_count - 1
      WHERE id = couponId AND remaining_count > 0
      → 0건 업데이트 : status = FAILED, save, return  ← 수량 소진
   d. coupon_issues INSERT (AVAILABLE)
   e. coupon_issue_requests.status = ISSUED, save
3. ack.acknowledge()
```

모든 상태 전이가 하나의 `@Transactional` 안에 있다. 중간에 실패하면 전부 롤백되고 status는 PENDING 그대로 → Kafka가 재시도한다.

### 동시성과 멱등 설계

**1. 수량 제한 — DB 원자 감소**

```sql
UPDATE coupons
SET remaining_count = remaining_count - 1, updated_at = NOW()
WHERE id = :couponId AND remaining_count > 0
```

반환된 업데이트 행 수가 0이면 수량 소진. DB가 읽기-수정-쓰기를 단일 쿼리로 처리해 동시 업데이트에도 lost update가 없다.

| 방식 | 설명 | 문제 |
|---|---|---|
| A: 파티션 직렬화만 믿기 | Consumer가 단일 파티션에서 처리하니 동시성 없음 | 리밸런싱 순간에 두 Consumer가 잠깐 같은 파티션에서 처리할 수 있는 엣지 케이스 존재 |
| **B: DB 원자 감소 (현재)** | SQL 한 줄이 수량 소진 여부를 결정 | 없음. DB 레벨 보장 |
| C: Redis 카운터 | `DECR coupon:{id}:count` 원자 | DB와 Redis 간 정합성 관리 필요. 장애 시 두 값이 달라질 수 있음 |

파티션 직렬화로도 거의 충분하지만, **"거의"를 믿지 않는다**는 것이 B 선택의 이유다. DB 레벨이 최후 방어선이다.

**2. 멱등 키**

| 키 | 방식 | 이유 |
|---|---|---|
| Kafka 메시지 중복 | `coupon_issue_requests.status` 체크 | requestId 기반. PENDING이 아니면 이미 처리됨 |
| 중복 발급 요청 | `coupon_issues` EXISTS 체크 | Consumer에서 두 번째 안전망 |

`event_handled` 테이블을 쓰지 않은 이유: 이 파이프라인에서 멱등 키는 `requestId`이고, 요청 1개당 레코드 1개인 `coupon_issue_requests`가 이미 상태 기계 역할을 한다. 별도 테이블을 추가하면 한 요청에 두 테이블을 동시에 보고해야 해서 오히려 복잡해진다.

**3. 이중 방어 — API 사전 차단 + Consumer 안전망**

```
API (requestCouponIssue)         Consumer (handleCouponIssueRequest)
       ↓                                    ↓
  중복 요청 체크                        중복 발급 체크
  (coupon_issue_requests 조회)          (coupon_issues 조회)
       ↓                                    ↓
  만료 체크                          remaining_count 원자 감소
       ↓
  Outbox INSERT
```

API 수준 체크는 "이미 요청을 넣은 사람이 중복 요청 보내는 것"을 막는 빠른 방어선이다. Kafka 메시지가 쌓이기 전에 걸러낸다. Consumer 수준 체크는 Kafka 재시도나 기타 이유로 같은 메시지가 두 번 처리될 때의 안전망이다.

**4. 처리 순서 — 왜 이 순서인가**

```
중복 체크 → remaining_count 감소 → coupon_issues INSERT → status = ISSUED
```

순서를 바꾸면 어떻게 될까?

- `감소 → 중복 체크`: 중복인데 수량을 깎고 나서 중복 판정. 수량 누수.
- `감소 → INSERT → 중복 체크`: INSERT가 UNIQUE 위반으로 터지면 감소된 수량을 돌려받지 못함. 수량 누수.

중복 체크가 항상 가장 먼저여야 수량 누수가 없다.

### 실패 처리 — 선착순 특수 사항

전역 DLT 정책(공통 — 실패 처리 참조)이 기본이고, 선착순 도메인의 특수 사항만 여기에 남긴다.

| 종류 | 상황 | 처리 |
|---|---|---|
| 비즈니스 실패 | 수량 소진, 중복 요청 | status = FAILED, ack → Kafka에서 메시지 제거. **DLT로 안 감** |
| 시스템 실패 | DB 다운, 네트워크 오류 | 예외 전파 → 전역 정책 (1초 3회 재시도 후 DLT) |

**비즈니스 실패를 FAILED로 확정하고 ack하는 이유:** 선착순이기 때문이다. 수량이 소진됐는데 재시도해봐야 결과가 바뀌지 않는다. FAILED를 재시도 불가 상태로 확정하고 사용자가 `GET /issue-requests/{requestId}`로 확인하게 한다. PENDING/ISSUED/FAILED 모두 API 사전 차단 대상에 포함한 이유도 같다 — FAILED인 사람이 다시 요청해도 이미 선착순에서 탈락한 것이다.

**선착순 시나리오와 FixedBackOff의 궁합:** A의 재시도(총 3초)가 뒤따르는 B, C, D를 밀지만, 지수 백오프의 십수 초 지연보다 훨씬 짧아 대기 유저 체감 지연이 작다. 3초 후 DLT로 옮겨 파티션을 해방하는 게 선착순 도메인에 맞다.

---

## 공통 — Kafka Producer 옵션

| 옵션 | 현재 설정 | 역할 |
|---|---|---|
| `acks` | `all` | 브로커가 '성공'으로 응답하는 기준. `1`=리더 저장 시 OK, `all`=ISR 전원 저장 시 OK |
| `enable.idempotence` | `true` | 재시도 시 중복 발행 방지. Kafka가 sequence number로 중복 감지 |
| `linger.ms` | `0` (기본) | 메시지를 N ms 모아서 배치 전송. `0`=즉시 전송 |
| `delivery.timeout.ms` | `120000` (기본) | 재시도 포함 최종 발행 타임아웃. retries의 실질 상한 |

> **ISR(In-Sync Replicas)**: 리더와 동기화된 follower 집합. `acks=all`은 이 집합 전원이 저장을 확인해야 성공. 단일 broker에서는 `acks=1`과 실질 동일하다.

> **세 옵션 묶음** — `acks=all` + `enable.idempotence=true` + `max.in.flight=5`는 Kafka 권장 세트. 하나만 바꾸면 보장이 깨진다. `acks`를 낮추면 `idempotence`가 조용히 꺼진다.

### 옵션 테스트 결과

**acks=0 + idempotence=true (의도적 모순)**

- 기대: ConfigException으로 시작 실패
- 실제: 에러 없이 시작, ProducerConfig 로그에 `enable.idempotence = false`로 찍힘

Kafka 3.0+에서 `acks`와 충돌하면 throw 대신 silent disable로 처리한다. `acks=all`도 함께 명시해야 보장된다.

**Kafka down 시 acks=0 vs acks=all**

- 기대: acks=0은 응답을 안 기다리니 발행 성공으로 처리될 것
- 실제: 둘 다 `published_at=NULL`, send 실패

`acks`는 TCP 연결이 잡힌 후 "응답 대기" 옵션이다. broker가 죽으면 TCP 연결 자체가 실패해 acks 값과 무관하게 send()가 깨진다. 이때 outbox에 쌓인 이벤트는 broker 복구 후 Poller가 자동으로 재시도한다.

**linger.ms 비교 (20건)**

| 설정 | 20건 처리 시간 |
|---|---|
| `linger.ms=0` (기본) | 320 ms |
| `linger.ms=100` | 2533 ms (~8배 느림) |

Poller가 `for (event) { send().get() }` 동기 패턴이라 매번 1건씩 전송한다. linger.ms를 높이면 배치를 기다리는 시간만 추가된다. 여러 send()가 동시에 일어나는 환경에서만 linger.ms가 의미 있다. **현재는 0 유지**.

**num.partitions — partition key와 순서 보장**

`catalog-events`를 1→3 partition으로 늘리고 productId 1~5에 각 2건씩 발행:

| Partition | productId |
|---|---|
| 0 | 1, 5 |
| 1 | 4 |
| 2 | 2, 3 |

- 같은 key는 항상 같은 partition → 같은 productId 이벤트 순서 보장
- Consumer 인스턴스를 늘리면 partition을 나눠 처리 → 처리량 증가 (최대 partition 수까지)
- 해시 분산은 완벽 균등이 아님 (key 수가 적으면 쏠릴 수 있음)

---

## 공통 — Kafka Broker 옵션 (다중 브로커 실험)

단일 broker에서 `acks=all`은 기다릴 follower가 없어 `acks=1`과 동일하다. 3-broker 클러스터(전용 controller 1 + broker 3)로 실제 동작 검증.

> combined(`broker,controller`) 모드로 3노드를 두면 broker 2대를 죽일 때 controller 쿼럼도 함께 깨진다. 전용 controller를 분리하면 broker를 죽여도 쿼럼이 유지돼 `min.insync.replicas` 발동을 깔끔히 관찰할 수 있다.

### acks=1 vs acks=all 지연

| acks | 처리량 | 평균 지연 |
|---|---|---|
| `1` (리더만) | ~26,500 rec/s | ~219 ms |
| `all` (ISR 전원) | ~18,900 rec/s | ~467 ms |

`acks=all`은 follower ack 대기로 처리량 30% 감소, 지연 2배. 내구성은 공짜가 아니다. 그래서 유실 허용인 조회수는 Outbox 없이 직접 발행하고, 유실 불가인 likeCount/salesCount는 이 비용을 감수한다.

### min.insync.replicas

`acks=all`과 짝을 이루는 broker 옵션. ISR이 이 숫자 미만이면 발행 자체를 거부한다.

| 상태 | ISR | acks=all 발행 |
|---|---|---|
| 전체 정상 | 3 | 성공 |
| broker 1대 down | 2 | **성공** (2 ≥ min 2) |
| broker 2대 down | 1 | **거부** — `NotEnoughReplicasException` |

리더가 살아있어도 ISR이 min.insync.replicas 미만이면 쓰기를 거부한다. RF=3 / min.insync=2는 "1대 장애는 버티고, 2대 장애면 차라리 멈춘다"는 가용성↔내구성 균형점.

broker가 죽어 발행이 거부되는 동안 outbox에 `published_at=NULL`로 이벤트가 쌓인다. broker 복구 후 Poller가 자동으로 재시도해 유실 없이 처리된다 — Outbox가 ISR 부족까지 흡수한다.

---

## 공통 — Kafka Consumer 옵션

| 옵션 | 현재 설정 | 역할 |
|---|---|---|
| `group.id` | 설정 필요 | 같은 그룹: partition을 나눠 처리(분담). 다른 그룹: 같은 토픽을 각자 전부 수신(fan-out) |
| `auto.offset.reset` | 설정 필요 | 처음 구독 시 시작 위치. `earliest`=처음부터, `latest`=구독 이후 새 것만 |
| `enable.auto.commit` | `false` | `true`면 처리 전 크래시해도 처리된 것으로 기록 → 유실. at-least-once는 `false` + manual Ack 필수 |
| `max.poll.records` | `500` (기본) | poll() 한 번에 가져올 최대 메시지 수. 크면 처리량↑이지만 처리 시간이 길어져 추방 위험↑ |
| `max.poll.interval.ms` | `300000` (기본) | poll() 호출 간격 최대 허용 시간. 초과 시 그룹에서 추방 → 리밸런싱 |
| `session.timeout.ms` | `45000` (기본) | heartbeat 미수신 시 장애로 판단 → 리밸런싱 |

> **리밸런싱**: 그룹 내 Consumer가 추가/제거될 때 파티션 할당을 재조정. 이 동안 그룹 전체가 처리를 멈춘다(Stop-the-world).

> **장애 감지 두 갈래**: `session.timeout.ms`는 네트워크 단절·프로세스 크래시를 감지한다. `max.poll.interval.ms`는 처리 로직이 느려 poll을 제때 못 부를 때 감지한다. 처리가 느려서 생긴 문제라면 session.timeout을 늘려도 해결 안 되고 max.poll.interval.ms를 늘리거나 max.poll.records를 줄여야 한다.

### 옵션 테스트 결과

**auto.offset.reset**

| 설정 | 시나리오 | 결과 |
|---|---|---|
| `earliest` | 메시지 3건 발행 후 새 그룹 구독 | 3건 모두 읽음 |
| `latest` | 2건 발행 후 구독, 1건 추가 발행 | 추가분 1건만 읽음 |

통합 테스트에서 `earliest`를 쓰는 이유: 테스트는 발행 후 Consumer가 붙으므로 `latest`면 메시지를 놓친다.

**enable.auto.commit**

poll 후 처리 전 크래시 상황 시뮬레이션:

| 설정 | 재구독 시 |
|---|---|
| `true` | 못 받음 (offset 자동 커밋돼 유실) |
| `false` (manual) | 다시 받음 (ack 안 했으니 재시도) |

**max.poll.interval.ms**

`max.poll.interval.ms=2000`에서 4초 처리(sleep)로 초과시킴 → `commitSync()`가 `CommitFailedException`으로 실패. 이미 그룹에서 추방돼 파티션 소유권을 잃었기 때문. 커밋 실패 → offset 안 올라감 → 리밸런싱 후 재처리.

**group.id (fan-out vs 분담)**

2 partition 토픽에 각 1건 발행:

| 구성 | 결과 |
|---|---|
| 다른 그룹 2개 | 각 그룹이 전부(2건) 수신 — fan-out |
| 같은 그룹 Consumer 2개 | 각자 1건씩 (partition 분담) |

`like-aggregator`와 `view-aggregator`가 다른 그룹이라 같은 `catalog-events` 토픽에서 각자 독립적으로 집계한다.

---

## 공통 — Outbox 설계

### 테이블 구조

```sql
outbox_events (id, aggregate_id, topic, event_type, payload JSON, created_at, published_at)
event_handled (event_id PK, handled_at)  -- Consumer 멱등 처리용
```

`outbox_events`는 Producer 쪽, `event_handled`는 Consumer 쪽. 역할이 분리된다.

### 주의사항

| 문제 | 원인 | 대응 |
|---|---|---|
| 최대 N초 지연 | Poller 실행 주기 | 집계 특성상 허용 |
| Poller 중단 시 이벤트 누적 | outbox_events 계속 쌓임 | 모니터링 + 알람 |
| 중복 발행 | 발행 성공 후 published_at 갱신 전 크래시 | Consumer event_handled로 흡수 |
| 다중 인스턴스 중복 처리 | 서버마다 Poller가 동시에 가져감 | `SELECT ... SKIP LOCKED` 또는 분산 락 |

> Outbox는 **At Least Once**를 보장한다. **Exactly Once**는 보장하지 않는다. 중복 방어는 Consumer 책임.

---

## 공통 — 실패 처리 (DLT 정책)

### 커버리지

```
API/Producer         Broker              Consumer
    ↓                  ↓                    ↓
[발행 실패]        [브로커 장애]        [처리 실패]
    ↑                                      ↑
  Outbox 담당                            DLT 담당
```

| 지점 | 예시 | 잡히는 곳 | 결과 |
|---|---|---|---|
| **Producer → Broker 발행 실패** | 브로커 다운, ISR 부족 | Outbox 패턴 | `outbox_events.published_at = NULL`로 남고 Poller가 재시도. **DLT로 안 감** |
| **Consumer 처리 실패 (시스템)** | DB 다운, 파싱 실패, 코드 버그 | `DefaultErrorHandler + DeadLetterPublishingRecoverer` | 1초 간격 3회 재시도 후 `.DLT` 이관 |
| **Consumer 처리 실패 (비즈니스)** | 수량 소진, 중복 요청 | Service가 정상 반환 | ack. DLT로 안 감 |
| **Broker → Consumer 전달 실패** | Consumer가 브로커 못 잡음 | Kafka 클라이언트 내부 재시도 | 앱 코드는 관여 안 함 |

### 공통 설정

전역 DLT 팩토리 하나를 정의해 모든 컨슈머가 재사용한다.

```java
@Bean(DLT_LISTENER_FACTORY)
ConcurrentKafkaListenerContainerFactory<Object, Object> dltListenerContainerFactory(...) {
    factory.getContainerProperties().setAckMode(MANUAL);
    factory.setCommonErrorHandler(new DefaultErrorHandler(
        new DeadLetterPublishingRecoverer(kafkaTemplate),
        new FixedBackOff(1000L, 3L)
    ));
}
```

### DLT 토픽 (명시 선언)

`auto.create.topics.enable=false`이므로 각 DLT를 `NewTopic` 빈으로 선언:

| 원본 토픽 | DLT 토픽 | 사용 컨슈머 |
|---|---|---|
| `catalog-events` | `catalog-events.DLT` | Like + View (fan-out) |
| `order-events` | `order-events.DLT` | Payment |
| `coupon-issue-requests` | `coupon-issue-requests.DLT` | Coupon |

### FixedBackOff(1s, 3) 선택 이유

| 대안 | 문제 |
|---|---|
| ExponentialBackOff | 같은 파티션의 다음 메시지가 백오프 동안 막힌다(파티션 블로킹). 처리량 급감 |
| 무한 재시도 (기존 no-ack 방식) | 영속 실패 시 파티션이 영원히 잠긴다. lag 무한 누적 |
| **FixedBackOff(1s, 3) — 채택** | 총 3초 파티션 블록 후 DLT 이관. 짧은 일시 오류는 흡수하고 영속 실패는 격리 |

### DLT 리스너의 역할

DLT 리스너의 역할은 **관찰**뿐이다 — 자동 재처리는 위험(3회 실패한 걸 되돌리면 무한 루프). 사람이 원인 확인 후 수동 재발행이 안전하다.

각 DLT 토픽마다 리스너를 두고 error 로그로 남긴다. 단, `catalog-events.DLT`는 Like/View 두 컨슈머 그룹이 공유하므로 리스너 하나만 두어 중복 로깅을 피한다.

운영에서는 이 자리에 알림 훅(Slack/Sentry/Prometheus)을 붙인다.

---

## 공통 — Inbox 미도입 판단

Outbox는 도입했지만, 대칭 개념인 **Inbox 패턴은 의도적으로 도입하지 않았다**. 이유를 남긴다.

### Inbox 패턴이란

```
Message 수신 → inbox_events INSERT (payload 통째로) → 별도 프로세서가 처리
                    ↑ 여기까지 트랜잭션                      ↑ 별도 트랜잭션
```

Consumer 측 대칭 패턴. 세 가지 목적:

1. **Durability** — 받자마자 DB에 저장 → 컨슈머 다운돼도 유실 없음
2. **멱등** — inbox_id를 PK로 두면 중복 처리 방지
3. **재시도 분리** — 실패해도 inbox에 남아있어 별도 워커가 나중에 재처리

### Kafka + 현재 구성으로 이미 커버되는 이유

| Inbox 목적 | Kafka + 현재 구성의 대체 수단 |
|---|---|
| Durability | **Kafka 브로커 자체가 durable log**. offset을 커밋 안 하면 브로커에 남아있음. 컨슈머 재시작 시 커밋 안 된 offset부터 다시 poll |
| 멱등 | **`event_handled` 테이블** — eventId 기준 중복 감지 (`topic:partition:offset` 좌표 or `"order:{id}"` 비즈니스 키) |
| 재시도 분리 | **manual ack + DefaultErrorHandler + DLT** — 시스템 실패는 3회 재시도 후 DLT로 격리 |

즉 Kafka 자체가 Inbox 테이블 역할을 대신하고 있어서, 별도 Inbox를 두면 **같은 데이터를 두 번 저장하는 셈**이다.

### Inbox가 진짜 필요한 경우

- **소스가 Kafka가 아닌 경우** — HTTP webhook, RabbitMQ 등 durable하지 않은 곳에서 받을 때. Kafka는 이미 durable log라 이 문제가 없다.
- **처리 로직이 매우 비싼 경우** — 실패 후 재실행 코스트가 커서 미리 Inbox에 쌓아두고 워커 풀로 분산해야 하는 경우.
- **처리 지연을 허용해야 하는 경우** — 배치 스케줄러가 나중에 처리하는 시나리오.

현재 도메인(좋아요 집계, 결제 집계, 조회수 집계, 쿠폰 발급) 중 어느 것도 위 세 조건에 해당하지 않는다.

### 트레이드오프

| 얻는 것 | 잃는 것 |
|---|---|
| 브로커 독립적인 재처리 (Kafka 리텐션 지나도 재실행 가능) | 모든 이벤트를 두 번 저장 (Inbox + 실제 처리 결과) |
| 처리 지연 분리 (백프레셔에 유리) | Inbox 프로세서/스케줄러/상태 관리 코드 |
| 리플레이 히스토리 확보 | 트랜잭션 경계가 하나 더 늘어 복잡도 증가 |

지금 도메인 규모에서는 **비용이 이득을 초과한다**. 실제로 Kafka 리텐션(기본 7일)이 짧게 느껴지거나 브로커 의존을 줄여야 할 이유가 생기면 그때 도입을 재검토한다.

### 정리

- Outbox: **꼭 필요** — Producer가 발행에 실패하면 유실 위험이 있어 DB 트랜잭션과 묶어야 함
- Inbox: **불필요** — Kafka 자체가 Inbox 역할, `event_handled`가 멱등, DLT가 격리를 담당

Outbox와 Inbox는 이름은 대칭이지만 **문제의 성격이 다르다.** 대칭이라는 이유만으로 둘 다 도입할 필요는 없다.

---

## 공통 — 통합 검증 (Testcontainers)

Testcontainers로 실제 Kafka(`apache/kafka:3.8.1`) + MySQL을 띄워 검증:

- `LIKE_ADDED` 발행 → `like_count == 1`, `event_handled` 1건
- `ORDER_CONFIRMED`(items 2건) 발행 → 각 productId의 `sales_count` 증가, `event_handled` 키 `"order:{id}"` 1건
- 같은 orderId `ORDER_CONFIRMED` 2회 발행 → `sales_count` 1회만 반영 (멱등 확인)

쿠폰은 별도 동시성 통합 테스트에서 20 스레드 동시 요청으로 수량 제한과 멱등을 검증한다(Kafka 리스너 비활성 상태로 서비스 계층 직접 호출).

### 검증 중 발견한 버그 — 이중 직렬화

Producer `value-serializer`가 `JsonSerializer`였는데, Outbox payload는 이미 ObjectMapper로 직렬화된 **JSON 문자열**이다. JsonSerializer가 이 문자열을 한 번 더 직렬화해 바깥에 따옴표가 덧씌워진 채 발행됐다.

Consumer가 `StringDeserializer`로 받아 파싱하면 JSON 객체가 아니라 **문자열 리터럴**로 읽혀 `.get("eventType")`이 null → NPE → ack 안 됨 → lag 무한 누적.

→ Producer 설정을 `JsonSerializer` → `StringSerializer`로 수정.

> Producer와 Consumer의 직렬화 방식은 항상 짝을 맞춰야 한다. payload를 코드에서 직접 문자열로 만들면 String 계열, 객체를 그대로 넘기면 Json 계열이어야 한다. 섞이면 이중 인코딩이 조용히 발생한다.

---

## 공통 — ApplicationEvent 목록

| 이벤트 | 발행처 | phase | 목적 | 리스너 |
|---|---|---|---|---|
| `PaymentConfirmedEvent` | `PaymentFacade.applyPgResult` (SUCCESS) | AFTER_COMMIT | 알림 | `PaymentEventListener` |
| `PaymentFailedEvent` | `PaymentFacade.applyPgResult` (FAIL) | AFTER_COMMIT | 알림 | `PaymentEventListener` |
| `OrderCreatedEvent` | `OrderFacade.createOrder` | AFTER_COMMIT | 행동 로그 | `UserActionLogListener` |
| `ProductViewedEvent` | `ProductFacade.getProduct` | AFTER_COMMIT | 행동 로그 + 조회수 전파 | `UserActionLogListener` · `ProductViewEventPublisher` |

> `PaymentConfirmedEvent`(알림)와 `order-events` Outbox INSERT(집계)는 목적이 달라 같은 SUCCESS 분기에 공존한다 — 하나는 유실 허용(알림), 하나는 유실 불가(집계).

---

## 진행 현황

| 도메인 | ApplicationEvent | Outbox Producer | Consumer |
|---|---|---|---|
| 좋아요 | ✅ 완료 | ✅ 완료 | ✅ 완료 (Testcontainers 검증, DLT 포함) |
| 결제 | ✅ 완료 | ✅ 완료 | ✅ 완료 (Testcontainers 검증, DLT 포함) |
| 조회수 | ✅ 완료 | ✅ 완료 (Kafka 직접, Outbox 미경유) | ✅ 완료 (Testcontainers 검증, DLT 공유) |
| 쿠폰 | — | ✅ 완료 (Outbox) | ✅ 완료 (동시성 테스트, DLT 포함) |
