package ir.karname.pricefeed;

import ir.karname.commodity.PriceService;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TSETMC identifiers stay strings: their precision exceeds JavaScript's safe integer range. */
@Component
public class TsetmcClient {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");
    private final PriceHttpClient http;
    private final Clock clock;
    private final String baseUrl;

    TsetmcClient(PriceHttpClient http, Clock clock,
            @Value("${karname.stocks.base-url:https://cdn.tsetmc.com/api}") String baseUrl) {
        this.http = http;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public record StockInstrument(String instrumentId, String symbol, String nameFa) {
    }

    public record StockQuote(BigDecimal priceToman, Instant quotedAt) {
    }

    public List<StockInstrument> search(String query) {
        String clean = PersianText.clean(query);
        if (clean == null) {
            return List.of();
        }
        if (clean.length() > 60 || clean.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("stock.invalidQuery");
        }
        String encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8).replace("+", "%20");
        JsonNode results = get("Instrument/GetInstrumentSearch/" + encoded).path("instrumentSearch");
        if (!results.isArray()) {
            throw new PriceFetchException("پاسخ جست‌وجوی نمادهای بورس معتبر نیست.");
        }
        Map<String, StockInstrument> unique = new LinkedHashMap<>();
        for (JsonNode node : results) {
            // lastDate is the search API's active flag, not a quote date. Flow 3 is derivatives.
            if (node.path("lastDate").asInt(1) == 0 || node.path("flow").asInt(1) == 3) {
                continue;
            }
            StockInstrument instrument = instrument(node);
            if (instrument != null) {
                unique.putIfAbsent(instrument.instrumentId(), instrument);
            }
            if (unique.size() == 30) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    public StockInstrument instrument(String instrumentId) {
        validateId(instrumentId);
        StockInstrument result = instrument(get("Instrument/GetInstrumentInfo/" + instrumentId).path("instrumentInfo"));
        if (result == null || !result.instrumentId().equals(instrumentId)) {
            throw new PriceFetchException("شناسه یا مشخصات نماد در پاسخ بورس معتبر نیست.");
        }
        return result;
    }

    public StockQuote quote(String instrumentId) {
        validateId(instrumentId);
        JsonNode quote = get("ClosingPrice/GetClosingPriceInfo/" + instrumentId).path("closingPriceInfo");
        String returnedId = scalar(quote.path("insCode"));
        // This endpoint legitimately returns the sentinel "0"; a different real ID is never accepted.
        if (!quote.isObject() || !(instrumentId.equals(returnedId) || "0".equals(returnedId))) {
            throw new PriceFetchException("شناسهٔ قیمت دریافتی با نماد انتخاب‌شده مطابقت ندارد.");
        }
        if (quote.has("qTotTran5J") && PriceJson.positiveNumber(quote.path("qTotTran5J")).isEmpty()) {
            throw new PriceFetchException("قیمت معامله‌شده برای این نماد دریافت نشد.");
        }
        BigDecimal price = PriceJson.positiveNumber(quote.path("pClosing"))
                .map(rial -> rial.movePointLeft(1)).filter(PriceService::acceptable)
                .orElseThrow(() -> new PriceFetchException("قیمت پایانی معتبر برای این نماد دریافت نشد."));
        Instant quotedAt;
        try {
            String day = scalar(quote.path("dEven"));
            String time = scalar(quote.path("hEven"));
            if (day == null || !day.matches("[0-9]{8}") || time == null || !time.matches("[0-9]{1,6}")) {
                throw new DateTimeException("Missing trade timestamp");
            }
            LocalDate date = LocalDate.parse(day, DateTimeFormatter.BASIC_ISO_DATE);
            int hhmmss = Integer.parseInt(time);
            if (hhmmss == 0) {
                throw new DateTimeException("Missing trade time");
            }
            LocalTime localTime = LocalTime.of(hhmmss / 10000, hhmmss / 100 % 100, hhmmss % 100);
            quotedAt = date.atTime(localTime).atZone(TEHRAN).toInstant();
            if (date.getYear() < 1990 || quotedAt.isAfter(clock.instant())) {
                throw new DateTimeException("Invalid trade timestamp");
            }
        } catch (DateTimeException | NumberFormatException ex) {
            throw new PriceFetchException("تاریخ یا ساعت قیمت دریافتی از بورس معتبر نیست.", ex);
        }
        return new StockQuote(price, quotedAt);
    }

    public static void validateId(String instrumentId) {
        if (instrumentId == null || !instrumentId.matches("[1-9][0-9]{0,19}")) {
            throw ApiException.badRequest("stock.invalidInstrument");
        }
    }

    private JsonNode get(String path) {
        return PriceJson.parse(http.get(URI.create(baseUrl + "/" + path), Map.of(), List.of()));
    }

    private static StockInstrument instrument(JsonNode node) {
        String id = scalar(node.path("insCode"));
        String symbol = text(node.path("lVal18AFC"));
        String name = text(node.path("lVal30"));
        String type = scalar(node.path("yVal"));
        if (id == null || !id.matches("[1-9][0-9]{0,19}") || symbol == null || symbol.length() > 50
                || name == null || name.length() > 200 || node.path("flow").asInt(1) == 3
                || "68".equals(type) || "69".equals(type)) {
            return null;
        }
        return new StockInstrument(id, symbol, name);
    }

    private static String text(JsonNode node) {
        return node.isString() ? PersianText.clean(node.asString()) : null;
    }

    private static String scalar(JsonNode node) {
        return node.isString() || node.isIntegralNumber() ? node.asString() : null;
    }
}
