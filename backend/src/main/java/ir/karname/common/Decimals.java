package ir.karname.common;

import java.math.BigDecimal;

/**
 * Refuses numbers whose size alone makes arithmetic expensive. "1E-100000000" is a short JSON
 * token, but rounding it to eight decimals builds a power of ten with a hundred million digits;
 * every amount, price and quantity the app handles is far inside these bounds.
 */
public final class Decimals {

    public static final int MAX_SCALE = 30;
    public static final int MAX_PRECISION = 60;

    private Decimals() {
    }

    /** Uses only the scale and digit count, so even an extreme value is checked cheaply. */
    public static boolean isReasonable(BigDecimal value) {
        return value.scale() <= MAX_SCALE && value.scale() >= -MAX_SCALE && value.precision() <= MAX_PRECISION;
    }
}
