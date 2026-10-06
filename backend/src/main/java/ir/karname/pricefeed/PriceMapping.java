package ir.karname.pricefeed;

import java.math.BigDecimal;

/**
 * What a source publishes for one commodity: for Nobitex {@code path} is the coin symbol (usdt,
 * btc…); for JSON sources it is a JSON Pointer such as {@code /data/gold18/price}. The value is
 * multiplied by {@code multiplier} (e.g. to turn a price per mesghal into a price per gram), when given.
 */
public record PriceMapping(String commodity, String path, BigDecimal multiplier) {
}
