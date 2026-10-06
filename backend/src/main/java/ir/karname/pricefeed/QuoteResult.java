package ir.karname.pricefeed;

import java.math.BigDecimal;

/** One mapping's outcome: the number read ({@code raw}) and the Toman price, or why it failed. */
public record QuoteResult(String commodity, String path, BigDecimal raw, BigDecimal priceToman, String error) {

    static QuoteResult ok(PriceMapping m, BigDecimal raw, BigDecimal priceToman) {
        return new QuoteResult(m.commodity(), m.path(), raw, priceToman, null);
    }

    static QuoteResult failed(PriceMapping m, String error) {
        return new QuoteResult(m.commodity(), m.path(), null, null, error);
    }

    public boolean ok() {
        return error == null;
    }
}
