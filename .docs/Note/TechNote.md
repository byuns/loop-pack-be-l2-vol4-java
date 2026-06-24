# [Challenge Story] 타임아웃 이후 도착한 콜백은 어디로 가는가 (6주차 · 6팀 · 변승진)

## TL;DR

타임아웃(600ms)이 나면 내부에서는 결제를 실패로 처리하고 결제 기록(PaymentModel)을 남기지 않는다. 그런데 PG는 그 이후에도 계속 처리 중이어서 성공 콜백이 도착할 수 있다. 콜백 처리 코드는 PaymentModel을 먼저 찾고 없으면 NOT_FOUND 예외를 던졌기 때문에, 이 케이스에서 콜백이 조용히 버려졌다. 복구 API가 있어도 콜백 경로 자체가 이 케이스를 처리하지 못하는 설계 구멍이었다. 콜백에 orderId를 추가하고, PaymentModel이 없을 때 orderId로 주문을 찾아 직접 복구하는 흐름으로 수정했다.

---

## Context (배경 및 목표)

- **어떤 시스템을 만드는가**: PG 비동기 결제 연동. pg-simulator는 결제 요청을 수락하면 1~5초 안에 처리 결과를 콜백으로 전달한다. 내부 시스템에는 600ms 타임아웃이 걸려 있어, PG 응답이 늦으면 결제를 실패로 처리하고 PaymentModel을 저장하지 않은 채 종료한다.

- **가장 큰 기술적 도전 과제**: 타임아웃이 발생한 이후 PG가 보내는 콜백을 올바르게 처리하는가. 이 시점에는 PaymentModel이 없는 상태라, 콜백 경로가 이 케이스를 처리할 수 있어야 한다. 나아가 복구 API와 콜백이 동시에 들어올 때 PaymentModel이 중복 생성되지 않아야 한다.

---

## Design & Implementation (설계 및 구현)

### 핵심 기술 선택

**[선택 1] 콜백에 orderId 추가**

콜백에서 PaymentModel이 없을 때 Order를 찾으려면 orderId가 필요하다. pg-simulator는 원래부터 콜백 바디에 orderId를 포함해 전송하고 있었다 — `CallbackRequest`가 `transactionKey`만 역직렬화하고 orderId를 버리고 있었을 뿐이다.

| 옵션 | Pros | Cons |
|------|------|------|
| A. orderId를 콜백 바디에서 읽기 | 추가 PG 호출 없음, PG가 원래 보내는 값 활용 | 콜백 바디를 신뢰하는 것 (orderId 위조 가능성) |
| **선택: B. orderId를 바디에서 읽되, 실제 상태는 PG 재조회** | orderId는 경로 탐색에만 사용, 상태는 PG 직접 확인 | PG 호출 1회 추가 |

**선택 근거**: orderId를 위조해 콜백을 보내도, 실제 상태 반영은 `pgClient.getTransaction(transactionKey)`로 PG에서 직접 가져온 값만 사용한다. orderId 위조로 얻을 수 있는 것은 "존재하지 않는 Order에 대한 NOT_FOUND 에러 유도" 뿐이어서 실질적 위협이 없다.

---

**[선택 2] loginId를 OrderModel에 추가**

PG 재조회(`pgClient.getTransaction`)에는 `X-USER-ID` 헤더(loginId)가 필요하다. 콜백 처리는 인증 없는 엔드포인트라 요청에서 loginId를 꺼낼 수 없고, PaymentModel도 없는 케이스이므로 다른 경로로 loginId를 구해야 한다.

| 옵션 | Pros | Cons |
|------|------|------|
| A. UserRepository.findById로 User 조회 | OrderModel 변경 없음 | PaymentFacade에 User 도메인 의존성 추가 |
| **선택: B. OrderModel에 loginId 필드 추가** | 단일 조회로 loginId 확보, 도메인 간 결합 없음 | OrderModel 생성자·테스트 수정 필요 |

**선택 근거**: PaymentModel이 이미 `loginId`를 저장하는 이유와 동일하다 — PG 호출 시 사용자 식별이 필요하기 때문이다. Order도 특정 사용자의 것이므로 loginId를 함께 저장하는 것이 자연스럽다. UserRepository를 주입하면 Payment 도메인이 User 도메인을 직접 참조하게 되어 의존 방향이 어긋난다.

