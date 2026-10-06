package ir.karname.pricefeed;

import java.math.BigDecimal;

/** Currency of the numbers a source publishes; prices are stored in Toman. */
public enum PriceUnit {
    RIAL,
    TOMAN;

    public BigDecimal toToman(BigDecimal value) {
        return this == RIAL ? value.movePointLeft(1) : value;
    }
}
