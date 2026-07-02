# 이벤트 기반 파이프라인 — Outbox + Kafka Consumer 설계

## 🧭 Context & Decision

### 문제 정의
- **현재 동작/제약**: 좋아요·결제·조회수·쿠폰 도메인의 부가 로직(집계·알림·로깅·발급)이 요청 트랜잭션 안에 섞여 있다. 이를 이벤트로 분리해 발행자가 구독자를 모른 채 "과거 사실"만 남기도록 재구성한다.
- **문제(리스크)**: 트랜잭션을 어디서 끊을지, 이벤트가 유실·중복되지 않을지, 다른 시스템에 어떻게 전파할지가 동시에 걸린다. 부가 로직 실패가 사용자 요청 자체를 실패시키면 안 되고, 비동기로 분리하는 순간 유실·중복·순서·읽기 일관성이 새로운 설계 문제가 된다.
- **성공 기준**: 부가 로직을 분리해도 (유실 불가 도메인은) 집계가 일관되게 유지되고, 중복 이벤트가 이중 반영되지 않으며, 처리 실패가 파이프라인 전체를 막지 않고 격리된다. 구독자가 늘어도 발행자 코드는 바뀌지 않는다.

---

### 선택지와 결정

**[결정 1] 분리 방식 — "유실 허용 × 다른 시스템" 두 축으로 판단**

- 고려한 대안:
  - A (`@TransactionalEventListener` AFTER_COMMIT): 같은 프로세스 내 후처리. 유실 허용·단일 시스템용
  - B (Kafka 직접, fire-and-forget): 다른 시스템 전파하되 유실 허용
  - C (Outbox + Kafka): 다른 시스템 전파 + 유실 불가
- 최종 결정: **도메인 성격별로 A/B/C를 매트릭스로 선택**
- 트레이드오프: 트랜잭션 경계는 "구현이 편한가"가 아니라 "두 가지가 항상 동시에 일관해야 하는 비즈니스 불변식이 있는가"로 판단했다.

  | 부수효과 | 유실 허용? | 다른 시스템? | 방식 |
  |---|---|---|---|
  | likeCount·판매량 집계 | No | Yes | **C** Outbox + Kafka |
  | 결제 알림·행동 로깅 | Yes | No | **A** AFTER_COMMIT |
  | 조회수 집계 | Yes | Yes | **B** Kafka 직접 |

  집계는 한 건 유실이 곧 DB에 남는 틀린 숫자라 유실 불가다. 여기서 "유실 허용"의 기준은 "한 건쯤 빠져도 괜찮다"가 아니라 **"누락이 영구적으로 굳는가"**다 — 조회수는 통계라 몇 건 빠져도 추세에 영향이 없다.

---

**[결정 2] ApplicationEvent → Outbox 교체 — 이벤트를 어디에 두나**

- 고려한 대안:
  - A (ApplicationEvent): 메모리에만 존재. 커밋 직후 서버가 죽으면 이벤트 유실 → 집계 영영 누락
  - B (Outbox): 도메인 저장과 **같은 TX 안에서** `outbox_events`에 INSERT → TX 커밋되면 이벤트도 DB에 보장. Poller가 5초마다 미발행분을 Kafka로 발행
- 최종 결정: **B (유실 불가 집계 도메인)**
- 트레이드오프: A는 구현이 단순하고 커밋 직후 즉시 반영되지만 서버 크래시에 취약하다. B는 크래시에도 DB에 남아 복구 가능하지만 Poller 주기만큼 반영이 지연되고 구현 복잡도가 올라간다. 집계는 "즉시성"보다 "유실 없음"이 중요해 지연을 수용했다.

---

**[결정 3] 멱등 키 — Kafka 좌표 vs 비즈니스 키**

- 고려한 대안:
  - A (Kafka 좌표 `topic:partition:offset`): 좌표가 안 겹쳐 자연스러운 고유값
  - B (비즈니스 키 `order:{orderId}`): 주문당 평생 1회인 사실을 키로 사용
- 최종 결정: **도메인별 — 좋아요는 A, 결제는 B**
- 트레이드오프: 좋아요는 같은 상품에 이벤트가 여러 번 올 수 있어 좌표를 쓴다. 하지만 좌표 기반은 **Outbox 재발행으로 (partition, offset)이 바뀌면 중복 감지에 실패**하는 한계가 있다(아래 고민 참조). 결제는 `ORDER_CONFIRMED`가 주문당 1회뿐이라 `order:{orderId}`를 쓰면 재발행·리밸런싱으로 좌표가 바뀌어도 중복을 정확히 잡는다.

  결국 **파티션 키 설계가 멱등·순서 전략을 결정한다.** 조회수는 `productId` 키라 단일 파티션에서 순서가 보장돼 `occurredAt` 가드로 stale만 떨군다. 반면 판매량은 `orderId` 키라 순서가 안 보장돼 비즈니스 키 멱등을 쓴다.

