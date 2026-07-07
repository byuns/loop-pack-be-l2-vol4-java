# 09. 대기열 시스템

블랙 프라이데이처럼 순간적으로 대규모 트래픽이 몰릴 때, 주문 API 앞단에 대기열을 두어
시스템을 보호하면서 유저에게 공정한 대기 경험을 제공한다.

## 전체 흐름 (Step 1 + 2)

```
[유저] POST /queue/enter
     → Redis Sorted Set에 줄 세움 (INCR 카운터로 순번 부여)
     → "당신은 512번째입니다"

[유저] GET /queue/position (polling)
     → WAITING + 순번  →  ...  →  READY + 입장 토큰

[스케줄러] 100ms마다 실행
     → ZPOPMIN으로 앞에서 10명 꺼냄
     → 입장 토큰 발급 (Redis SET, TTL 5분)

[유저] POST /orders (Header: X-Entry-Token)
     → 토큰 검증 → 주문 처리 → 성공 시 토큰 삭제
     → 이후 흐름은 R7 이벤트 파이프라인 그대로
```

---

# 왜 대기열인가 — 전략 비교

## 1. 아무것도 없을 때

모든 요청이 그대로 주문 API로 진입한다.

**문제**
- DB 커넥션 풀 고갈 → 타임아웃 → **500 / 503 에러 폭발**
- 먼저 클릭했어도 커넥션 경쟁에서 밀리면 실패 → 불공정
- 서버가 스스로 무너짐 (의도된 거부가 아님)

```
유저 1000명 동시 요청
       ↓ (전부 진입)
주문 API ← DB 커넥션 10개 뿐
       ↓
커넥션 경쟁 → 타임아웃 → 500 / 503
```

## 2. Rate Limiting을 했을 때

초당 허용 요청 수를 설정해, 임계치를 초과한 요청은 즉시 거부한다.

**장점**
- 시스템 부하를 확실히 제한할 수 있음
- 임계치 초과 요청에 **429 (Too Many Requests)** 반환 → 의도된 거부

**단점**
- 거부당한 유저는 재시도하거나 그냥 이탈 → 주문 기회 자체를 잃음
- 먼저 클릭했어도 429를 받으면 끝 → 여전히 불공정
- "기다리면 살 수 있다"는 경험을 줄 수 없음

```
유저 1000명 동시 요청
       ↓
[Rate Limiter] — 초당 N개만 통과
       ↓              ↓
   주문 API      429 즉시 반환 (나머지)
```

## 3. 대기열을 만들었을 때

요청을 거부하지 않고 줄을 세운다. 시스템이 감당할 수 있는 속도로만 통과시킨다.

**장점**
- 진입 순서가 보장됨 → 공정
- 거부 없이 나중에라도 처리 → 주문 기회 유지
- 순번 / 예상 대기 시간을 보여주면 유저 이탈 감소

**단점**
- 구현 복잡도가 높음 (Redis, 스케줄러, 토큰 관리)
- 대기열 자체도 인프라 (Redis)에 의존

```
유저 1000명 동시 요청
       ↓
[Redis 대기열] — 순서대로 줄 세움
       ↓ (스케줄러가 N명씩 꺼냄)
토큰 발급 → 주문 API 진입
       ↓
DB가 감당 가능한 처리량으로 안정적 처리
```

## 비교 요약

|  | 아무것도 없음 | Rate Limiting | 대기열 |
|---|---|---|---|
| 초과 요청 처리 | 그대로 받다가 터짐 | 즉시 거부 (429) | 줄 세움 |
| 에러 종류 | 500 / 503 | 429 | 없음 (대기) |
| 공정성 | 낮음 (커넥션 운) | 낮음 (속도 운) | 높음 (순서 보장) |
| 유저 경험 | 에러 또는 성공 양극단 | 거부 → 이탈 | 기다리지만 예측 가능 |
| 시스템 안정성 | 낮음 | 높음 | 높음 |
| 구현 복잡도 | 낮음 | 낮음 | 높음 |

> Rate Limiting은 "초과분을 잘라낸다", 대기열은 "초과분을 나중에 처리한다".
> 주문처럼 **기회를 보존해야 하는 상황**에서는 대기열이 적합하다.
>
> 단, 둘은 양자택일이 아니다 — 대기열에도 상한을 두고 초과분은 429로 거부하므로,
> "대기열 뒤에 Rate Limiting을 한 겹 더 둔" 조합 구조다.

