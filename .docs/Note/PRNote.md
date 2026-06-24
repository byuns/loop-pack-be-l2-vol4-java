## 🧭 Context & Decision

### 문제 정의
- **현재 동작/제약**: 기존 결제 흐름에 실제 PG 연동을 추가한다. 로컬에서 실행 가능한 `pg-simulator`를 외부 결제 시스템으로 사용하며, 비동기 결제 방식(요청 수락 → 콜백으로 결과 수신)으로 동작한다.
- **문제(리스크)**: PG를 어디서 호출하고, 실패했을 때 내부 상태를 어떻게 보정할 것인가. 외부 HTTP 호출은 DB 트랜잭션의 ACID 보장 범위 밖에 있어, 합치면 커넥션 점유·롤백 불일치·락 보유 시간 증가가 동시에 발생한다.
- **성공 기준**: PG 장애·지연 시에도 내부 상태가 일관되게 유지되고, commerce-api 전체가 다운되지 않으며, 타임아웃으로 누락된 결제건도 복구할 수 있다.

---

### 선택지와 결정

**[결정 1] PG 외부 호출 방식 — FeignClient vs RestTemplate**

- 고려한 대안:
  - A (RestTemplate): 직접 구현. 서킷 브레이커·Timeout·Fallback을 모두 코드로 붙여야 함
  - B (FeignClient): `spring.cloud.openfeign.circuitbreaker.enabled: true` + yml 설정만으로 Resilience4j 서킷 브레이커·TimeLimiter·Fallback 연동
- 최종 결정: **B**
- 트레이드오프: A는 `RestTemplate` 호출 코드 외에 `@CircuitBreaker`, `@TimeLimiter`, fallback 메서드, 에러 응답 파싱을 모두 직접 붙여야 한다. B는 `sliding-window-size`, `failure-rate-threshold`, `timeout-duration` 등 Resilience4j 설정을 yml에만 작성하면 FeignClient가 자동으로 연동한다. 단, PG가 4xx/5xx를 반환할 때 Feign은 기본적으로 `FeignException` 하나로 뭉쳐버리므로, 에러 종류(404 없음 / 400 검증 실패 등)를 구분하려면 `ErrorDecoder`를 별도로 구현해야 한다. 이 부분은 복구 API 확장(결정 5) 시점에 실제로 필요해져서 그때 추가했다.

---

**[결정 2] 트랜잭션 구조 — 단일 트랜잭션 유지 vs 트랜잭션 경계 분리**

- 고려한 대안:
  - A (단일 트랜잭션): `@Transactional` 하나가 `startPayment` + PG 호출 + `paymentRepository.save` 전체를 감쌈. PG 실패 시 자동 롤백으로 Order가 PENDING_PAYMENT로 복원됨.
  - B (트랜잭션 분리): `[트랜잭션1] startPayment → 커밋` → `PG 호출(커넥션 없음)` → `[트랜잭션2] 저장`. PG 실패 시 트랜잭션 C(명시적 보정)로 Order를 PAYMENT_FAILED로 전이.
- 최종 결정: **초기에는 A, k6 부하 테스트 후 B로 전환**
- 트레이드오프: A는 구조가 단순하고 PG 실패 시 롤백으로 자동 복원되지만, 80명의 가상 유저 부하 테스트에서 `pool_timeout_rate` 7.02%가 실측됐다. PG 응답을 기다리는 100~600ms 동안 커넥션을 점유하는 구조가 실제로 풀 고갈을 일으킨다는 걸 수치로 확인했다. B로 전환 후 동일 조건에서 2.37%로 66% 감소. B의 단점은 PG 호출이 실패했을 때 상태를 되돌리는 보정 트랜잭션(트랜잭션 C)이 추가로 필요하다는 점이다. 이 보정 자체가 실패하면 주문이 결제 진행 중 상태에 멈춰 사용자가 재결제를 시도할 수 없게 된다. 발생 가능성은 매우 낮지만, 이런 상황이 생기더라도 복구 API(결정 5)로 수동으로 상태를 바로잡을 수 있다.

---

