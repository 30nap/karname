package ir.karname.pricefeed;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ready-made sources: the address, units and paths of services whose answers we know, so an
 * administrator only switches one on (and enters a key where the service asks for one). Every
 * field stays editable afterwards, so a service that changes its format is fixed from the settings.
 */
public final class PriceSourcePresets {

    private PriceSourcePresets() {
    }

    /** What the preset publishes, for grouping in the picker. */
    public enum Coverage {
        CRYPTO, GOLD_CURRENCY, ALL
    }

    /**
     * @param keyHelp where to get a key, in Persian; null when no key is needed
     * @param note    Persian caveats shown in the picker (quotas, reliability)
     */
    public record Preset(String id, String name, String description, Coverage coverage, PriceSourceKind kind, String url, PriceUnit unit,
            Map<String, String> headers, List<PriceMapping> mappings, int intervalMinutes, String website, String keyHelp, String note) {

        public boolean requiresKey() {
            return keyHelp != null;
        }
    }

    private static PriceMapping m(String commodity, String path) {
        return new PriceMapping(commodity, path, null, null);
    }

    private static PriceMapping m(String commodity, String path, String multiplier, PriceUnit unit) {
        return new PriceMapping(commodity, path, multiplier == null ? null : new BigDecimal(multiplier), unit);
    }

    static final List<Preset> ALL = List.of(
            new Preset("NOBITEX", "نوبیتکس", "قیمت تتر و رمزارزها در بازار ریالی نوبیتکس؛ بدون کلید. تتر به تومان تقریب خوبی از دلار آزاد است.",
                    Coverage.CRYPTO, PriceSourceKind.NOBITEX, NobitexPriceProvider.DEFAULT_URL, PriceUnit.RIAL, Map.of(),
                    List.of(m("USDT", "usdt"), m("BTC", "btc"), m("ETH", "eth"), m("TON", "gram")), 30, "https://nobitex.ir", null,
                    "تون‌کوین از تیر ۱۴۰۵ با نام گرم (gram) معامله می‌شود."),
            // one answer with every market, keyed by symbol; TMN markets are in Toman (USDT markets in USDT)
            new Preset("WALLEX", "والکس", "قیمت تتر و رمزارزها در بازار تومانی والکس؛ بدون کلید.",
                    Coverage.CRYPTO, PriceSourceKind.JSON, "https://api.wallex.ir/v1/markets", PriceUnit.TOMAN, Map.of(),
                    List.of(m("USDT", "/result/symbols/USDTTMN/stats/lastPrice"), m("BTC", "/result/symbols/BTCTMN/stats/lastPrice"),
                            m("ETH", "/result/symbols/ETHTMN/stats/lastPrice"), m("TON", "/result/symbols/GRAMTMN/stats/lastPrice")),
                    30, "https://wallex.ir", null, null),
            // a list of {symbol, price}; IRT markets are in Toman
            new Preset("BITPIN", "بیت‌پین", "قیمت تتر و رمزارزها در بازار تومانی بیت‌پین؛ بدون کلید.",
                    Coverage.CRYPTO, PriceSourceKind.JSON, "https://api.bitpin.ir/api/v1/mkt/tickers/", PriceUnit.TOMAN, Map.of(),
                    List.of(m("USDT", "/[symbol=USDT_IRT]/price"), m("BTC", "/[symbol=BTC_IRT]/price"), m("ETH", "/[symbol=ETH_IRT]/price")),
                    30, "https://bitpin.ir", null, "اگر نشانی جواب نداد، api.bitpin.org را امتحان کنید."));

    public static List<Preset> all() {
        return ALL;
    }

    public static Optional<Preset> find(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
