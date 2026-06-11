## 🧭 Context & Decision

### 문제 정의
- **현재 동작/제약**: 주문 흐름이 `주문 생성 → 결제 시작 → 결제 확정` 3단계 API로 분리. 초기 설계에서 쿠폰 소비는 주문 생성, 재고 선점은 결제 시작 트랜잭션에서 각각 처리됨.
- **문제(리스크)**: 결제 시작이 실패하면 주문 생성에서 이미 커밋된 쿠폰 소비가 롤백되지 않아 쿠폰이 영구 소진됨. 동시 주문 시 동일한 쿠폰·재고 row에 여러 스레드가 동시에 접근하면 Lost Update 발생.
- **성공 기준**: 쿠폰 소비 + 재고 선점 + 주문 생성이 원자적으로 처리되어 부분 성공이 없고, 동시 요청 시 재고·쿠폰 정합성이 보장됨.

---

### 선택지와 결정

**[결정 1] 트랜잭션 범위 — 쿠폰·재고·주문을 어느 단계에서 묶을 것인가**

- 고려한 대안:
  - A (기존): 쿠폰 소비는 `createOrder`, 재고 선점은 `startPayment` 트랜잭션으로 분리
  - B: 쿠폰 소비 + 재고 선점 + 주문 생성을 `createOrder` 단일 트랜잭션으로 통합
- 최종 결정: **B**
- 트레이드오프: `createOrder` 트랜잭션이 재고·쿠폰 락을 동시에 보유해 락 경합 범위가 넓어지지만, 셋 중 하나라도 실패하면 모두 롤백되어 부분 성공이 없어짐. `startPayment`는 상태 전이(`PENDING_PAYMENT → IN_PAYMENT`)만 담당하도록 단순해짐.

---

**[결정 2] 동시성 제어 — 어떤 락 전략을 사용할 것인가**

- 고려한 대안:
  - A (낙관적 락): `@Version`으로 충돌 감지 후 재시도
  - B (비관적 락): 쓰기 직전 `SELECT FOR UPDATE`
  - C (원자적 UPDATE): `UPDATE SET reserved_stock = reserved_stock + qty WHERE (total_stock - reserved_stock) >= qty` 조건부 갱신
- 최종 결정: **재고는 B (비관적 락), 쿠폰은 C (원자적 UPDATE)**
- 트레이드오프:
  - 낙관적 락은 쿠폰(유저별 row 분리, 충돌 빈도 낮음)엔 적합하지만, 재고(모든 유저가 같은 row 경쟁, 충돌 빈도 높음)엔 재시도 폭풍이 발생할 수 있음
  - 재고에 원자적 UPDATE를 적용하지 않은 이유: `affected rows = 0`만 반환되어 재고 부족인지 row 없음인지 Java에서 알 수 없음 → `StockModel.reserve()` 도메인 검증 로직을 유지할 수 없음
  - 쿠폰에 원자적 UPDATE를 적용한 이유: `AVAILABLE → USED` 단순 상태 전이라 조건이 간단하고, `affected rows = 0`이면 "이미 사용됨"으로 명확히 해석 가능
- 추후 개선 여지: 트래픽이 늘어 재고 처리량이 병목이 되면 재고도 원자적 UPDATE로 전환 검토 가능

---

## 🤔 고민한 점 / 막혔던 부분

쿠폰도 처음엔 재고와 동일하게 비관적 락(`SELECT FOR UPDATE`)으로 구현했다. 그런데 쿠폰 이슈 row는 유저별로 분리되어 있어 같은 row에 동시 접근하는 상황이 현실적으로 드물다. 비관적 락은 SELECT 시점부터 COMMIT까지 DB 락을 유지하므로, 충돌 빈도가 낮은 쿠폰에 적용하면 락 보유 시간만 길어지고 얻는 이점이 적다.

반면 쿠폰의 상태 전이는 `AVAILABLE → USED`로 단순해서 `WHERE status = AVAILABLE` 하나로 SQL에 조건을 표현할 수 있다. 원자적 UPDATE는 락 없이 WHERE 조건으로 "오직 하나만 성공"을 보장하고, `affected rows = 0`이면 "이미 사용됨"으로 명확히 해석된다.

다만 원자적 UPDATE 하나만으로는 "보유하지 않은 쿠폰"(404)과 "이미 사용된 쿠폰"(400)을 구분할 수 없다. 그래서 소유 확인(락 없는 SELECT)과 상태 전이(원자적 UPDATE)를 두 단계로 분리했다. 이 사이의 race condition — 두 스레드가 소유 확인을 동시에 통과하는 경우 — 은 step 2의 WHERE 조건이 막아준다. 동시에 통과하더라도 UPDATE를 성공시키는 건 하나뿐이다.

---

락 메서드를 분리하지 않아 발생한 이슈가 있었다.

처음에는 기존 조회 메서드에 직접 `@Lock(LockModeType.PESSIMISTIC_WRITE)`를 추가했다. 실행하자 `@Transactional(readOnly = true)` 컨텍스트에서 호출될 때 `SELECT FOR UPDATE`를 발급할 수 없다는 `GenericJDBCException`이 발생하며 테스트 9개가 실패했다.

원인은 readOnly 트랜잭션에서는 쓰기 락을 획득할 수 없다는 것이었다. 해결책은 락이 필요한 경우와 일반 조회를 아예 다른 메서드로 분리하는 것이었다.

```java
findAllByProductIds(ids)         // 일반 조회 (readOnly 포함 모든 컨텍스트)
findAllByProductIdsWithLock(ids) // 쓰기 전용 (PESSIMISTIC_WRITE)
```

이후로는 `@Lock`은 반드시 `WithLock` 접미사를 가진 전용 메서드에만 적용하는 규칙을 지키고 있다.

---

## 🙋 기타

`startPayment` 중복 호출 방어를 위해 `IN_PAYMENT` 상태를 추가했다. 재고 선점이 `createOrder`로 이동했기 때문에 `startPayment`는 상태 전이만 담당하는데, 같은 주문으로 두 번 호출하면 `PENDING_PAYMENT` 상태 체크로 막는다. 충돌 빈도가 낮고 한 사용자의 중복 요청이 대상이라 별도 락은 적용하지 않았다.