**[결정 3] 동시 결제 요청 방어 — 비관적 락 (초기 구현 후 보완)**

- 배경: 초기 구현(`orderRepository.find` + `order.startPayment()`)에는 락이 없었다. 실패 케이스 분석 과정에서 "같은 주문에 동시 요청이 오면 두 트랜잭션이 동일한 PENDING_PAYMENT 스냅샷을 읽고 둘 다 PG 호출에 성공할 수 있다"는 걸 뒤늦게 인식했다.
- 고려한 대안:
  - A (낙관적 락): version 컬럼으로 충돌 감지 → 충돌 시 재시도. 실패 후 예외가 사용자에게 노출될 수 있음.
  - B (비관적 락): `SELECT ... FOR UPDATE`로 먼저 온 요청이 락을 잡고, 뒤따라온 요청은 대기 후 IN_PAYMENT 상태를 확인해 BAD_REQUEST 반환.
- 최종 결정: **B**
- 트레이드오프: A는 충돌 시 `OptimisticLockingFailureException`이 발생하고 재시도하는데, 재시도 전에 이미 첫 번째 요청이 PG 호출까지 도달했을 수 있다. 두 번째 요청도 재시도에서 PG를 호출하면 같은 주문에 PG 거래가 2건 생긴다. B는 `SELECT FOR UPDATE`로 첫 번째 요청이 락을 잡는 순간 두 번째 요청은 DB 레벨에서 대기하다 커밋 이후 IN_PAYMENT 상태를 보고 `startPayment()` 검증에서 즉시 실패한다. PG 호출은 최대 1회로 보장된다. 락 경합 범위는 같은 `orderId`를 가진 요청끼리만이므로 전체 처리량에 미치는 영향은 미미하다.

---

**[결정 4] Fallback — 무조건 대체 응답 vs 원인 판별**

- 고려한 대안:
  - A (PgPaymentFallback): 예외 종류에 관계없이 항상 "서비스 불가" 반환
  - B (PgPaymentFallbackFactory): PG가 실제로 응답한 에러(404, 400 등)는 그대로 전달. 진짜로 응답을 못 받았을 때(타임아웃, 서킷 브레이커 OPEN)만 "서비스 불가"로 대체.
- 최종 결정: **B**
- 트레이드오프: 복구 API는 "PG가 404를 반환하면 복구할 거래가 없으니 종료"라는 분기가 핵심이다. A는 예외 종류를 보지 않고 항상 `CoreException(SERVICE_UNAVAILABLE)`을 던지므로, PG가 정상적으로 404를 보냈을 때도 "장애"로 처리해 이 분기 자체가 불가능하다. B는 `cause`를 확인해 `CoreException`(PG가 직접 보낸 에러)이면 그대로 전파하고, `CallNotPermittedException`이나 `TimeoutException`처럼 PG와 통신 자체가 안 됐을 때만 `SERVICE_UNAVAILABLE`로 대체한다. `PgErrorDecoder`는 이 구조의 전제 조건으로, Feign이 4xx/5xx를 `FeignException`으로 뭉쳐버리는 것을 막고 HTTP 상태 코드를 `CoreException`으로 정확히 변환해준다.

---

**[결정 5] 복구 API 범위 — transactionKey 기반 vs orderId 기반 확장**

- 고려한 대안:
  - A (transactionKey 기반): 로컬에 PaymentModel(PENDING)이 있을 때만 PG에 재조회해 상태 보정
  - B (orderId 기반 확장): 로컬에 PaymentModel이 없을 때 orderId로 PG에 먼저 물어보고, 거래가 있으면 그 transactionKey로 단건 재조회 후 동기화
