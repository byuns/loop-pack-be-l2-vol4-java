# 09. 대기열 시스템

블랙 프라이데이처럼 순간적으로 대규모 트래픽이 몰릴 때, 주문 API 앞단에 대기열을 두어
시스템을 보호하면서 유저에게 공정한 대기 경험을 제공한다.

## 전체 흐름

```
[유저] POST /queue/enter
     → Redis Sorted Set에 줄 세움 (INCR 카운터로 순번 부여)

[유저] GET /queue/position (polling) 또는 GET /queue/stream (SSE)
     → WAITING + 순번  →  ...  →  READY + 입장 토큰

[스케줄러] 100ms마다 실행
     → ZPOPMIN으로 앞에서 10명 꺼냄
     → 입장 토큰 발급 (Redis SET, TTL 5분, Jitter visibleAt 부여)

[유저] POST /orders (Header: X-Entry-Token)
     → 토큰 검증 → 주문 처리 → 성공 시 토큰 삭제
```

---

# 왜 대기열인가 — 전략 비교

|  | 아무것도 없음 | Rate Limiting | 대기열 |
|---|---|---|---|
| 초과 요청 처리 | 그대로 받다가 터짐 | 즉시 거부 (429) | 줄 세움 |
| 에러 종류 | 500 / 503 | 429 | 없음 (대기) |
| 공정성 | 낮음 (커넥션 운) | 낮음 (속도 운) | 높음 (순서 보장) |
| 유저 경험 | 에러/성공 양극단 | 거부 → 이탈 | 기다리지만 예측 가능 |
| 구현 복잡도 | 낮음 | 낮음 | 높음 |

> Rate Limiting은 "초과분을 잘라낸다", 대기열은 "초과분을 나중에 처리한다".
> 주문처럼 **기회를 보존해야 하는 상황**에서는 대기열이 적합하다.
> 단, 둘은 양자택일이 아니다 — 대기열에도 상한을 두고 초과분은 429로 거부한다.

---

# Step 1 — Redis 기반 대기열

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | Score 방식 | Redis `INCR` 카운터 | 원자적 보장, 서버 시계 편차 무관 |
| 2 | Key | 단일 `queue:waiting` | 과제 범위상 상품/이벤트별 분리 불필요 |
| 3 | Member | userId | Sorted Set unique 특성 활용 |
| 4 | 중복 진입 | 기존 순번 유지 (`ZADD NX`) | 새로고침/재시도에 안전 |
| 5 | 대기열 상한 | 있음 (초과 시 429) | Redis 메모리 보호 |

Score를 timestamp가 아닌 `INCR`로 한 이유: ms 정밀도로는 동시 진입이 겹칠 수 있고, 다중 서버에서는 시계 편차로 순서가 뒤집힐 수 있음. `INCR`은 원자적이라 절대 겹치지 않음.

## 핵심 구현 — 원자적 진입 (Lua Script)

"중복 확인 → 상한 확인 → 순번 발급 → 줄 세우기" 네 명령 사이 다른 요청이 끼어들어 상한이 뚫리거나 순번이 중복되는 것을 막기 위해 하나의 Lua로 묶었다.

```lua
if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
  return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1
end
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
  return -1
end
local score = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], score, ARGV[1])
return redis.call('ZRANK', KEYS[1], ARGV[1]) + 1
```

## API

| Method | Path | 응답 상태 |
|---|---|---|
| POST | `/api/v1/queue/enter` | 200 / 400 / 429 |
| GET | `/api/v1/queue/position?userId=` | 200 / 400 |
| GET | `/api/v1/queue/size` | 200 |

응답은 상태가 달라도 필드 구성이 같고 `status`(WAITING / READY / NOT_IN_QUEUE)로 구분한다.

---