### Logic Flow

**수정 전**

```
[타임아웃 케이스]
TX1: Order → IN_PAYMENT
PG 호출 시작
600ms 후 TimeoutException 발생
TX C: Order → PAYMENT_FAILED (PaymentModel 없음)

이후 PG가 SUCCESS 처리 → 콜백 발송
handleCallback(transactionKey)
  → findByTransactionKey("TX-xxx") → 없음
  → throw NOT_FOUND ← 콜백 버려짐
```

**수정 후**

```
[타임아웃 케이스]
(동일)

이후 PG가 SUCCESS 처리 → 콜백 발송
handleCallback(transactionKey, orderId)  ← orderId 추가
  → findByTransactionKey("TX-xxx") → 없음
  → find(orderId) → Order 조회
  → pgClient.getTransaction(order.loginId, transactionKey) → SUCCESS
  → PaymentModel 신규 생성 + Order → CONFIRMED
```

`recoverFromPgByTransactionKey`는 이미 transactionKey를 알고 있으므로, 복구 API의 `recoverFromPgByOrderId`(orderId로 목록 조회 후 최신 건 단건 재조회)와 달리 PG 호출 1회로 처리가 끝난다.

---

## Engineering Challenges (트러블슈팅 및 최적화)

### 예상치 못한 현상

타임아웃 케이스를 처음 분석했을 때 이미 복구 API(`POST /api/v1/payments/{orderId}/recover`)가 구현되어 있었다. "타임아웃으로 PaymentModel이 없어도 복구 API를 호출하면 된다" — 이 생각이 콜백 경로 자체를 검증하지 않게 만들었다.

그런데 실제로 콜백 경로는 어떻게 동작하고 있었나?

```
[타임아웃 후 PG가 SUCCESS 처리 → 콜백 발송]
handleCallback(transactionKey)
  → findByTransactionKey("TX-xxx") → 없음
  → throw NOT_FOUND ← 그냥 버려짐
```

pg-simulator는 콜백 전송 실패 시 재시도하지 않는다. 콜백이 한 번 버려지면 그걸로 끝이다. 복구 API는 **수동으로 호출해야** 작동한다 — 자동으로 고쳐지지 않는다. 복구 API의 존재가 콜백 경로의 버그를 가려온 것이었다.

### 추론 및 검증

**불일치 상태가 얼마나 오래 유지되는가**

```
타임아웃 발생 (600ms)
  → TX C: Order → PAYMENT_FAILED
  → PG: 아직 처리 중... (최대 5초)
  → PG: SUCCESS 처리 완료 → 콜백 발송
  → 콜백 버려짐 → 불일치 상태 고착
```

스케줄러가 있다면 IN_PAYMENT 상태가 일정 시간(PG 처리 최대 5초 + 네트워크 버퍼 = 10~30초 어딘가)을 넘기면 자동으로 감지해 복구를 시도할 수 있다. 그러나 현재 구현에는 스케줄러가 없다. 콜백이 올바르게 처리되는 것이 유일한 자동 복구 경로였던 것이다.

**검토했지만 구현하지 않은 것 — 실패 사유 구분**

타임아웃 실패인지, PG 거절인지를 구분하면 재시도 전략을 다르게 가져갈 수 있다. hard decline(한도초과, 잘못된 카드)은 재시도가 의미 없고, soft decline(일시적 장애)은 자동 재시도가 가능하다.

구현하지 않은 이유: pg-simulator가 내려주는 실패 사유는 "한도초과"와 "잘못된카드" 두 가지뿐이다 — 둘 다 hard decline이다. soft decline에 해당하는 사유를 내려주지 않는다. 시뮬레이터가 절대 만들지 않는 케이스를 처리하는 코드는 테스트할 수 없으니 만들지 않는다.

**동시 복구 방어 — 잘못된 첫 번째 시도**

복구 API와 콜백이 동시에 들어오면 두 요청이 모두 `existingPayment.isEmpty() = true`를 확인하고 PaymentModel을 중복 생성할 수 있다.

처음 시도한 접근:

```
@Transactional
recoverPayment() {
    existingPayment = paymentRepository.findByOrderId(orderId)
    if (existingPayment.isEmpty()) {
        orderRepository.findWithLock(orderId)  ← 락을 isEmpty 이후에 추가
        recoverFromPgByOrderId(order, loginId)
    }
}
```