- 최종 결정: **B**
- 트레이드오프: 타임아웃 케이스에서는 트랜잭션 C가 실행된 후 PaymentModel이 로컬에 없는 상태다. A는 `transactionKey`를 알아야 PG에 조회할 수 있는데, `transactionKey`는 PaymentModel에만 있으므로 해당 건에 접근할 방법 자체가 없다. 복구 API를 호출해도 "결제 정보 없음" 에러로 끝난다. B는 PG의 `GET /api/v1/payments?orderId=` API를 이용해 `transactionKey` 없이도 접근한다. PG가 404를 반환하면 거래 자체가 없는 것이므로 종료, 거래가 있으면 최신 1건의 `transactionKey`로 단건 재조회 후 PaymentModel을 새로 생성해 동기화한다. 추가 PG 호출이 생기지만 복구 API는 수동 호출이라 빈도가 낮다. 단, orderId 기반 조회 응답에는 카드 종류 정보가 없어 `"UNKNOWN"`으로 채우는데, 이 값은 결제 처리 로직에 사용되지 않는 부수 정보라 실질적 문제는 없다.

---

**[결정 6] PAYMENT_FAILED 주문 재결제 허용**

- 고려한 대안:
  - A (실패 사유 구분): hard decline(한도초과/잘못된카드)은 재시도 차단, soft decline만 허용
  - B (일괄 허용): 실패 사유에 관계없이 PAYMENT_FAILED → 재결제 허용, 카드 선택은 사용자에게 위임
- 최종 결정: **B**
- 트레이드오프: A는 실패 사유를 코드로 구분할 수 있을 때만 의미가 있다. pg-simulator는 한도초과·잘못된카드(둘 다 hard decline) 두 가지만 반환하고, 일시적 장애 같은 soft decline 사유를 내려주지 않는다. 존재하지 않는 케이스를 처리하는 코드는 테스트할 수 없으므로 만들지 않는다. B는 PAYMENT_FAILED 상태에서 `startPayment()`를 허용하도록 조건을 추가하는 것만으로 구현이 끝난다. 같은 카드를 다시 시도해서 같은 결과가 나오는 건 사용자 선택의 문제이고, 실제 서비스에서 soft decline을 구분해 자동 재시도가 필요하다면 그때 A 방향으로 확장하면 된다.

---

**[결정 7] 타임아웃 케이스 콜백 버그 수정 — handleCallback에 orderId 추가**

- 고려한 대안:
  - A (현행 유지): 타임아웃 케이스에서 콜백이 오면 NOT_FOUND 반환, 복구 API로 수동 보정
  - B (orderId 추가 + 직접 복구): 콜백 바디의 orderId로 Order를 찾고, 이미 알고 있는 transactionKey로 PG에 직접 재조회해 PaymentModel 신규 생성
- 최종 결정: **B**
- 트레이드오프: A는 콜백 경로 자체가 타임아웃 케이스를 처리하지 못하는 설계 구멍이다. "복구 API로 나중에 고칠 수 있다"는 생각으로 방치했지만, 콜백이 유실되지 않는 한 복구 API를 호출하기 전에 콜백이 먼저 도달한다. pg-simulator는 콜백 전송 실패 시 재시도하지 않으므로, 콜백 경로가 NOT_FOUND를 반환하면 그 건은 복구 API를 수동으로 호출하지 않는 한 영구 누락된다. B는 orderId를 경로 탐색에만 사용하고 실제 상태는 PG에 재조회해 확인한다. orderId를 위조해 콜백을 보내도 얻을 수 있는 것은 "없는 Order에 대한 NOT_FOUND 유도"뿐이어서 보안 위협이 없다. 도메인 모델 변경은 OrderModel에 loginId 필드 추가뿐이며, PaymentModel이 이미 같은 이유(PG 호출 시 사용자 식별)로 loginId를 저장하므로 일관된 패턴이다.

---

**[결정 8] recoverPayment 비관적 락 — 동시 복구 요청 시 PaymentModel 중복 생성 방지**

- 고려한 대안:
  - A (unique constraint): PaymentModel에 transactionKey unique 제약을 걸어 중복 삽입을 DB 레벨에서 차단
  - B (비관적 락): Order에 SELECT FOR UPDATE로 락 선점 → isEmpty() 체크 → PaymentModel 생성 순으로 진행
