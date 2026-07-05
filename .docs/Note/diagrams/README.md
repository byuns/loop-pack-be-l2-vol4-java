# TechNote 첨부 그림 (Excalidraw)

블로그 본문의 `[그림 N]` 자리에 붙일 6개 그림 스켈레톤.

## 파일 목록

| # | 파일 | 어느 자리에 |
|---|---|---|
| 1 | `01-send-fail-sequence.excalidraw` | 첫 번째 실험 — broker down 시 send() 실패 |
| 2 | `02-silent-disable.excalidraw` | 두 번째 실험 — acks=0 + idempotence=true silent disable |
| 3 | `03-combined-vs-dedicated.excalidraw` | 세 번째 실험 — combined vs dedicated controller 구성 |
| 4 | `04-broker-status-result.excalidraw` | 세 번째 실험 — broker 상태별 발행 결과 (RF=3, min=2) |
| 5 | `05-throughput-latency-chart.excalidraw` | acks=all의 가격 — 처리량/지연 비교 막대 그래프 |
| 6 | `06-outbox-absorb-flow.excalidraw` | Outbox가 흡수하는 것 — broker down→누적→복구 흐름 |

## 여는 방법

1. https://excalidraw.com 접속
2. 좌상단 메뉴 → "Open" → 해당 `.excalidraw` 파일 선택
3. 색·선·아이콘 자유롭게 다듬기
4. 좌상단 메뉴 → "Export image" → PNG 또는 SVG로 저장

## 다듬을 때 참고

- **색 팔레트**: 파랑(안전/정상), 빨강(실패/거부), 초록(성공/복구), 노랑(경고), 회색(중립)
- **폰트**: fontFamily 1(Virgil, 손그림)이 기본. 로그·코드는 fontFamily 3(Cascadia, mono)로 이미 지정됨
- **그림 크기**: 블로그 본문 폭 1000~1200px에 맞춰 export 시 조절
- **아이콘 추가**: 우측 상단 "Library" → Kafka/DB/Server 검색 가능

## 각 그림 의도 요약

**그림 1** — broker가 죽어 있으면 acks 무관하게 send() 실패한다는 걸 한눈에.
Producer(파랑) → send() 화살표 → Broker(빨강, DOWN) X 마크. 아래 설명 캡션.

**그림 2** — 잘못된 옵션 조합이 조용히 꺼지는 순간.
좌측 config YAML(파랑) → "앱 시작" 화살표 → 우측 로그(노랑, `enable.idempotence = false` 강조). 아래 경고 캡션.

**그림 3** — 3-broker 세팅에서 combined는 왜 안 되고 dedicated는 왜 되는가.
좌측 Combined 모드(3노드 중 2대 DOWN, 쿼럼 붕괴). 우측 Dedicated(controller 살아 있음, broker 2대만 DOWN). 결과 비교.

**그림 4** — RF=3, min=2에서 broker 상태별 발행 결과 3행 카드.
정상(초록, ISR=3 성공) → 1대 down(초록, ISR=2 성공) → 2대 down(빨강, ISR=1 거부).

**그림 5** — acks=1 vs acks=all 실측 막대.
왼쪽 처리량: 26,500(파랑) vs 18,900(빨강) — "30% 감소"
오른쪽 지연: 219(파랑) vs 467(빨강) — "2배 증가"

**그림 6** — Outbox 흡수 흐름 5단계.
① broker DOWN(빨강) → ② outbox 20건 누적(노랑) → ③ broker UP(초록) → ④ Poller 재발행(파랑) → ⑤ Kafka 20건 도착(초록).

## 다듬는 순서 팁

1. 6개 다 Excalidraw에 열어보고 전체 톤 확인
2. 어색한 요소(선 굵기, 텍스트 위치, 화살표 방향) 조정
3. 필요하면 Excalidraw Library에서 아이콘 추가 (Kafka 로고, DB 아이콘 등)
4. 각 그림을 PNG(투명 배경)로 export
5. 블로그 본문의 `[그림 N]` 자리에 순서대로 삽입