이 방법은 동작하지 않는다. 락을 `isEmpty()` 체크 이후에 잡으면, 두 요청이 이미 같은 결론(`isEmpty()=true`)을 내린 상태에서 순서대로 락을 잡는다. 첫 번째 요청이 PaymentModel을 만들고 커밋한 뒤 락을 놓아도, 두 번째 요청은 isEmpty 체크 결과를 이미 true로 기억한 채 락을 잡으므로 여전히 PaymentModel 생성 경로로 진입한다.

### 최종 해결

```
[수정 전]
요청 A: find(orderId) → 일반 SELECT
요청 B: find(orderId) → 일반 SELECT (동시)
요청 A: isEmpty() → true → PaymentModel 생성
요청 B: isEmpty() → true → PaymentModel 또 생성 (중복!)

[수정 후]
요청 A: findWithLock(orderId) → SELECT FOR UPDATE, 락 획득
요청 B: findWithLock(orderId) → 락 획득 대기
요청 A: isEmpty() → true → PaymentModel 생성 후 커밋 → 락 해제
요청 B: 락 획득 → isEmpty() → false (이미 생성됨) → 종료
```

핵심: **락 선점이 isEmpty 체크보다 반드시 앞에 있어야 한다.** 요청 B가 락을 획득하는 시점에 요청 A의 커밋 결과가 이미 DB에 반영되어 있어야 하기 때문이다. 락이 그 순서를 강제한다.

---

## Verification & Insight (검증)

| 항목 | 수정 전 | 수정 후 |
|------|---------|---------|
| 타임아웃 케이스 콜백 처리 | NOT_FOUND 예외 → 콜백 버려짐 | orderId로 Order 조회 → 자동 복구 |
| 동시 복구 요청 | PaymentModel 중복 생성 가능 | findWithLock 선점 → 두 번째 요청 종료 |
| 콜백 유실 케이스 | 수동 복구 API 필요 | 동일 (자동 감지 수단 없음) |

- **불일치 창**: 타임아웃 ~ 콜백 도착까지 최대 약 5초 동안 PG와 내부 상태가 어긋난다. 이 구간에서 콜백 경로가 올바르게 처리하지 않으면 불일치는 수동 복구 전까지 영구히 유지된다.
- **Security**: orderId는 경로 탐색에만 사용하고 상태는 PG에서 직접 확인한다. 콜백 위조 방지 원칙(바디의 status를 신뢰하지 않고 PG 재조회)이 이 케이스에도 동일하게 적용된다.

---

## Lessons Learned

1. **복구 API가 있다고 해서 기존 경로의 버그가 가려지지 않는다.** 콜백 경로와 복구 API는 각각 독립적으로 올바르게 동작해야 한다. "복구 API로 나중에 고칠 수 있다"는 생각이 콜백 경로를 검증하지 않게 만들었다. 복구 API는 수동 트리거이므로, 그걸 자동 보정 수단으로 전제하는 순간 자동 경로(콜백)의 버그가 보이지 않게 된다.

2. **기존에 동작하던 정상 케이스만 테스트하면 실패 케이스의 버그를 놓친다.** 타임아웃으로 PaymentModel이 없는 상태에서 콜백이 오는 시나리오를 테스트했더라면 초기에 발견할 수 있었다.

3. **락의 위치는 "어디서 잡느냐"가 아니라 "어떤 체크보다 앞에 잡느냐"가 핵심이다.** isEmpty 체크 이후에 락을 잡으면 두 요청이 이미 같은 결론을 내린 상태에서 순서만 조정하는 것이라 중복을 막지 못한다. 락을 먼저 잡아야 첫 번째 요청의 커밋 결과가 두 번째 요청에 보인다.

4. **동시성 문제는 "발생할 가능성이 낮다"는 이유로 뒤로 미루기 쉽다.** 복구 API와 콜백이 동시에 들어오는 케이스는 타임아웃 이후 PG 처리가 완료되는 몇 초 안에만 발생하는 좁은 구간이다. 그러나 그 구간에서 중복 생성이 발생하면 정합성이 깨진다. 락은 "자주 발생하는 경우"가 아니라 "발생해서는 안 되는 경우"를 막기 위해 건다.