# Step 2 — 입장 토큰 & 스케줄러

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 스케줄러 주기 / 배치 | 100ms / 10명 | 아래 산정 근거 |
| 2 | 대기열에서 꺼내기 | `ZPOPMIN N` | "앞에서 N명 조회+삭제"가 원자적 명령 하나 |
| 3 | 토큰 TTL | 5분 | 주문서 확인+결제 수단 선택에 충분, 미사용 토큰 슬롯 점유 최소화 |
| 4 | 토큰 저장 | `queue:token:{userId}` = 토큰값 | userId 키 하나로 소유자 확인·READY 분기·만료 모두 해결 |
| 5 | 토큰 검증 위치 | OrderFacade 진입부 | 구조 단순, 통합 테스트 용이 |
| 6 | 토큰 삭제 시점 | 주문 성공 시에만 | 실패(재고 부족·결제 실패) 시 TTL 내 재시도 가능 |
| 7 | 만료 유저 | 처음부터 재진입 | 대기열·토큰 모두 없는 상태 → 다시 줄 서기 |
| 8 | 스케줄러 다중화 | 단일 인스턴스 가정 | 다중화 시 ShedLock 등 분산 락 필요 — 과제 범위 밖 |

### 배치 크기 산정 근거

```
DB 커넥션 풀:           40개
주문 API 몫:            50% → 20개
주문 평균 처리 시간 가정: 200ms

초당 입장 가능 = 20 × (1000ms / 200ms) = 초당 100명
100ms 주기 → 배치 = 100 / 10 = 10명
```

1초에 100명을 몰아넣지 않고 100ms마다 10명씩 쪼개는 이유: 같은 총량이라도 시간축 분산으로 주문 API 스파이크(Thundering Herd)가 완화된다. 토큰 발급 ≠ 즉시 주문이므로 실제 DB 유입은 이보다 완만하다. 위 수치는 "최악(전원 즉시 주문)"에도 풀이 버티는 보수적 기준.

### 토큰 key 설계 — userId를 키로

userId를 키로 삼으면 세 가지가 별도 장치 없이 해결된다:
1. **소유자 확인** — 요청 유저의 키를 조회해 헤더 토큰과 비교 → 남의 토큰 통과 불가
2. **만료 처리** — Redis TTL 자동 삭제. 만료 검사 코드 불필요
3. **READY 조회** — 이 키 하나만 확인하면 발급 여부 판정 가능

### getPosition 상태 분기 (Step 2에서 변경)

`ZPOPMIN`으로 꺼낸 유저는 ZRANK가 null이 되므로, 토큰을 먼저 확인해야 READY와 NOT_IN_QUEUE를 구분할 수 있다.

```
1. queue:token:{userId} 존재? → READY + 토큰
2. ZRANK 존재?              → WAITING + 순번
3. 둘 다 없음               → NOT_IN_QUEUE
```

### 알려진 트레이드오프

- **ZPOPMIN과 토큰 SET 사이 장애**: 꺼낸 뒤 토큰 발급 전 서버가 죽으면 해당 유저는 대기열·토큰 모두 없는 상태. Lua로 묶는 대신 "재진입으로 복구"를 택했다 — 발생 확률 대비 구현 복잡도 최소화.
- **토큰 동시 사용**: 성공 시에만 삭제하는 정책상 이론상 동시 요청 가능. `GETDEL`로 원자화하면 막을 수 있지만 실패 시 재시도가 막힌다 — 유저 친화 쪽 선택. 재고 차감이 최종 방어선.

### 주문 흐름의 정책이 코드 구조에 드러난다

```java
entryTokenValidator.validate(userId, entryToken);   // ① 검증 (실패 → 403)
OrderInfo info = createOrder(...);                  // ② 주문 (예외 → 토큰 유지)
entryTokenValidator.consume(userId);                // ③ 성공했을 때만 삭제
```

403(FORBIDDEN)을 쓴 이유: 로그인된 유저에게 부족한 건 인증이 아니라 "입장 권한"이라서.

### 설정

