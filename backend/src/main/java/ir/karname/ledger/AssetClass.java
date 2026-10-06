package ir.karname.ledger;

import ir.karname.commodity.CommodityKind;

/** Asset classes for allocation, in the fixed order the charts color them. */
public enum AssetClass {
    TOMAN, FIAT, CRYPTO, GOLD, SECURITY, PROPERTY, OTHER;

    public static AssetClass of(CommodityKind kind) {
        return switch (kind) {
            case TOMAN -> TOMAN;
            case FIAT -> FIAT;
            case CRYPTO -> CRYPTO;
            case GOLD, COIN -> GOLD;
            case SECURITY -> SECURITY;
            case PROPERTY, VEHICLE -> PROPERTY;
            case OTHER -> OTHER;
        };
    }
}