---

# Step 1 — Redis 기반 대기열

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | Score 방식 | Redis `INCR` 카운터 | 원자적 보장, 서버 시계 편차 무관 |
| 2 | Key 설계 | 단일 대기열 `queue:waiting` | 과제 범위상 상품/이벤트별 분리 불필요 |
| 3 | Member | userId | 유저 유일 식별, Sorted Set unique 특성 활용 |
| 4 | 중복 진입 정책 | 기존 순번 유지 (`ZADD NX`) | 새로고침/재시도에 안전, 유저 친화적 |
| 5 | API 응답 스펙 | 단일 응답 + `status` 필드 | 프론트 처리 단순화 |
| 6 | 대기열 상한 | 있음 (초과 시 429) | Redis 메모리 보호 |

### 1. 데이터 구조 — Redis Sorted Set

```
Key: "queue:waiting"
┌───────────────────────┐
│  Score  │   Member    │
├───────────────────────┤
│    1    │  user:101   │  ← 1등
│    2    │  user:202   │  ← 2등
│    3    │  user:303   │  ← 3등
└───────────────────────┘
```

- **Score**: `INCR queue:counter`로 발급받은 순번
- **Member**: userId
- 순번 조회는 `ZRANK` (0-based라 +1)

Score를 timestamp가 아닌 원자적 카운터로 한 이유:
- `System.currentTimeMillis()`는 ms 정밀도라 동시 진입 시 겹칠 수 있음
- 서버가 여러 대면 시계 편차로 순서가 뒤집힐 수 있음
- Redis `INCR`은 원자적이라 절대 겹치지 않음

### 2. 중복 진입 정책 — 기존 순번 유지

- 이미 대기 중인 유저의 재요청은 무시하고 기존 순번 반환 (`ZADD NX`)
- 새로고침/네트워크 재시도에 안전
- 티켓팅처럼 "새로고침 시 밀림" 정책은 사용하지 않음
  - 이유: 이커머스 주문은 극단적 트래픽 억제보다 유저 신뢰 유지가 우선

### 3. 대기열 상한

- `queue.max-size` (기본 100000) 초과 시 `429 Too Many Requests` 반환
- Redis 메모리 폭발로 대기열 시스템 자체가 무너지는 상황 방지

## 구현 결과

### 패키지 구성

```
com.loopers.queue
├── domain
│   ├── WaitingModel.java        도메인 모델 (userId, position)
│   ├── QueueStatus.java         WAITING / READY / NOT_IN_QUEUE
│   └── QueueRepository.java     Repository Port
├── application
│   ├── QueueFacade.java         진입 / 순번 조회 / 전체 인원 조회
│   └── WaitingInfo.java         응답 모델 (record + 팩토리)
├── infrastructure
│   └── QueueRepositoryImpl.java Redis Sorted Set + Lua Script
└── interfaces
    ├── QueueV1ApiSpec.java      Swagger 스펙
    ├── QueueV1Controller.java   REST 컨트롤러
    └── QueueV1Dto.java          요청/응답 DTO
```

### 핵심 구현: 원자적 진입 (Lua Script)

진입 시 해야 할 일은 "중복 확인 → 상한 확인 → 순번 발급 → 줄 세우기" 4가지인데,
명령을 따로 날리면 명령 사이에 다른 요청이 끼어들어 상한이 뚫리거나 순번이 중복될 수 있다.
`INCR + ZCARD + ZADD NX + ZRANK`를 하나의 Lua Script로 묶어 원자적으로 처리한다.

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

- 이미 있으면 → 기존 순번 반환 (중복 진입 방지)
- 상한 초과 → `-1` 반환 → Facade에서 429로 변환
- 그 외 → INCR 카운터로 score 발급 → ZADD → 새 순번 반환

### API

| Method | Path | 설명 | 응답 상태 |
|---|---|---|---|
| POST | `/api/v1/queue/enter` | 대기열 진입 | 200 / 400 / 429 |
| GET | `/api/v1/queue/position?userId=` | 순번 조회 | 200 / 400 |
| GET | `/api/v1/queue/size` | 전체 대기 인원 조회 | 200 |

응답은 상태가 달라도 필드 구성이 같고 `status`로 구분한다:

