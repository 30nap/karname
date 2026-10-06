package ir.karname.pricefeed;

/** How a source is read: the Nobitex market API, or any JSON API through configured paths. */
public enum PriceSourceKind {
    NOBITEX,
    JSON
}