---

**[결정 4] 판매량 집계 동시성 — find-modify-save vs 원자 UPSERT**

- 고려한 대안:
  - A (`find → count + delta → save`): 읽고 더해서 저장
  - B (`INSERT ... ON DUPLICATE KEY UPDATE count = count + :delta`): DB가 읽기-수정-쓰기를 단일 쿼리로 처리
- 최종 결정: **B (원자 UPSERT)**
- 트레이드오프: 결제는 `orderId`로 파티셔닝되는데 집계 단위는 `productId`다. 상품 A가 든 주문 10개가 동시에 처리되면 Consumer 여럿이 `product_metrics`의 같은 행을 동시에 건드려 A 방식에서는 lost update가 난다(각자 5를 읽고 +2·+3을 쓰면 마지막 값으로 덮임). B는 DB 레벨에서 원자적으로 누적해 동시 업데이트에도 안전하다. 좋아요는 `productId` 파티셔닝이라 같은 행을 동시에 건드리는 Consumer가 없어 이 문제가 없다 — **동시성 대책은 파티션 키에 따라 필요 여부가 갈린다.**

---

**[결정 5] 실패 처리 — 무한 재시도 vs FixedBackOff + DLT**

- 고려한 대안:
  - A (무한 재시도, no-ack): 성공할 때까지 재시도
  - B (ExponentialBackOff): 지수적으로 간격 증가
  - C (`FixedBackOff(1s, 3)` + DLT): 1초 간격 3회 후 `.DLT`로 격리
- 최종 결정: **C**
- 트레이드오프: A는 영속 실패 시 파티션이 영원히 잠겨 lag가 무한 누적된다. B는 백오프 동안 같은 파티션의 다음 메시지가 막혀(파티션 블로킹) 처리량이 급감한다. C는 총 3초 파티션 블록 후 DLT로 옮겨, 짧은 일시 오류는 흡수하고 영속 실패는 격리한다. 단 **비즈니스 실패(수량 소진·중복)는 DLT로 보내지 않는다** — Service가 정상 반환하고 ack해 Kafka에서 제거한다. 재시도해도 결과가 안 바뀌기 때문. DLT 리스너의 역할은 자동 재처리가 아니라 **관찰**뿐이다(3회 실패분을 자동 되돌리면 무한 루프 위험).

---

**[결정 6] 쿠폰 선착순 — 동기 처리 vs Kafka 직렬화**

- 고려한 대안:
  - A (동기 TX): `issueCoupon()`이 발급까지 한 트랜잭션에서 완료. DB row lock이 직렬화 지점
  - B (Kafka + `couponId` 파티션 키): API는 Outbox INSERT만 하고 202 반환, Consumer가 순서대로 처리
- 최종 결정: **B**
- 트레이드오프: 선착순 100장에 1만 명이 몰리면 A는 row lock에 대부분의 요청이 lock wait로 쌓여 DB가 병목이 된다. B는 `couponId` 파티션 키로 같은 쿠폰 요청을 단일 파티션에서 직렬 처리해 경합을 없애고, API 응답이 빠르다. 대신 "발급됐나요?"를 즉시 못 주고 polling으로 확인해야 한다(핵심 트레이드오프). 수량 제한은 파티션 직렬화만 믿지 않고 **DB 원자 감소**(`remaining_count = remaining_count - 1 WHERE remaining_count > 0`)를 최후 방어선으로 뒀다 — 리밸런싱 순간 두 Consumer가 잠깐 겹치는 엣지 케이스까지 막기 위해서다. 요청/발급 테이블을 분리(`coupon_issue_requests` vs `coupon_issues`)해, 기존 코드가 PENDING을 발급으로 오인하지 않게 했다.

---

## 📊 Kafka 옵션 실험 — 설계 검증

이론으로 결정한 옵션들을 실제로 돌려 동작을 확인했다. 예상과 다른 결과가 설계 결정에 영향을 준 것들만 남긴다.

**`acks=all` + `enable.idempotence=true`를 반드시 함께 명시해야 하는 이유**

`acks=0` + `idempotence=true`를 의도적으로 충돌시켰을 때 `ConfigException`이 날 거라 예상했지만, 에러 없이 시작하고 로그에 `enable.idempotence = false`로 찍혔다. Kafka 3.0+는 충돌 옵션을 예외 대신 silent disable로 처리한다. `acks=all`을 함께 명시하지 않으면 멱등이 조용히 꺼진다.

**broker가 죽으면 `acks` 값과 무관하게 발행이 실패한다**

`acks=0`은 응답을 기다리지 않으니 broker가 죽어도 성공할 거라 예상했지만, `acks=0`과 `all` 모두 send가 실패했다(`published_at=NULL`). `acks`는 TCP 연결이 잡힌 뒤의 응답 대기 옵션이라, broker가 죽으면 연결 자체가 실패해 acks 값이 의미 없다. 이때 outbox에 쌓인 이벤트는 broker 복구 후 Poller가 자동 재시도한다 — Outbox가 broker 장애까지 흡수하는 이유다.