```json
// 대기 중
{ "status": "WAITING", "position": 3, "estimatedWaitTime": null, "token": null }

// 토큰 발급됨 (Step 2에서 채워짐)
{ "status": "READY", "position": null, "estimatedWaitTime": null, "token": "abc123..." }

// 대기열에 없음
{ "status": "NOT_IN_QUEUE", "position": null, "estimatedWaitTime": null, "token": null }

// 상한 초과 (429)
{ "meta": { "result": "FAIL", "errorCode": "Too Many Requests", "message": "대기열이 가득 찼습니다." } }
```

> `estimatedWaitTime`은 Step 3(예상 대기 시간 계산)에서 채운다.

### 테스트 커버리지

| 레이어 | 파일 | 케이스 수 | 검증 대상 |
|---|---|---|---|
| 단위 | `WaitingModelTest` | 4 | userId/position 유효성 |
| 단위 | `WaitingInfoTest` | 3 | 상태별 팩토리 |
| 통합 | `QueueFacadeIntegrationTest` | 12 | 진입·조회·상한·재진입·동시성 |
| E2E | `QueueV1ApiE2ETest` | 8+1 | HTTP 스펙 검증 (200/400/429), READY 응답 |

**동시성 테스트**
- 여러 스레드가 동시에 서로 다른 유저로 진입 → 순번 겹치지 않고 순차 부여
- 같은 유저가 동시에 여러 번 진입 → 단 하나의 순번만 부여

---

# Step 2 — 입장 토큰 & 스케줄러

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 스케줄러 주기 / 배치 크기 | 100ms / 10명 | 아래 산정 근거 참고. 입장을 시간축으로 분산해 Thundering Herd 완화 |
| 2 | 대기열에서 꺼내기 | `ZPOPMIN N` | "앞에서 N명 조회 + 삭제"가 원자적 명령 하나 |
| 3 | 토큰 TTL | 5분 | 주문서 확인 + 결제 수단 선택에 충분, 미사용 토큰의 슬롯 점유 최소화 |
| 4 | 토큰 저장 구조 | `queue:token:{userId}` = 토큰값 (TTL 5분) | userId 키 하나로 소유자 확인 + READY 분기 + 만료를 모두 해결 |
| 5 | 토큰 검증 위치 | OrderFacade 진입부 | 구조 단순, 통합 테스트 용이. 인터셉터는 대상 API가 늘어날 때 재검토 |
| 6 | 토큰 삭제 시점 | 주문 성공 시에만 삭제 | 실패(재고 부족·결제 실패) 시 TTL 내 재시도 가능 — 유저 신뢰 우선 |
| 7 | 토큰 만료 유저 | 처음부터 재진입 | 대기열에도 토큰도 없는 상태 → 다시 줄 서기 |
| 8 | 스케줄러 다중화 | 단일 인스턴스 가정 | 다중화 시 ShedLock 등 분산 락 필요 — 과제 범위 밖, 한계로 명시 |

### 배치 크기 산정 근거

```
DB 커넥션 풀:            40개 (HikariCP maximum-pool-size)
주문 API 몫:             50% → 20개 (상품 조회 등 다른 API와 풀 공유)
주문 평균 처리 시간 가정:  200ms (실측 전 보수적 가정)

초당 입장 가능 인원 = 20개 × (1000ms / 200ms) = 초당 100명
스케줄러 100ms 주기 → 배치 크기 = 100명 / 10회 = 10명
```

- 초당 100명을 1초에 한 번 몰아넣지 않고 100ms마다 10명씩 쪼개는 이유:
  같은 총량이라도 시간축으로 분산되어 주문 API로의 스파이크(Thundering Herd)가 완화된다.
- 토큰 발급 ≠ 즉시 주문: 토큰을 받은 유저는 수 초~수십 초에 걸쳐 주문하므로 실제 DB 유입은
  이보다 완만하게 퍼진다. 위 수치는 "최악의 경우(전원 즉시 주문)"에도 풀이 버티는 보수적 기준.
- 평균 처리 시간은 추후 메트릭 실측값으로 보정한다.

### 토큰 key 설계 — userId를 키로

```
Key:   queue:token:{userId}     ← 토큰이 아니라 "유저"가 키
Value: UUID 토큰값
TTL:   300초
```

userId를 키로 삼으면 세 가지가 별도 장치 없이 해결된다:

1. **소유자 확인** — 검증 시 "요청 유저의 키"를 조회해 헤더 토큰과 비교하므로 남의 토큰으로는 통과 불가
2. **만료 처리** — Redis TTL이 자동 삭제. 만료 검사 코드가 없음 ("키 없음 = 미발급 또는 만료")
3. **READY 조회** — 순번 조회 시 이 키 하나만 확인하면 토큰 발급 여부를 알 수 있음

