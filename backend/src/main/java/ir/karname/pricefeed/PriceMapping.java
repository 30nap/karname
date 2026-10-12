package ir.karname.pricefeed;

import java.math.BigDecimal;

/**
 * What a source publishes for one commodity: for Nobitex {@code path} is the coin symbol (usdt,
 * btc…); for JSON sources it is a JSON Pointer such as {@code /data/gold18/price}, where a segment
 * may pick a list element by a field ({@code /currency/[symbol=USD]/price}). The value is
 * multiplied by {@code multiplier} (e.g. to turn a price per mesghal into a price per gram), when
 * given, and read in {@code unit} when it differs from the source's.
 */
public record PriceMapping(String commodity, String path, BigDecimal multiplier, PriceUnit unit) {

    public PriceMapping(String commodity, String path, BigDecimal multiplier) {
        this(commodity, path, multiplier, null);
    }

    PriceUnit unitOr(PriceUnit fallback) {
        return unit == null ? fallback : unit;
    }
}
