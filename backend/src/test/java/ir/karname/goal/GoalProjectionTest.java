package ir.karname.goal;

import ir.karname.common.jalali.JalaliMonth;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoalProjectionTest {

    private static List<BigDecimal> values(String... values) {
        return Arrays.stream(values).map(v -> v == null ? null : new BigDecimal(v)).toList();
    }

    @Test
    void trendIsTheLeastSquaresSlope() {
        assertThat(GoalProjection.monthlyTrend(values("100", "200", "300"))).isEqualByComparingTo("100");
        assertThat(GoalProjection.monthlyTrend(values(null, null, "100", "200", "300"))).isEqualByComparingTo("100");
        assertThat(GoalProjection.monthlyTrend(values("100", "300", "200", "400"))).isEqualByComparingTo("80");
        assertThat(GoalProjection.monthlyTrend(values("500", "400", "300"))).isEqualByComparingTo("-100");
    }

    @Test
    void trendNeedsThreeKnownPoints() {
        assertThat(GoalProjection.monthlyTrend(values(null, "100", "200"))).isNull();
    }

    @Test
    void monthsToGoalRoundsUp() {
        assertThat(GoalProjection.monthsToGoal(new BigDecimal("1000"), new BigDecimal("100"))).isEqualTo(10);
        assertThat(GoalProjection.monthsToGoal(new BigDecimal("1001"), new BigDecimal("100"))).isEqualTo(11);
        assertThat(GoalProjection.monthsToGoal(BigDecimal.ZERO, null)).isZero();
        assertThat(GoalProjection.monthsToGoal(new BigDecimal("1000"), new BigDecimal("-5"))).isNull();
        assertThat(GoalProjection.monthsToGoal(new BigDecimal("1000"), null)).isNull();
        assertThat(GoalProjection.monthsToGoal(new BigDecimal("1000000"), BigDecimal.ONE)).isNull();
    }

    @Test
    void paceIsAtLeastOneMonth() {
        assertThat(GoalProjection.paceMonths(JalaliMonth.of(1405, 7), JalaliMonth.of(1406, 6))).isEqualTo(11);
        assertThat(GoalProjection.paceMonths(JalaliMonth.of(1405, 7), JalaliMonth.of(1405, 7))).isEqualTo(1);
    }
}