### getPosition 상태 분기 (Step 2에서 변경 필수)

스케줄러가 `ZPOPMIN`으로 꺼낸 유저는 ZRANK가 null이 되므로, 토큰 확인 없이는
`NOT_IN_QUEUE`로 오판된다. 반드시 토큰을 먼저 확인해야 한다.

```
1. queue:token:{userId} 존재? → READY + 토큰 반환
2. ZRANK 존재?              → WAITING + 순번
3. 둘 다 없음               → NOT_IN_QUEUE
```

### 알려진 트레이드오프

- **ZPOPMIN과 토큰 SET 사이 장애**: 꺼내긴 했는데 토큰 발급 전에 서버가 죽으면 해당 유저는
  대기열·토큰 모두 없는 상태가 된다. Lua로 묶는 대신 "유저가 재진입하면 복구된다"로 허용 —
  발생 확률 대비 구현 복잡도를 낮추는 선택.
- **토큰 동시 사용**: 성공 시에만 삭제하는 정책상 같은 토큰의 동시 요청이 이론상 가능하다.
  `GETDEL`로 검증+삭제를 원자화하면 막을 수 있지만 주문 실패 시 재시도가 불가능해지는
  딜레마가 있어 유저 친화 쪽을 택했다. 주문 도메인의 재고 차감 로직이 최종 방어선.

## 구현 결과

### 추가된 구성 요소

```
com.loopers.queue
├── domain
│   ├── EntryTokenModel.java       입장 토큰 도메인 모델 (userId, token) — issue()로 UUID 발급
│   ├── EntryTokenRepository.java  토큰 저장/조회/삭제 Port (TTL 지원)
│   └── QueueRepository.java       popMin(count) 추가 — ZPOPMIN
├── application
│   ├── QueueScheduler.java        @Scheduled(100ms) → admitNextBatch() 호출
│   ├── EntryTokenValidator.java   토큰 검증(validate) / 소비(consume) — OrderFacade가 사용
│   └── QueueFacade.java           admitNextBatch() 추가, getPosition()에 READY 분기 추가
└── infrastructure
    └── EntryTokenRepositoryImpl.java  Redis SET + TTL (key: queue:token:{userId})

com.loopers.order
├── application/OrderFacade.java       createOrderWithEntryToken() — 검증 → 주문 → 성공 시 토큰 삭제
└── interfaces/OrderV1Controller.java  POST /orders에 X-Entry-Token 헤더 추가
```

### 동작 흐름

```
[스케줄러] 100ms마다 ZPOPMIN 10명 → queue:token:{userId} = UUID (TTL 5분)

[유저] GET /queue/position
     → 토큰 있음: READY + 토큰    ← 토큰을 먼저 확인해야 NOT_IN_QUEUE와 구분됨
     → ZRANK 있음: WAITING + 순번
     → 둘 다 없음: NOT_IN_QUEUE

[유저] POST /orders (X-Entry-Token 헤더)
     → userId로 저장된 토큰 조회 → 값 비교 (타인 토큰 차단)
     → 불일치/없음/만료: 403 FORBIDDEN
     → 주문 성공: 토큰 삭제 / 주문 실패: 토큰 유지 (TTL 내 재시도 가능)
```

주문 흐름의 정책은 코드 구조에 그대로 드러난다:

```java
entryTokenValidator.validate(userId, entryToken);   // ① 검증 (실패 → 403)
OrderInfo info = createOrder(...);                  // ② 주문 (여기서 예외 → 토큰 유지)
entryTokenValidator.consume(userId);                // ③ 성공했을 때만 삭제
```

403(FORBIDDEN)을 쓴 이유: 로그인은 이미 된 유저이고, 부족한 것은 인증이 아니라 "입장 권한"이라서.

### 설정 (application.yml)

| 키 | 값 | 의미 |
|---|---|---|
| `queue.max-size` | 100000 | 대기열 상한 (초과 시 429) |
| `queue.scheduler.enabled` | true (test 프로파일은 false) | 스케줄러 on/off |
| `queue.scheduler.interval-ms` | 100 | 실행 주기 |
| `queue.scheduler.batch-size` | 10 | 회당 입장 인원 (산정 근거는 위 참고) |
| `queue.token.ttl-seconds` | 300 | 토큰 TTL 5분 |
| `queue.entry-token.required` | true | false 시 검증 우회 — Redis 장애 대비 kill switch (Graceful Degradation) |

