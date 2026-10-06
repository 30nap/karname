package ir.karname.commodity;

import java.math.BigDecimal;
import java.time.Instant;

/** A known price of one commodity unit in Toman. */
public record PricePoint(long commodityId, BigDecimal priceToman, Instant pricedAt, String source, boolean personal) {

    public static PricePoint toman(long commodityId) {
        return new PricePoint(commodityId, BigDecimal.ONE, Instant.EPOCH, "FIXED", false);
    }
}
