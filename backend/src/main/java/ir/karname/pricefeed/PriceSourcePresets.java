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
            // a list of {symbol, price}; IRT markets are in Toman. Only Tether: BTC_IRT and ETH_IRT stopped
            // trading in 2024 and still answer with their last price (or 0), which would pass for a current one
            new Preset("BITPIN", "بیت‌پین", "قیمت تتر در بازار تومانی بیت‌پین؛ بدون کلید. پشتیبان یا محک خوبی برای تتر است.",
                    Coverage.CRYPTO, PriceSourceKind.JSON, "https://api.bitpin.ir/api/v1/mkt/tickers/", PriceUnit.TOMAN, Map.of(),
                    List.of(m("USDT", "/[symbol=USDT_IRT]/price")),
                    30, "https://bitpin.ir", null, "اگر نشانی جواب نداد، api.bitpin.org را امتحان کنید."),
            // the board tgju.org updates itself from: {"current": {slug: {"p": "2,092,950", "ts": ...}}}, all in Rial;
            // usdt-irr is a dead slug frozen since 2020, crypto-tether-irr is the live one
            new Preset("TGJU", "شبکه اطلاع‌رسانی طلا و ارز (TGJU)",
                    "دلار و ارزهای بازار آزاد، طلا، آب‌شده، سکه و تتر به ریال؛ بدون کلید. در دسترس از داخل و خارج ایران.",
                    Coverage.GOLD_CURRENCY, PriceSourceKind.JSON, "https://call1.tgju.org/ajax.json", PriceUnit.RIAL, Map.of(),
                    List.of(m("USD", "/current/price_dollar_rl/p"), m("EUR", "/current/price_eur/p"), m("GBP", "/current/price_gbp/p"),
                            m("AED", "/current/price_aed/p"), m("TRY", "/current/price_try/p"), m("CAD", "/current/price_cad/p"),
                            m("AUD", "/current/price_aud/p"), m("CNY", "/current/price_cny/p"),
                            m("GOLD18", "/current/geram18/p"), m("GOLD24", "/current/geram24/p"), m("MESGHAL", "/current/mesghal/p"),
                            m("COIN_EMAMI", "/current/sekee/p"), m("COIN_BAHAR", "/current/sekeb/p"), m("COIN_HALF", "/current/nim/p"),
                            m("COIN_QUARTER", "/current/rob/p"), m("COIN_GERAMI", "/current/gerami/p"),
                            m("USDT", "/current/crypto-tether-irr/p")),
                    30, "https://www.tgju.org", null,
                    "سرویس رسمی و مستند نیست. اگر call1 جواب نداد، call2.tgju.org یا call3.tgju.org را امتحان کنید یا یکی را منبع پشتیبان کنید."),
            // /latest/ answers every item at once: {"usd_sell": {"value": "192600", ...}}, in Toman, coins and melted gold
            // in thousands of Toman; the free key allows 120 requests a month, so one every eight hours
            new Preset("NAVASAN", "نوسان", "دلار و ارزهای بازار تهران، طلا، آب‌شده و سکه به تومان؛ کلید رایگان.",
                    Coverage.GOLD_CURRENCY, PriceSourceKind.JSON, "https://api.navasan.tech/latest/?api_key={key}", PriceUnit.TOMAN, Map.of(),
                    List.of(m("USD", "/usd_sell/value"), m("EUR", "/eur/value"), m("GBP", "/gbp/value"), m("AED", "/aed/value"),
                            m("TRY", "/try/value"), m("CAD", "/cad/value"), m("AUD", "/aud/value"), m("CNY", "/cny/value"),
                            m("GOLD18", "/18ayar/value"), m("MESGHAL", "/abshodeh/value", "1000", null),
                            m("COIN_EMAMI", "/sekkeh/value", "1000", null), m("COIN_BAHAR", "/bahar/value", "1000", null),
                            m("COIN_HALF", "/nim/value", "1000", null), m("COIN_QUARTER", "/rob/value", "1000", null),
                            m("COIN_GERAMI", "/gerami/value", "1000", null)),
                    480, "https://www.navasan.tech", "کلید رایگان را از ربات تلگرام ‎@navasan_contact_bot بگیرید؛ سه ماه اعتبار دارد و تمدید می‌شود.",
                    "کلید رایگان ۱۲۰ درخواست در ماه دارد و هر دو ساعت به‌روز می‌شود؛ آزمایش و دریافت دستی هم از آن کم می‌کنند. "
                            + "سکه‌ها و آب‌شده به هزار تومان اعلام می‌شوند و ضریب ۱۰۰۰ دارند. ممکن است از داخل ایران در دسترس نباشد."));

    public static List<Preset> all() {
        return ALL;
    }

    public static Optional<Preset> find(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