### 테스트 커버리지 (Step 2 추가분)

| 레이어 | 파일 | 케이스 수 | 검증 대상 |
|---|---|---|---|
| 단위 | `EntryTokenModelTest` | 6 | 유효성 검증, UUID 발급·유일성 |
| 통합 | `QueueAdmissionIntegrationTest` | 8 | 배치 크기·순서·TTL·READY 분기 |
| 통합 | `OrderEntryTokenIntegrationTest` | 5 | 검증·삭제·실패 시 유지·만료·타인 토큰 |
| E2E | `OrderEntryTokenE2ETest` | 3 | X-Entry-Token 200/403 |
| E2E | `QueueV1ApiE2ETest` (추가) | 1 | position 조회 READY + 토큰 포함 |

> 테스트 환경 주의: 스프링 테스트 컨텍스트 캐시에 남은 타 컨텍스트의 스케줄러가 공유 Redis
> 대기열을 비워 순번 검증을 오염시키는 문제가 있어, test 프로파일에서는 스케줄러를 끄고
> `admitNextBatch()` 수동 호출로 검증한다.

---

# Step 3 — 실시간 순번 조회 (예상 대기 시간)

## 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 처리량 산정 | 정적 — 설정값 기반 (초당 100명) | 스케줄러 처리량은 우리가 통제하는 값이라 변동 요인이 적음. 실측 방식의 복잡도 대비 이득이 작음 |
| 2 | 표시 방식 | 초 단위 정수, 올림 | 기존 응답 예시와 일치. 분 환산은 프론트 몫 |
| 3 | 다음 배치 입장 예정자 | position ≤ batch-size(10) → 0초 | "곧 입장" 표시용 |
| 4 | pollAfter 구간 기준 | 예상 대기 시간 기준 | 입장이 임박할수록 자주 조회. 배치 설정이 바뀌어도 자동 반영 |
| 5 | READY 응답 | expiresAt 포함 (Redis TTL 조회) | "5분 안에 주문하세요" 카운트다운 제공 |

### 계산 공식

```
초당 처리량 = batch-size × (1000 / interval-ms) = 10 × 10 = 100명/초

estimatedWaitTime(초) = position ≤ batch-size 이면 0
                        아니면 ceil(position / 초당 처리량)

예: position 342 → ceil(3.42) = 4초
```

한계 (문서화): 스케줄러가 멈추면 예상 시간이 실제와 어긋난다. 처리량이 설정값과 크게
달라지는 운영 상황이 생기면 실측 기반으로 전환을 검토한다.

### Polling 부하와 pollAfter

대기 10만 명이 2초마다 polling하면 초당 5만 요청 — Redis(ZRANK, O(log N))보다 톰캣 워커
스레드(200개)가 먼저 병목이 된다. 응답에 다음 조회 권장 간격을 포함해 총량을 줄인다:

| 예상 대기 시간 | pollAfter |
|---|---|
| 60초 이상 | 10초 |
| 10~60초 | 5초 |
| 10초 미만 | 2초 |
| READY / NOT_IN_QUEUE | null (조회 불필요) |

서버가 강제할 수는 없는 클라이언트 가이드라는 한계가 있다.

### Polling vs SSE

- SSE는 순번 변화를 push할 수 있지만, 대기 인원만큼 동시 커넥션을 유지해야 해서
  비용이 대기 인원에 비례한다 (10만 명 = 커넥션 10만 개).
- 대기열은 변화가 느리고 예측 가능한 상태라, 동적 주기 Polling으로 충분하다.
  Polling은 무상태라 수평 확장이 쉽고 주기 조절로 총량을 제어할 수 있다.

## 구현 결과

### 추가된 구성 요소

```
com.loopers.queue
├── domain
│   └── WaitTimeCalculator.java   순수 자바 — 예상 대기 시간(올림)·pollAfter 계산
├── application
│   ├── WaitingInfo.java          pollAfter·expiresAt 필드 추가
│   └── QueueFacade.java          enter/getPosition에 예상 시간 계산 연결, READY에 expiresAt
├── domain/EntryTokenRepository.java   getTtl(userId) 추가 — expiresAt 계산용
└── interfaces/QueueV1Dto.java    응답에 pollAfter·expiresAt 추가
```

