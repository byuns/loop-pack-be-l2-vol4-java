# Kafka에 send() 하나면 끝난 줄 알았다

한 줄이면 될 줄 알았다.

```java
kafkaTemplate.send("catalog-events", productId, event);
```

TX 커밋되면 Kafka로 흘러가고, Kafka가 알아서 이벤트를 안 잃도록 보관하고, Consumer가 알아서 집계한다. 실제로 대부분의 시간엔 그대로 굴러간다.

문제는 "대부분"에 있었다.

broker가 잠깐 죽거나, 옵션 조합이 어긋나거나, ISR이 부족한 그 짧은 순간에 이벤트가 조용히 사라진다. 조용해서 놓치기 쉽다. "Kafka는 durable하다"는 말은 실은 이벤트가 **도달하고 나서**의 얘기였고, 도달 이전은 다른 문제였다.

그 도달 실패의 순간들을 직접 봐야 Outbox Pattern이 왜 필요한지 감이 온다. 3-broker 클러스터를 세팅해두고 broker를 하나씩 내려가며 실험한 기록을 남긴다.

## TL;DR

- broker가 죽어 있으면 `acks` 값과 무관하게 `send()` 자체가 실패한다. TCP가 못 잡히기 때문.
- `acks=0` + `idempotence=true`를 함께 쓰면 예외 없이 idempotence만 조용히 꺼진다 (Kafka 3.0+ silent disable).
- `min.insync.replicas` 미달이면 리더가 살아있어도 발행이 거부된다 (`NotEnoughReplicasException`).
- `acks=all`의 실측 가격은 **처리량 30% 감소, 지연 2배**. 내구성은 공짜가 아니다.
- Outbox는 이 모든 "발행 전 유실" 창을 DB 트랜잭션 뒤로 옮겨서 흡수한다. Poller가 broker 복구 후 자동 재시도한다.

Kafka가 이벤트를 안 잃는 건 **도달하고 나서**의 얘기다. 도달 자체를 지키는 건 애플리케이션의 몫이었다.

---

## 시작 전에 — 실험에 나오는 Kafka 옵션 4가지

실험 얘기로 넘어가기 전에, 앞으로 계속 나올 옵션 4개를 표로 정리해둔다. 미리 봐두면 각 실험이 뭘 확인하려는 건지 훨씬 잘 잡힌다.

| 옵션 | 뜻 | 값 / 주의점 |
|---|---|---|
| **`acks`** | Producer가 "성공" 처리 전에 broker 응답을 어디까지 기다릴지 | `0` = 응답 안 기다림 (가장 빠름)<br>`1` = 리더가 저장하면 성공 (가장 흔함)<br>`all` = 복제본까지 저장해야 성공 (가장 안전) |
| **`enable.idempotence`** | Producer 재시도 시 중복 발행 방지. Kafka가 메시지마다 순번(sequence number)을 붙여 걸러낸다 | **`acks=all`이 짝으로 켜져 있어야 유효**. 짝 없이 혼자 켜면 조용히 꺼진다 → 두 번째 실험에서 확인 |
| **`min.insync.replicas`** | broker 측 옵션. ISR 개수가 이 값 미만이면 발행 자체를 거부 | 3-broker에 `min=2`면:<br>1대 down → 발행 OK<br>2대 down → 발행 거부 |
| **ISR** (In-Sync Replicas) | 리더와 최신 상태로 동기화된 복제본 집합 | 리더를 따라가지 못하면 ISR에서 빠짐. `min.insync.replicas`가 이 개수를 감시 |

> **`acks`를 편지에 비유하면**: 우체통에 넣고 끝(`0`), 우체국 도착 확인(`1`), 상대방 손에 전달된 확인(`all`).

---

## 첫 번째 실험 — broker를 그냥 죽여봤다

가장 먼저 알고 싶었던 건 이거였다. broker가 죽어 있을 때 `acks=0`이면 응답을 안 기다리니까, 성공한 것처럼 처리되지 않을까?

로컬 Kafka broker를 내리고 두 옵션으로 각각 발행을 시도했다.

| acks 설정 | 결과 |
|---|---|
| `acks=0` | 실패 — `outbox_events.published_at = NULL`로 남음 |
| `acks=all` | 실패 |

> **[그림 1]** Kafka down 시 acks 값 무관하게 send()가 실패하는 시퀀스

두 옵션 모두 실패했다. `acks=0`이라고 다른 처리를 하지 않는다. 이유는 `acks`의 정체를 다시 보면 명확해진다.

`acks`는 **TCP 연결이 잡힌 뒤 broker의 "저장 완료" 응답을 얼마나 기다릴 것인가**의 옵션이다. broker가 죽으면 TCP 자체가 못 잡히니 `acks` 값과 무관하게 send()가 예외를 던진다. `acks=0`이 "broker가 죽어도 성공"인 게 아니라 "**연결은 잡혔는데 저장 확인을 안 함**"이라는 걸 이제야 이해했다.