| 키 | 값 | 의미 |
|---|---|---|
| `queue.max-size` | 100000 | 대기열 상한 (초과 시 429) |
| `queue.scheduler.enabled` | true (test는 false) | 스케줄러 on/off |
| `queue.scheduler.interval-ms` | 100 | 실행 주기 |
| `queue.scheduler.batch-size` | 10 | 회당 입장 인원 |
| `queue.token.ttl-seconds` | 300 | 토큰 TTL 5분 |
| `queue.entry-token.required` | true | false 시 검증 우회 (Redis 장애 kill switch) |

> 테스트 환경 주의: 컨텍스트 캐시에 남은 다른 컨텍스트의 스케줄러가 공유 Redis 대기열을 비워 순번 검증을 오염시키는 문제가 있어, test 프로파일에서는 스케줄러를 끄고 `admitNextBatch()`를 수동 호출로 검증한다.

---

# Step 3 — 실시간 순번 조회 (예상 대기 시간)

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 처리량 산정 | 정적 — 설정값 기반 (초당 100명) | 스케줄러 처리량은 우리가 통제, 변동 요인 적음 |
| 2 | 표시 방식 | 초 단위 정수, 올림 | 분 환산은 프론트 몫 |
| 3 | 다음 배치 예정자 | position ≤ batch-size → 0초 | "곧 입장" 표시 |
| 4 | pollAfter 구간 | 예상 대기 시간 기준 | 임박할수록 자주 조회 |
| 5 | READY 응답 | expiresAt 포함 (Redis TTL 조회) | 카운트다운 제공 |

### 계산

```
초당 처리량 = batch-size × (1000 / interval-ms) = 10 × 10 = 100명/초
estimatedWaitTime = position ≤ batch-size ? 0 : ceil(position / 100)
예: position 342 → 4초
```

**한계**: 스케줄러가 멈추면 예상 시간이 실제와 어긋난다. 운영 상황에서 처리량이 설정값과 크게 달라지면 실측 기반으로 전환 검토.

### pollAfter로 Polling 부하 완화

대기 10만 명이 2초마다 polling하면 초당 5만 요청 — Redis(ZRANK, O(log N))보다 톰캣 워커(200개)가 먼저 병목. 응답에 다음 조회 권장 간격을 담아 총량을 줄인다.

| 예상 대기 시간 | pollAfter |
|---|---|
| 60초 이상 | 10초 |
| 10~60초 | 5초 |
| 10초 미만 | 2초 |
| READY / NOT_IN_QUEUE | null |

서버가 강제할 수는 없는 클라이언트 가이드라는 한계가 있다.

---

# Step 4 — Thundering Herd 완화 (Jitter)

## 배경

스케줄러 배치가 이미 시간축에 분산돼 있어도(100ms/10명), 같은 100ms 틱에 발급된 10명은 사실상 동시에 주문 API를 두드린다. Jitter는 그 안에서도 각 유저의 "노출 가능 시각"을 랜덤 지연으로 흩뿌려 스파이크를 한 번 더 완화한다.

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 위치 | 토큰 발급 후 노출/검증까지의 시각 | 실제 스파이크(주문 API)를 가장 직접 완화 |
| 2 | 범위 | `[0, 500ms]` | 배치 간격 100ms의 5배 — 유저 체감은 미미, 10명이 500ms에 걸쳐 흩어짐 |
| 3 | 강제 방식 | **서버가 검증에서도 차단** | 클라이언트 Jitter는 커스텀 클라이언트로 우회 가능 |
| 4 | 저장 방식 | 기존 토큰 value에 `visibleAtMillis\|token` 인코딩 | 단일 GET/SET 유지, 추가 키 없음 |
| 5 | 대기 중 응답 | `WAITING` 재사용 + `position=0` | 클라이언트 로직 최소 변경, WAITING→READY 흐름 유지 |

## 동작 흐름

