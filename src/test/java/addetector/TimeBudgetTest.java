package addetector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeBudgetTest {

    @Test
    void softDeadlineComesSixtySecondsBeforeHard() {
        long now = System.nanoTime();
        // 25분 예산 중 24분 30초가 지난 시점: 새 방문은 멈추지만 아직 끊지는 않는다.
        TimeBudget late = new TimeBudget(Duration.ofMinutes(25), now - Duration.ofSeconds(24 * 60 + 30).toNanos());
        assertTrue(late.softExpired());
        assertFalse(late.hardExpired());
        assertTrue(late.millisToHard() > 25_000 && late.millisToHard() <= 30_000);

        TimeBudget early = new TimeBudget(Duration.ofMinutes(25), now - Duration.ofMinutes(20).toNanos());
        assertFalse(early.softExpired());

        TimeBudget over = new TimeBudget(Duration.ofMinutes(25), now - Duration.ofMinutes(26).toNanos());
        assertTrue(over.hardExpired());
        assertTrue(over.millisToHard() == 0);
    }

    @Test
    void shortBudgetsKeepAProportionalMargin() {
        long now = System.nanoTime();
        // 10초 예산: 여유는 60초가 아니라 예산의 1/5(2초)
        TimeBudget budget = new TimeBudget(Duration.ofSeconds(10), now - Duration.ofSeconds(7).toNanos());
        assertFalse(budget.softExpired());
        TimeBudget later = new TimeBudget(Duration.ofSeconds(10), now - Duration.ofMillis(8_500).toNanos());
        assertTrue(later.softExpired());
        assertFalse(later.hardExpired());
    }

    @Test
    void defaultStaysUnderContestLimit() {
        assertTrue(TimeBudget.DEFAULT.compareTo(Duration.ofMinutes(30)) < 0);
    }
}