즉, 그냥 Kafka에 던지는 코드는 broker 장애 순간 이벤트를 잃는다. 이걸 재시도할 어딘가가 필요해진다.

## 두 번째 실험 — 옵션이 조용히 꺼졌다

첫 실험 뒤 궁금해졌다. 만약 옵션 조합을 잘못 쓰면, 애플리케이션 시작 자체가 실패해서 실수가 드러날까?

의도적으로 모순되는 조합으로 띄웠다.

```yaml
producer:
  acks: 0
  enable-idempotence: true   # ← acks=all이 필요한 옵션
```

> `enable-idempotence`는 Producer가 재시도할 때 생기는 중복 발행을 Kafka가 sequence number로 감지해 제거해주는 옵션이다. 켜져 있어야 send 재시도가 안전해진다.

기대한 결과는 `ConfigException`. 실제 결과는 **에러 없이 시작**이었다. ProducerConfig 로그를 뒤져보니 조용히 이렇게 찍혀 있었다.

```
enable.idempotence = false
```

> **[그림 2]** acks=0 + idempotence=true 조합 시 silent disable 개념도

Kafka 3.0+ 부터는 옵션 충돌이 있으면 예외를 던지지 않고 **문제되는 옵션만 조용히 꺼버린다**([KIP-679](https://cwiki.apache.org/confluence/display/KAFKA/KIP-679)). 로그를 확인하지 않으면 "나는 idempotent producer를 쓰고 있다"고 오해하기 딱 좋다. 나도 이번 실험 전까지는 그렇게 오해하고 있었을 것 같다.

`acks=all` + `enable.idempotence=true` + `max.in.flight=5`가 왜 **한 세트**로 묶여야 하는지 이 실험으로 이해했다. 셋 중 하나만 만져도 나머지가 조용히 꺼진다.

## 세 번째 실험 — 3-broker에서 진짜 동작

여기까지가 단일 broker에서 볼 수 있는 것의 전부다. `acks=all`이 진짜 어떻게 동작하는지 보려면 broker가 여러 대여야 한다. 단일 broker에서는 기다릴 follower가 없어 `acks=all`이 실은 `acks=1`과 동일하기 때문.

### 클러스터를 어떻게 짤 것인가

Kafka는 broker와 controller 역할이 있다. combined 모드(한 노드가 broker와 controller 역할을 겸함)로 3노드를 두면 편하지만, broker 2대를 죽이면 controller 쿼럼도 함께 깨져 클러스터 자체가 마비된다. 그러면 `min.insync.replicas` 발동만 관찰하고 싶을 때 다른 문제가 섞여든다.

그래서 **dedicated controller 1대 + broker 3대**로 분리했다. 이러면 broker를 몇 대 죽여도 controller 쿼럼이 유지돼 실험이 깔끔해진다.

> **[그림 3]** combined vs dedicated controller 구성 — broker 2대 죽였을 때 쿼럼 유지 차이

### min.insync.replicas 실험

옵션은 앞서 정리한 대로다. 바로 실험 세팅으로 넘어간다.

- Replication Factor: 3 (복제본 수)
- `min.insync.replicas`: 2
- `acks=all`

| broker 상태 | ISR | `acks=all` 발행 |
|---|---|---|
| 전체 정상 | 3 | 성공 |
| broker 1대 down | 2 | 성공 (2 ≥ min=2) |
| broker 2대 down | 1 | **거부** — `NotEnoughReplicasException` |

> **[그림 4]** broker 상태별 발행 결과 다이어그램

리더가 살아있어도 ISR이 부족하면 **쓰기 자체가 거부**된다. `RF=3 / min.insync=2`는 "**1대 장애는 버티고, 2대 장애면 차라리 멈춘다**"는 가용성↔내구성 균형점이다.

이 균형점이 흥미로운 이유는 이런 거였다. broker 2대가 죽었을 때 "일단 리더에 저장하고 나중에 복제"할 수도 있다. Kafka는 그렇게 안 한다. **불완전한 상태로는 애초에 안 받는다.** 이 강경함이 acks=all의 신뢰성을 만든다.

당연히 이 순간에도 send()는 예외를 던진다. 여기서도 이벤트가 유실될 창이 열린다.

## acks=all의 가격은 얼마였나

여기까지 오면 자연스러운 질문이 생긴다. **"이 안전을 위해 얼마나 낼 준비가 되어 있나?"**

같은 3-broker 클러스터에서 acks=1과 acks=all을 각각 실측했다.

| acks | 처리량 | 평균 지연 |
|---|---|---|
| `1` (리더만) | ~26,500 rec/s | ~219 ms |
| `all` (ISR 전원) | ~18,900 rec/s | ~467 ms |

> **[그림 5]** 처리량/지연 비교 막대 그래프

**처리량 30% 감소, 지연 2배.** follower ack 대기의 실질 비용이다.

이 숫자를 보고 나서야 우리 시스템 결정의 무게가 이해됐다. 좋아요·판매량 집계처럼 유실이 곧 DB에 남는 틀린 숫자가 되는 도메인은 이 비용을 감수해야 한다. 반면 조회수는 통계라 몇 건 빠져도 추세에 영향이 없으니 Outbox 없이 응답 확인 없이 그냥 던진다(fire-and-forget).

즉 **"내구성은 필요한 데만 낸다"**. 모든 이벤트를 acks=all로 보내는 게 답이 아니라, 도메인 성격에 맞춰 비용을 배분하는 게 실무 답이었다.

## 그래서 Outbox는 뭘 하는가

지금까지 확인한 발행 실패 창을 정리하면 세 개다.

1. broker down → `send()` 자체 실패
2. 옵션 잘못 설정 → idempotence 조용히 꺼짐
3. ISR 부족 → `NotEnoughReplicasException`

**셋 다 "Kafka에 도달하기 전"의 문제**다. Kafka는 자기 log에 도달하지 못한 이벤트는 지켜주지 않는다.

Outbox가 하는 일은 단순하다.

```
비즈니스 TX 안에서:
  1. 도메인 상태 변경 (like INSERT)
  2. outbox_events INSERT (published_at = NULL)
  → 이 두 개는 원자적으로 성공 or 실패

TX 밖에서:
  Poller(주기적으로 outbox를 훑는 스케줄러)가
  published_at IS NULL을 찾아 Kafka로 발행
  → 성공 시 published_at 갱신
  → 실패 시 다음 주기에 재시도
```

> **[그림 6]** broker down → outbox 누적 → 복구 후 Poller 재발행 흐름

발행이 실패해도 이벤트는 이미 DB 안에 있다. broker가 복구되면 Poller가 다시 발행한다.

### 진짜로 흡수되는지 확인

broker를 내린 상태에서 좋아요를 20건 눌렀다.

- `like` 테이블: 20건 INSERT
- `outbox_events`: 20건 INSERT (전부 `published_at = NULL`)
- Kafka: 0건

broker를 복구했다. 5초 뒤 Poller가 돌자:

- `outbox_events`: 20건 모두 `published_at` 채워짐
- Kafka: 20건 발행
- Consumer 처리 후 `like_count = 20`

**유실 없음.** Outbox가 ISR 부족까지 흡수한 셈이다.

### Outbox가 대체하지 않는 것

여기서 조심해야 하는 지점이 있다. Outbox는 **발행 실패**를 잡을 뿐이다. 이것만으로 모든 문제가 해결되진 않는다.

- **소비 실패** (Consumer 쪽에서 DB down, 코드 버그) → DLT (Dead Letter Topic)
- **중복 발행** (Poller가 발행 성공 후 published_at 갱신 전 크래시) → Consumer의 `event_handled` 멱등 처리

즉 Outbox는 "발행 전 유실"이라는 **특정 지점**만 담당한다. 이 개념을 이해하고 나서야 왜 Outbox와 DLT와 `event_handled`가 다 있어야 하는지 감이 왔다. 세 도구가 각자 다른 실패 창을 막고 있다.

## 정리하며

이번 실험에서 남긴 배움 세 가지.

**"Kafka는 이벤트를 안 잃는다"는 프레이밍이 부정확했다.** Kafka에 이벤트가 도달하고 나서 사라지지 않는 건 사실이다. 근데 도달 자체가 실패하는 창이 세 군데 있고, 그걸 잡는 건 Kafka가 아니라 애플리케이션의 몫이었다. Outbox는 그 몫을 DB 트랜잭션으로 옮겨서 흡수하는 도구다.

**옵션 미신을 조심해야 한다.** `acks=0`은 "broker down에도 성공"이 아니고, `idempotence=true` 설정만으로 idempotent producer가 되는 것도 아니었다. `acks=all`과 `min.insync.replicas`도 함께 봐야 진짜 방어선이 된다. 옵션은 세트로 이해해야 한다.

**내구성은 공짜가 아니다.** 처리량 30%↓, 지연 2배를 감당할지 말지는 도메인이 결정한다. "무조건 안전하게"가 정답이 아니라 "어디에 얼마를 낼지"의 선택이었다.

한 줄로 요약하면 이렇다.

> "내가 지금 만들고 있는 이 이벤트는, 정말 유실돼도 괜찮은가?"

이 질문에 "아니다"라면 Outbox는 선택이 아니라 필수다. "괜찮다"라면 조회수처럼 그냥 던져도 된다. **도메인이 답을 정하고, 인프라 옵션은 그 답을 따라간다**.

Consumer 쪽 옵션(`enable.auto.commit`, `max.poll.interval.ms`, ack mode)의 함정은 이번 편에서 못 다뤘다. 다음 편에서 이어 쓸 예정이다.
