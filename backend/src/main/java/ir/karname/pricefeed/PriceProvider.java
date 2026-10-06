package ir.karname.pricefeed;

import java.util.List;

/** Port to an outside price publisher. Returns one result per mapping, in order. */
public interface PriceProvider {

    PriceSourceKind kind();

    /** @throws PriceFetchException when the source as a whole cannot be read */
    List<QuoteResult> fetch(SourceConfig config);
}
