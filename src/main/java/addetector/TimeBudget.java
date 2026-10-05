package addetector;

import java.time.Duration;

/**
 * 점검 시간 예산. 공모전 상한(30분)을 넘기면 시간 점수가 0점이므로 기본 25분 안에 끝낸다.
 * <ul>
 *   <li>soft 마감: 새 페이지 방문을 멈추는 시각(예산 − 60초)</li>
 *   <li>hard 마감: 하던 일을 끊고 결과를 기록하는 시각(예산)</li>
 * </ul>
 */
public final class TimeBudget {
    public static final Duration DEFAULT = Duration.ofMinutes(25);
    private static final long SOFT_MARGIN_NANOS = Duration.ofSeconds(60).toNanos();

    private final long start;
    private final long soft;
    private final long hard;

    public TimeBudget(Duration budget) {
        this(budget, System.nanoTime());
    }

    TimeBudget(Duration budget, long startNanos) {
        long total = Math.max(1, budget.toNanos());
        this.start = startNanos;
        this.hard = startNanos + total;
        // 짧은 예산에서는 여유를 예산의 1/5로 줄인다.
        this.soft = hard - Math.min(SOFT_MARGIN_NANOS, total / 5);
    }

    public boolean softExpired() {
        return System.nanoTime() - soft >= 0;
    }

    public boolean hardExpired() {
        return System.nanoTime() - hard >= 0;
    }

    /** hard 마감까지 남은 시간(밀리초). 지났으면 0. */
    public long millisToHard() {
        return Math.max(0, (hard - System.nanoTime()) / 1_000_000);
    }

    /** soft 마감까지 남은 시간(밀리초). 지났으면 0. */
    public long millisToSoft() {
        return Math.max(0, (soft - System.nanoTime()) / 1_000_000);
    }

    public double elapsedSeconds() {
        return (System.nanoTime() - start) / 1e9;
    }
}