### 응답 예시 (최종)

```json
// 대기 중 (342번째)
{ "status": "WAITING", "position": 342, "estimatedWaitTime": 4, "pollAfter": 2,
  "token": null, "expiresAt": null }

// 토큰 발급됨
{ "status": "READY", "position": null, "estimatedWaitTime": null, "pollAfter": null,
  "token": "abc123...", "expiresAt": "2026-07-06T22:10:00+09:00" }

// 대기열에 없음
{ "status": "NOT_IN_QUEUE", "position": null, "estimatedWaitTime": null, "pollAfter": null,
  "token": null, "expiresAt": null }
```

- `POST /queue/enter` 응답에도 동일하게 estimatedWaitTime·pollAfter가 포함된다 (진입 직후부터 안내).
- READY의 expiresAt은 Redis TTL 조회(`현재 시각 + 남은 TTL`)로 계산한다.

### 테스트 커버리지 (Step 3 추가분)

| 레이어 | 파일 | 케이스 수 | 검증 대상 |
|---|---|---|---|
| 단위 | `WaitTimeCalculatorTest` | 6 | 올림 계산·0초 처리·pollAfter 구간 |
| 단위 | `WaitingInfoTest` (수정) | 3 | 상태별 필드 세팅 |
| 통합 | `QueueAdmissionIntegrationTest` (추가) | 2 | 342번째 → 4초·pollAfter 2초, expiresAt 미래 시각 |
| E2E | `QueueV1ApiE2ETest` (추가) | 2 | WAITING 응답 필드, READY + expiresAt |

---

# Nice-to-Have 확장

Must-Have 세 단계가 끝난 뒤 추가로 도전한 항목들. 각각 "왜 넣었고, 왜 지금 이 정도로만 넣었는가"를 명시한다.

## Step 4 — Thundering Herd 완화 (Jitter)

### 배경

스케줄러 배치가 시간축에 이미 분산돼 있어도(100ms/10명), 같은 100ms 틱에 발급된 10명은 여전히 사실상 동시에 주문 API를 두드린다.
Jitter는 그 안에서도 각 유저의 "노출 가능 시각"을 랜덤 지연으로 흩뿌려 주문 API 스파이크를 한 번 더 완화한다.

### 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 위치 | 토큰 발급 후 노출/검증까지의 시각 | 실제 스파이크(주문 API)를 가장 직접 완화 |
| 2 | 범위 | `[0, 500ms]` | 배치 간격 100ms의 5배 — 배치끼리 살짝 겹치는 정도로 유저 체감은 미미 |
| 3 | 강제 방식 | **서버가 검증에서도 차단** | 클라이언트 Jitter는 커스텀 클라이언트로 우회 가능 → 서버가 진짜 시간축을 결정 |
| 4 | 저장 방식 | 기존 `queue:token:{userId}` value에 `visibleAtMillis\|token` 형태로 인코딩 | 단일 GET/SET 유지, 추가 키 없음 |
| 5 | 대기 중 응답 | `WAITING` 재사용 + `position=0` | 클라이언트 로직 변경 최소, WAITING→READY 흐름 유지 |

### 구현 결과

```
com.loopers.queue
├── domain
│   └── EntryTokenModel.java           + visibleAt 필드, issue(userId, jitterMs), isVisible(now)
├── application
│   ├── EntryTokenValidator.java       visibleAt 이전 검증 시도는 FORBIDDEN
│   ├── QueueFacade.java               getPosition에서 visibility 분기, admitNextBatch에 jitter 전달
│   └── WaitingInfo.java               + pendingVisibility(remainingSeconds) 팩토리
└── infrastructure
    └── EntryTokenRepositoryImpl.java  value 형태: "{visibleAtMillis}|{token}"
```

### 동작 흐름

```
[스케줄러] ZPOPMIN → EntryTokenModel.issue(userId, 500)
     → visibleAt = now + random(0, 500ms)
     → queue:token:{userId} = "1728...|abc-uuid"

[유저] GET /queue/position
     → 토큰 있음 && visibleAt 지남   → READY + 토큰
     → 토큰 있음 && visibleAt 이전   → WAITING (position=0, estimatedWaitTime=남은 초)
     → 토큰 없음                     → 기존 ZRANK 로직

[유저] POST /orders (visibleAt 이전)
     → EntryTokenValidator.validate → FORBIDDEN
     → 유저는 정상적으로 서버 READY를 기다렸으므로 이 케이스는 실질적으로 발생 안 함 (방어선 역할)
```