```
[스케줄러] ZPOPMIN → EntryTokenModel.issue(userId, 500)
     → visibleAt = now + random(0, 500ms)
     → queue:token:{userId} = "1728...|abc-uuid"

[유저] GET /queue/position
     → 토큰 있음 && visibleAt 지남   → READY + 토큰
     → 토큰 있음 && visibleAt 이전   → WAITING (position=0, estimatedWaitTime=남은 초)
     → 토큰 없음                     → 기존 ZRANK 로직

[유저] POST /orders (visibleAt 이전)
     → validate → FORBIDDEN (방어선. 정상 흐름에선 발생 안 함)
```

## 설정 & 한계

- `queue.jitter.max-ms`: 500 (프로덕션) / 0 (test 기본).
- **효과 실측 미완**: k6 스크립트(`k6/queue-jitter-test.js`)로 Before(jitter=0)/After(jitter=500) 주문 API 응답 시간 분포를 비교할 계획. 자동 실행 시 Windows 소켓 이슈로 실패, 로컬 실행 예정.
- **500ms 근거는 이론값**: 배치 간격의 5배. 최적값은 실측 후 조정.

---

# Step 5 — SSE 기반 순번 Push (Polling 병행)

## 배경

Polling으로 대기 경험은 충분하지만, "서버가 push하는" SSE가 실서비스에서 어떤 트레이드오프를 갖는지 학습 목적으로 도전. **대체가 아니라 병행** — 기존 `/queue/position`은 그대로 두고 `/queue/stream`을 추가한다.

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 대체 vs 병행 | **병행** | Polling은 무상태·수평 확장 유리, SSE는 학습·데모용 |
| 2 | 규모 | 데모 (단일 인스턴스, 로컬 Map) | 다중 인스턴스는 Pub/Sub 인프라 필요 — 범위 밖 |
| 3 | 트리거 | **스케줄러 push** | 배치 처리 직후 상태가 실제로 변한다 |
| 4 | broadcast 대상 | 등록된 emitter 전원 | 데모 규모에선 감당 가능 |
| 5 | Jitter와 상호작용 | broadcast도 `getPosition()` 재사용 | visibleAt 이전 유저는 SSE로도 `position` 이벤트만 받음 |

## 이벤트 스펙

```
event: position       ← WAITING (스트림 유지)
data: {"status":"WAITING","position":342,"estimatedWaitTime":4,...}

event: ready          ← READY (전송 후 emitter.complete)
data: {"status":"READY","token":"abc123...","expiresAt":"..."}

event: error          ← NOT_IN_QUEUE (전송 후 complete)
data: {"message":"대기열에 없는 유저입니다."}
```

## 연결 관리

- Timeout **30분** (`new SseEmitter(1_800_000L)`)
- `onCompletion` / `onTimeout` / `onError` 3종 콜백에서 Map 정리
- 같은 유저 재구독 시 이전 emitter를 `complete()` 후 새 것으로 대체

## 한계

- **다중 인스턴스 불가**: Map이 서버 로컬. 유저가 서버 A에 붙었는데 스케줄러가 서버 B에서 돌면 push 도달 안 함. 실서비스는 Redis Pub/Sub 필요.
- **커넥션 유지 비용**: 톰캣 워커 200개 기준 SSE 커넥션이 스레드를 잡음 → 200명 한계. Reactive/WebFlux로 가면 수만 명 가능하지만 스택 전체 변경.
- **broadcast 부하**: 매 배치마다 등록 유저 전원에 `getPosition()` → 대규모에선 "변화 있는 유저만" 최적화 필요.

### Polling vs SSE

| | Polling | SSE |
|---|---|---|
| 서버 상태 | 무상태 | Map 유지 |
| 확장성 | 수평 확장 쉬움 | 커넥션 상한, 다중화 시 Pub/Sub 필요 |
| 실시간성 | 2~10초 지연 | 즉시 (배치 push) |
| 이번 선택 | 프로덕션 기본 | 학습·데모, 병행 옵션 |