**`acks=all`의 비용 — 내구성은 공짜가 아니다**

3-broker 클러스터에서 `acks=1` vs `all` 비교: 처리량 ~26,500 → ~18,900 rec/s(30%↓), 지연 ~219 → ~467ms(2배). 이 비용 때문에 유실 허용인 조회수는 Outbox 없이 직접 발행하고, 유실 불가인 likeCount·salesCount만 이 비용을 감수하는 방식으로 도메인별로 나눴다.

**`min.insync.replicas=2`가 Outbox와 맞물리는 방식**

RF=3 / `min.insync.replicas=2` 설정에서 broker 1대 down은 발행 성공(ISR 2≥2), 2대 down은 `NotEnoughReplicasException`으로 거부됐다. 거부되는 동안 outbox에 `published_at=NULL`로 이벤트가 쌓이고, broker 복구 후 Poller가 자동으로 재시도한다. ISR 부족으로 인한 발행 거부까지 Outbox가 흡수한다.

**`linger.ms`는 현재 구조에서 효과가 없다**

`linger.ms=0`(기본) 320ms vs `linger.ms=100` → 2533ms(~8배). Poller가 `send().get()` 동기로 1건씩 발행하는 구조라 linger를 높여봐야 대기 시간만 추가된다. 여러 send()가 동시에 일어나는 환경에서만 배치 효과가 생긴다. **현재 0 유지.**

**`enable.auto.commit=false` + manual ack가 필수인 이유**

auto commit 상태에서 poll 후 처리 전 크래시를 시뮬레이션했을 때, 재구독 시 해당 메시지를 받지 못했다. offset이 자동 커밋돼 처리된 것으로 기록되기 때문이다. at-least-once 보장에는 manual ack가 필수다.

**처리 지연은 `session.timeout`이 아닌 `max.poll.interval.ms`로 대응한다**

`max.poll.interval.ms=2000`에서 4초 처리(sleep)로 초과시키면 그룹에서 추방돼 `commitSync()`가 `CommitFailedException`으로 실패했다. 처리가 느린 문제라면 `session.timeout`을 늘려도 해결되지 않고 `max.poll.interval.ms`를 늘리거나 `max.poll.records`를 줄여야 한다.

**`group.id`가 fan-out 구조를 결정한다**

다른 그룹 2개는 같은 토픽에서 각자 전부 수신(fan-out), 같은 그룹 Consumer 2개는 파티션을 나눠 처리(분담)했다. `like-aggregator`와 `view-aggregator`가 같은 `catalog-events`를 각자 독립적으로 집계할 수 있는 이유다.

**통합 검증** — Testcontainers로 실제 Kafka + MySQL을 띄워 `LIKE_ADDED`→likeCount, `ORDER_CONFIRMED`(items 2건)→salesCount, 같은 orderId 2회 발행→1회만 반영(멱등)을 확인. 쿠폰은 20 스레드 동시 요청으로 수량 제한·멱등 검증.

---

## 🤔 고민한 점 / 막혔던 부분

**쌓이는 기록의 정리 시점(retention).** `outbox_events`(발행분)와 `event_handled`(멱등 로그)가 둘 다 무한 증식한다. 조회는 부분 인덱스/PK 기반이라 성능 문제는 아니고, 순수하게 증식 관리다. 다만 **지워도 되는 시점이 다르다** — outbox 발행분은 일 끝난 죽은 데이터라 보관 기간이 자유롭지만, 멱등 로그는 살아있는 중복 판단 근거라 같은 이벤트 재도착(재전송·재발행·DLT 재처리) 시 없으면 이중 집계가 난다. 그래서 멱등 로그는 **Kafka 리텐션보다 길게** 보관 후 삭제해야 안전하다. (리텐션을 늘리면 멱등 로그 보관도 함께 늘려야 하는 숨은 결합이 있다.)

---

**readOnly TX 안에서 이벤트 발행.** 조회수는 `getProduct`(readOnly TX)에서 발행하는데, TX 밖에서 publish하면 `@TransactionalEventListener`가 발화하지 않는다. readOnly TX도 커밋되므로 그 안에서 발행해야 AFTER_COMMIT이 정상 동작한다.

---

## 🙋 기타

- 도메인 진행: 좋아요·결제·조회수·쿠폰 4개 파이프라인 모두 ApplicationEvent / (Outbox or Kafka 직접) / Consumer + DLT까지 완료, Testcontainers 검증 포함.
- 전역 DLT 팩토리 하나(`FixedBackOff(1s,3)` + `DeadLetterPublishingRecoverer`)를 모든 컨슈머가 재사용. `auto.create.topics.enable=false`라 각 `.DLT`를 `NewTopic` 빈으로 명시 선언.
