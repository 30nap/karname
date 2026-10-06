package ir.karname.goal;

import ir.karname.common.jalali.JalaliMonth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Pure projection math for goals: trend of month-end values, time to target, and required pace. */
final class GoalProjection {

    /** ETAs further than this are reported as unknown. */
    static final int MAX_MONTHS = 600;

    private GoalProjection() {
    }

    /**
     * Least-squares slope (units per month) of month-end values; {@code values[i]} is i months
     * after the first point, null where unknown. Needs at least three known points.
     */
    static BigDecimal monthlyTrend(List<BigDecimal> values) {
        int n = 0;
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null) {
                n++;
                sumX = sumX.add(BigDecimal.valueOf(i));
                sumY = sumY.add(values.get(i));
            }
        }
        if (n < 3) {
            return null;
        }
        BigDecimal meanX = sumX.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_EVEN);
        BigDecimal meanY = sumY.divide(BigDecimal.valueOf(n), 8, RoundingMode.HALF_EVEN);
        BigDecimal covariance = BigDecimal.ZERO;
        BigDecimal variance = BigDecimal.ZERO;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null) {
                BigDecimal dx = BigDecimal.valueOf(i).subtract(meanX);
                covariance = covariance.add(dx.multiply(values.get(i).subtract(meanY)));
                variance = variance.add(dx.multiply(dx));
            }
        }
        return covariance.divide(variance, 8, RoundingMode.HALF_EVEN);
    }

    /** Whole months needed to cover {@code remaining} at {@code trend} per month; null if never (or too far). */
    static Integer monthsToGoal(BigDecimal remaining, BigDecimal trend) {
        if (remaining.signum() <= 0) {
            return 0;
        }
        if (trend == null || trend.signum() <= 0) {
            return null;
        }
        BigDecimal months = remaining.divide(trend, 0, RoundingMode.CEILING);
        return months.compareTo(BigDecimal.valueOf(MAX_MONTHS)) > 0 ? null : months.intValueExact();
    }

    /** Months from {@code current} until the target month, at least one so a pace can be computed. */
    static int paceMonths(JalaliMonth current, JalaliMonth target) {
        return (int) Math.max(1, current.monthsUntil(target));
    }
}
