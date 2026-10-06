package ir.karname.commodity;

import java.time.Duration;

public enum CommodityKind {
    TOMAN(null),
    FIAT(Duration.ofDays(2)),
    GOLD(Duration.ofDays(2)),
    COIN(Duration.ofDays(2)),
    CRYPTO(Duration.ofDays(2)),
    SECURITY(Duration.ofDays(7)),
    PROPERTY(Duration.ofDays(180)),
    VEHICLE(Duration.ofDays(180)),
    OTHER(Duration.ofDays(90));

    private final Duration staleAfter;

    CommodityKind(Duration staleAfter) {
        this.staleAfter = staleAfter;
    }

    /** How old a price may be before the UI warns that it is out of date; null = never priced. */
    public Duration staleAfter() {
        return staleAfter;
    }
}