### 설정

| 키 | 값 | 의미 |
|---|---|---|
| `queue.jitter.max-ms` | 500 (프로덕션), 0 (test 프로파일 기본) | 발급 후 최대 노출 지연 (ms) |

### 테스트 커버리지

| 레이어 | 파일 | 케이스 수 | 검증 대상 |
|---|---|---|---|
| 단위 | `EntryTokenModelTest` | 3 (추가) | visibleAt 유효성, issue의 지연 범위, isVisible |
| 통합 | `QueueJitterIntegrationTest` | 2 | 배치 직후 상태(WAITING/READY), 지연 경과 후 READY |
| 통합 | `OrderEntryTokenIntegrationTest` | 1 (추가) | visibleAt 이전 주문 시도 → FORBIDDEN, 토큰 유지 |

### 한계

- **효과 실측 미완**: k6로 Before(jitter=0)/After(jitter=500)의 주문 API 응답 시간 분포를 비교할 계획이었으나 Windows 소켓 이슈로 자동 실행 실패. 스크립트(`k6/queue-jitter-test.js`)는 준비됨.
- **최대 지연 500ms의 근거**: 배치 간격(100ms) × 5배 — 유저 체감 지연은 무시할 수준(0.5초)이면서 배치 내 10명이 500ms에 걸쳐 흩어짐. 최적값은 실측 후 조정.
- **클라이언트 Jitter 미도입**: 서버 강제로도 충분하다고 판단. 필요 시 클라이언트 쪽 재시도 Jitter를 추가로 얹을 수 있음.

---

## Step 5 — SSE 기반 순번 Push (Polling 병행)

### 배경

Polling으로 대기 경험은 충분히 제공되지만, "서버가 push하는" SSE 방식이 실서비스에서 어떤 트레이드오프를 갖는지 학습 목적으로 도전.
**대체가 아니라 병행** — 기존 `GET /queue/position`(Polling)은 그대로 두고, `GET /queue/stream`을 추가한다.

### 설계 결정

| # | 항목 | 결정 | 근거 |
|---|---|---|---|
| 1 | 대체 vs 병행 | **병행** | Polling은 무상태·수평 확장 유리. SSE는 학습·데모용으로 추가 |
| 2 | 규모 | 데모 (단일 인스턴스, 로컬 Map) | 다중 인스턴스 대응은 Redis Pub/Sub 등 추가 인프라 필요 — 범위 밖 |
| 3 | 트리거 | **스케줄러 push** | 배치 처리 직후 상태 변화가 실제로 있는 시점 → SSE 이점을 살림 |
| 4 | broadcast 대상 | 등록된 emitter 전원 | 데모 규모(수십~수백)면 감당 가능. 대규모는 "변화 있는 유저만" 최적화 필요 |
| 5 | Jitter와 상호작용 | broadcast에서도 `getPosition()`을 그대로 재사용 | visibleAt 이전 유저는 SSE로도 `position` 이벤트만 받음 (일관성) |

### 구현 결과

```
com.loopers.queue
├── application
│   ├── SsePublisher.java       Map<Long, SseEmitter> 관리, broadcast(statusProvider)
│   ├── QueueFacade.java        + subscribe(userId), broadcastToSubscribers()
│   └── QueueScheduler.java     admitNextBatch() 후 broadcastToSubscribers() 호출
└── interfaces
    └── QueueV1Controller.java  + GET /queue/stream?userId= (text/event-stream)
```

### 이벤트 스펙

```
event: position               ← WAITING 상태 (스트림 유지)
data: {"status":"WAITING","position":342,"estimatedWaitTime":4,"pollAfter":2,...}

event: ready                  ← READY 도달 (전송 후 emitter.complete)
data: {"status":"READY","token":"abc123...","expiresAt":"..."}

event: error                  ← NOT_IN_QUEUE (전송 후 complete)
data: {"message":"대기열에 없는 유저입니다."}
```

### 동작 흐름

```
[유저] GET /queue/stream?userId=1
     → SsePublisher.subscribe(1) → emitter 생성 + Map 등록
     → 즉시 첫 이벤트 push (현재 상태)

[스케줄러] admitNextBatch() → broadcastToSubscribers()
     → Map 전원에게 getPosition(userId) 결과를 이벤트로 push
     → READY 유저는 이벤트 전송 후 complete + Map 제거
     → WAITING 유저는 스트림 유지 (다음 배치까지 대기)

[유저] 연결 끊김 (탭 닫기 등)
     → onError/onTimeout 콜백에서 Map 자동 정리
```

