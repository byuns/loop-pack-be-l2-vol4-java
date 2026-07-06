package com.loopers.queue.domain;

/**
 * 예상 대기 시간과 다음 조회 권장 간격을 계산한다.
 * 처리량은 스케줄러 설정값 기반의 정적 계산 — 스케줄러가 멈추면 예상 시간이 실제와 어긋나는 한계가 있다.
 */
public class WaitTimeCalculator {

    private static final long POLL_LONG_THRESHOLD_SECONDS = 60;
    private static final long POLL_SHORT_THRESHOLD_SECONDS = 10;
    private static final long POLL_LONG_SECONDS = 10;
    private static final long POLL_MEDIUM_SECONDS = 5;
    private static final long POLL_SHORT_SECONDS = 2;

    private final int batchSize;
    private final double admittedPerSecond;

    public WaitTimeCalculator(int batchSize, long intervalMs) {
        this.batchSize = batchSize;
        this.admittedPerSecond = batchSize * (1000.0 / intervalMs);
    }

    /**
     * @return 예상 대기 시간(초, 올림). 다음 배치 입장 예정자(position ≤ batchSize)는 0.
     */
    public long estimateWaitSeconds(long position) {
        if (position <= batchSize) {
            return 0;
        }
        return (long) Math.ceil(position / admittedPerSecond);
    }

    /**
     * @return 다음 순번 조회 권장 간격(초). 입장이 임박할수록 짧아진다.
     */
    public long pollAfterSeconds(long estimatedWaitSeconds) {
        if (estimatedWaitSeconds >= POLL_LONG_THRESHOLD_SECONDS) {
            return POLL_LONG_SECONDS;
        }
        if (estimatedWaitSeconds >= POLL_SHORT_THRESHOLD_SECONDS) {
            return POLL_MEDIUM_SECONDS;
        }
        return POLL_SHORT_SECONDS;
    }
}