- 최종 결정: **B**
- 트레이드오프: A는 중복 삽입 시 `DataIntegrityViolationException`이 발생하고 호출 측에서 예외를 잡아 멱등하게 처리하는 별도 핸들링이 필요하다. B는 DB 레벨 예외 없이 첫 번째 요청의 커밋 결과를 두 번째 요청이 자연스럽게 읽는다. 핵심은 **락 선점 위치가 isEmpty 체크보다 반드시 앞이어야 한다는 점**이다. 락을 isEmpty 이후에 잡으면 두 요청 모두 isEmpty()=true를 보고 중복 생성 경로로 진입한다. 복구 API와 타임아웃 직후 콜백이 겹치는 구간(PG 처리 완료 후 몇 초 이내)은 좁지만, 그 구간에서 중복이 발생하면 동일한 transactionKey를 가진 PaymentModel이 두 개 생겨 정합성이 영구히 깨진다.

---

## 📊 k6 부하 테스트 — 설계 검증

설계 단계에서 이론으로만 판단한 것들이 실제 부하에서도 동일하게 동작하는지 k6로 검증했다.

**시나리오 1 — Race Condition (비관적 락 검증)**

동일한 주문에 10명의 가상 유저가 동시에 결제를 요청했을 때 중복 결제가 발생하지 않는지 확인했다. 결과: `race_blocked_count = 10`, `checks 100%`. 10건 모두 400으로 차단됐다 — 락을 먼저 획득한 트랜잭션1이 커밋된 후 나머지 9건이 IN_PAYMENT 상태를 읽어 `startPayment()` 검증에서 실패한 것이다.

**시나리오 2 — 커넥션 풀 포화 (트랜잭션 분리 효과 측정)**

warmup(20명의 가상 유저) / saturation(50명의 가상 유저) / overload(80명의 가상 유저) 3단계로 데이터베이스 커넥션 풀 한계를 측정했다.

| 지표 | 트랜잭션 분리 전 | 트랜잭션 분리 후 |
|------|-----------|-----------|
| pool_timeout_rate | 7.02% (48 / 683건) | **2.37% (16 / 674건)** |
| payment_duration p(95) | 3.57s | **2.55s** |
| payment_duration max | 5.52s | **3.94s** |

`pool_timeout_rate` 66% 감소가 핵심 수치다. 트랜잭션1(~50ms) + 트랜잭션2(~20ms)로 커넥션 점유 시간이 대폭 단축된 직접적인 효과다. warmup/saturation p(95)는 소폭 상승(1.07s → 1.35~1.52s)했는데, minimum-idle 30→10 조정으로 초기 커넥션 생성 비용이 추가되고 요청당 커넥션 획득 횟수가 1→2회로 늘어난 영향이다. "개별 요청이 수백 ms 느려지는 대신 풀 고갈로 3초를 기다리다 503을 받는 요청이 66% 줄었다"는 트레이드오프로 판단했다.

---

## 🤔 고민한 점 / 막혔던 부분

`@Transactional`을 같은 클래스 안에서 여러 번 나누려 했을 때 문제가 생겼다. Spring AOP는 외부에서 들어오는 프록시 호출에만 적용되므로, `this.someMethod()` 같은 내부 호출에는 트랜잭션이 적용되지 않는다. 트랜잭션1·트랜잭션 C·트랜잭션2를 한 클래스 안에서 각각 독립된 트랜잭션으로 실행하려면 AOP가 아닌 프로그래밍 방식이 필요했다. `PlatformTransactionManager`를 주입받아 `@PostConstruct`에서 `TransactionTemplate`을 초기화하는 방식으로 해결했다.

---

콜백을 받을 때 바디의 `status` 필드를 그대로 믿었다가 위조 가능성이 있다는 걸 뒤늦게 인식했다. PG 콜백에는 서명이나 인증 헤더가 없어 발신지를 검증할 방법이 없다. 해결책으로 콜백 바디의 status를 사용하지 않고, 콜백 수신 즉시 `transactionKey`로 PG에 재조회해 실제 상태를 가져오는 방식으로 바꿨다. 조회 비용이 추가되지만 위조된 콜백에 의해 주문 상태가 잘못 전이되는 리스크를 없앴다.

---

## 🙋 기타