### 연결 관리

- Timeout **30분** (`new SseEmitter(1_800_000L)`)
- `onCompletion` / `onTimeout` / `onError` 3종 콜백에서 Map 제거
- **같은 유저가 재구독하면 이전 emitter를 `complete()` 처리하고 새 것으로 대체**

### 테스트 커버리지

| 레이어 | 파일 | 케이스 수 | 검증 대상 |
|---|---|---|---|
| 단위 | `SsePublisherTest` | 4 | 구독 관리, 상태별 broadcast, 재구독 시 이전 emitter 정리, 다중 유저 개별 처리 |

### 한계

- **다중 인스턴스 불가**: Map이 서버 로컬. 유저가 서버 A에 붙었는데 스케줄러가 서버 B에서 돌면 push 도달 안 함. 실서비스는 Redis Pub/Sub이나 Kafka 브로드캐스트 필요.
- **커넥션 유지 비용**: 톰캣 워커 스레드 200개 기준 SSE 커넥션이 스레드를 잡는다 → 200명이 한계. Reactive/WebFlux로 가면 수만 명 가능하지만 프로젝트 스택 전체 변경 필요 → 범위 밖.
- **broadcast 부하**: 매 배치(100ms)마다 등록 유저 전원에 대해 `getPosition()` 호출 → Redis 왕복 N번. 대규모에서는 "변화가 있는 유저만" 골라내는 최적화 필요.

### Polling vs SSE — 재확인

| | Polling (기존) | SSE (신규) |
|---|---|---|
| 서버 상태 | 무상태 | Map 유지 (커넥션 상태) |
| 확장성 | 수평 확장 쉬움 | 서버당 커넥션 상한, 다중화 시 Pub/Sub 필요 |
| 실시간성 | 2~10초 지연 | 즉시 (배치 push) |
| 프론트 구현 | 단순 (setInterval) | EventSource API |
| 이번 선택 | 프로덕션 기본 | 학습·데모, 병행 옵션 |

---

# 진행 현황

## Step 1 체크리스트

- [x] Redis Sorted Set 기반 대기열 진입 API (`POST /queue/enter`)
- [x] 순번 조회 API (`GET /queue/position`)
- [x] userId 기반 중복 진입 방지 (ZADD NX + Lua)
- [x] 전체 대기 인원 조회 (`GET /queue/size`)

## Step 2 체크리스트

- [x] 스케줄러가 주기적으로 대기열에서 N명을 꺼내 입장 토큰 발급 (100ms / 10명)
- [x] 토큰 TTL 설정 (5분)
- [x] 주문 API 진입 시 토큰 검증 (userId 기준 조회로 타인 토큰 차단)
- [x] 주문 완료 후 토큰 삭제 (실패 시 유지 — TTL 내 재시도 허용)
- [x] 처리량 기준으로 스케줄러 배치 크기 산정 근거 문서화 (위 "배치 크기 산정 근거")
- [x] 토큰 발급 시 순번 조회 응답에 토큰 포함 (Step 3 항목 선반영)

## Step 3 체크리스트

- [x] 예상 대기 시간 계산 로직 (정적 처리량 기반, 올림)
- [x] Polling 기반 순번 + 예상 대기 시간 응답
- [x] 토큰 발급 시 순번 조회 응답에 토큰 포함 (Step 2에서 선반영)
- [x] Polling 부하 고려 — pollAfter로 조회 주기 동적 안내

## Nice-to-Have (이번 주 도전 항목)

- [x] **SSE 기반 실시간 순번 Push** — 병행 방식으로 `GET /queue/stream` 추가 (Step 5)
- [x] **Polling 주기 동적 조절** — 예상 대기 시간 구간별 pollAfter 응답 (10/5/2초)
- [x] **Redis 장애 시 Fallback** — `queue.entry-token.required` kill switch로 검증 우회 가능
- [x] **Thundering Herd 완화 (Jitter)** — 배치 분산(100ms/10명) + 노출 시각 0~500ms 랜덤 지연 (Step 4)
- [ ] **Jitter 효과 실측** — k6 스크립트(`k6/queue-jitter-test.js`) 준비됨. Windows 소켓 이슈로 자동 실행 실패, 로컬 실행 후 결과 반영 예정
