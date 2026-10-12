package ir.karname.pricefeed;

import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.PriceService;
import ir.karname.common.OutboundUrls;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.crypto.SecretCipher;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import ir.karname.pricefeed.PriceSelection.Decision;
import ir.karname.pricefeed.PriceSelection.Quote;
import ir.karname.pricefeed.PriceSelection.Status;
import ir.karname.pricefeed.PriceSourcePresets.Preset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Automatic price sources: admin configuration, dry runs and scheduled fetching. Every source keeps
 * its latest quote per commodity; the price recorded for a commodity comes from the most preferred
 * source whose quote is fresh, the others serving as fallbacks and cross-checks
 * ({@link PriceSelection}). A failing source records its error and leaves the last good prices in place.
 */
@Service
public class PriceFeedService {

    private static final Logger log = LoggerFactory.getLogger(PriceFeedService.class);

    /** An unchanged price is re-recorded this often, so it does not look stale. */
    static final Duration CONFIRM_EVERY = Duration.ofHours(6);
    /** Fetched prices older than this are thinned to one per day. */
    static final Duration KEEP_ALL_FOR = Duration.ofDays(30);
    /** A recorded price older than this is not used to judge new quotes. */
    static final Duration REFERENCE_AGE = Duration.ofDays(7);

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    /** Headers the HTTP client sets itself and refuses to take from a source. */
    private static final Set<String> RESERVED_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");
    /** Price tags of hand-entered and implied prices; a source named so would pass for one. */
    private static final Set<String> RESERVED_NAMES = Set.of("MANUAL", "TRANSACTION");
    /** Invisible marks that come along when a key is copied from a chat or a web page. */
    private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");
    private static final Pattern VISIBLE_ASCII = Pattern.compile("^[\\x21-\\x7E]{1,500}$");
    private static final Pattern HEADER_VALUE = Pattern.compile("^[\\x20-\\x7E]{0,2000}$");
    /** A Nobitex currency: usdt, btc, also 1m_pepe or 100k_floki. */
    private static final Pattern SYMBOL = Pattern.compile("^[a-z0-9_]{2,20}$");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("1e9");
    private static final TypeReference<List<PriceMapping>> MAPPINGS = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, String>> HEADERS = new TypeReference<>() {
    };

    private final PriceSourceRepository sources;
    private final PriceQuotes quotes;
    private final Map<PriceSourceKind, PriceProvider> providers;
    private final CommodityService commodities;
    private final PriceService prices;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final KarnameProperties properties;
    private final Clock clock;
    /** Sources being fetched now: a source is never fetched twice at once. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public PriceFeedService(PriceSourceRepository sources, PriceQuotes quotes, List<PriceProvider> providers, CommodityService commodities,
            PriceService prices, SecretCipher cipher, JsonMapper json, TransactionTemplate transactions, KarnameProperties properties, Clock clock) {
        this.sources = sources;
        this.quotes = quotes;
        this.providers = providers.stream().collect(Collectors.toMap(PriceProvider::kind, Function.identity()));
        this.commodities = commodities;
        this.prices = prices;
        this.cipher = cipher;
        this.json = json;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /** A header to send; a null value on update keeps the stored one (values are never sent back). */
    public record HeaderInput(String name, String value) {
    }

    /**
     * {@code apiKey} replaces {@code {key}} in the address and header values; empty or null keeps the
     * stored key (unless the address changed). {@code preset} names the template the form started from.
     */
    public record SourceRequest(String name, PriceSourceKind kind, String url, PriceUnit unit, List<HeaderInput> headers,
            List<PriceMapping> mappings, Integer intervalMinutes, Boolean enabled, String apiKey, String preset) {

        public SourceRequest(String name, PriceSourceKind kind, String url, PriceUnit unit, List<HeaderInput> headers,
                List<PriceMapping> mappings, Integer intervalMinutes, Boolean enabled) {
            this(name, kind, url, unit, headers, mappings, intervalMinutes, enabled, null, null);
        }
    }

    public record HeaderView(String name, boolean hasValue) {
    }

    /** {@code usesKey}: the address or a header asks for {@code {key}}; {@code hasApiKey}: one is stored. */
    public record SourceView(long id, String name, PriceSourceKind kind, String url, PriceUnit unit, List<HeaderView> headers,
            List<PriceMapping> mappings, int intervalMinutes, boolean enabled, int priority, String preset, boolean usesKey,
            boolean hasApiKey, Instant lastRunAt, Instant lastSuccessAt, String lastError, Integer lastCount, Instant nextRunAt) {
    }

    /** One source's latest quote for a commodity and what became of it. */
    public record QuoteView(long sourceId, String sourceName, BigDecimal priceToman, Instant fetchedAt, Status status) {
    }

    /**
     * Where a commodity's price comes from: the chosen quote, the others, a warning when they
     * disagree, and the price last recorded from them ({@code recorded…}).
     */
    public record CoverageView(String commodity, BigDecimal priceToman, Instant fetchedAt, String source, List<QuoteView> quotes,
            String warning, BigDecimal recordedPrice, Instant recordedAt, String recordedSource) {
    }

    /** Outcome of a fetch: per-commodity results, or {@code error} when the source could not be read at all. */
    public record RunResult(String error, List<QuoteResult> results, int recorded) {
    }

    @Transactional(readOnly = true)
    public List<SourceView> list() {
        return sources.findAllByOrderByPriorityAscIdAsc().stream().map(this::view).toList();
    }

    @Transactional
    public SourceView create(SourceRequest request) {
        PriceSource source = new PriceSource();
        apply(source, request);
        if (sources.existsByName(source.getName())) {
            throw ApiException.conflict("priceSource.duplicateName");
        }
        // a new source starts last: a fallback until the admin moves it up
        source.setPriority(sources.maxPriority() + 10);
        return view(sources.saveAndFlush(source));
    }

    @Transactional
    public SourceView update(long id, SourceRequest request) {
        PriceSource source = require(id);
        SourceConfig before = config(source);
        apply(source, request);
        if (sources.existsByNameAndIdNot(source.getName(), id)) {
            throw ApiException.conflict("priceSource.duplicateName");
        }
        forgetChangedQuotes(source, before, config(source));
        return view(sources.saveAndFlush(source));
    }

    /**
     * After an edit, the quotes read under the old settings no longer stand for the source: all of
     * them when the address, kind, unit, headers or key changed, else those of mappings removed or
     * changed. The source takes part again with its next fetch.
     */
    private void forgetChangedQuotes(PriceSource source, SourceConfig before, SourceConfig after) {
        quotes.lock();
        boolean all = before.kind() != after.kind() || !Objects.equals(before.url(), after.url()) || before.unit() != after.unit()
                || !before.headers().equals(after.headers()) || !Objects.equals(before.apiKey(), after.apiKey());
        if (all) {
            quotes.forget(source.getId(), null);
            return;
        }
        Map<String, PriceMapping> now = after.mappings().stream().collect(Collectors.toMap(PriceMapping::commodity, Function.identity()));
        List<Long> changed = before.mappings().stream()
                .filter(m -> !m.equals(now.get(m.commodity())))
                .map(m -> commodities.require(0, m.commodity()).getId())
                .toList();
        quotes.forget(source.getId(), changed);
    }

    @Transactional
    public void delete(long id) {
        sources.delete(require(id));
    }

    /** Sets the order of preference: {@code ids} lists every source, most preferred first. */
    @Transactional
    public List<SourceView> reorder(List<Long> ids) {
        List<PriceSource> all = sources.findAllByOrderByPriorityAscIdAsc();
        if (ids == null || ids.size() != all.size() || !new HashSet<>(ids).equals(all.stream().map(PriceSource::getId).collect(Collectors.toSet()))) {
            throw ApiException.badRequest("priceSource.invalidOrder");
        }
        Map<Long, PriceSource> byId = all.stream().collect(Collectors.toMap(PriceSource::getId, Function.identity()));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPriority((i + 1) * 10);
        }
        sources.flush();
        return list();
    }

    /** For every commodity some source quotes: the quote in use, the others, and disagreements. */
    @Transactional(readOnly = true)
    public List<CoverageView> coverage() {
        Instant now = clock.instant();
        List<CoverageView> result = new ArrayList<>();
        quotes.all().forEach((code, list) -> {
            Commodity commodity = commodities.require(0, code);
            Decision d = PriceSelection.decide(list, now, reference(commodity, now));
            List<QuoteView> views = d.verdicts().stream().map(v -> new QuoteView(v.quote().sourceId(), v.quote().sourceName(),
                    v.quote().priceToman(), v.quote().fetchedAt(), v.status())).toList();
            Quote chosen = d.chosen();
            PriceService.Fetched recorded = prices.latestFetched(commodity, Instant.EPOCH).orElse(null);
            result.add(new CoverageView(code, chosen == null ? null : chosen.priceToman(), chosen == null ? null : chosen.fetchedAt(),
                    chosen == null ? null : chosen.sourceName(), views, d.warning(), recorded == null ? null : recorded.price(),
                    recorded == null ? null : recorded.at(), recorded == null ? null : recorded.source()));
        });
        return result;
    }

    public List<Preset> presets() {
        return PriceSourcePresets.all();
    }

    /**
     * Fetches without recording anything, to check a configuration from the settings form; with
     * {@code id}, header values left empty are taken from the stored source. No transaction is held
     * while the outside service answers.
     */
    public RunResult test(Long id, SourceRequest request) {
        PriceSource draft = new PriceSource();
        if (id != null) {
            PriceSource stored = require(id);
            // the stored address too, so a changed one is noticed and the secrets are not lent to it
            draft.setKind(stored.getKind());
            draft.setUrl(stored.getUrl());
            draft.setHeadersEncrypted(stored.getHeadersEncrypted());
            draft.setApiKeyEncrypted(stored.getApiKeyEncrypted());
        }
        apply(draft, request);
        SourceConfig config = config(draft);
        try {
            return new RunResult(null, checked(provider(draft).fetch(config), config), 0);
        } catch (PriceFetchException e) {
            return new RunResult(PriceHttpClient.redact(e.getMessage(), config.secrets()), List.of(), 0);
        } catch (RuntimeException e) {
            log.error("Testing a price source failed unexpectedly: {}", where(e));
            return new RunResult(UNEXPECTED, List.of(), 0);
        }
    }

    static final String UNEXPECTED = "خطای پیش‌بینی‌نشده در دریافت یا ثبت قیمت؛ جزئیات در گزارش سرور است.";

    /** Where an unexpected exception was thrown, without its message: that may quote a secret (an address with the key in it). */
    private static String where(RuntimeException e) {
        StackTraceElement[] trace = e.getStackTrace();
        return e.getClass().getName() + (trace.length > 0 ? " at " + trace[0] : "");
    }

    /**
     * Turns a price that cannot be recorded (zero once rounded, beyond the limit) into an error,
     * and removes secrets from every error, including those quoting the service's answer.
     */
    private static List<QuoteResult> checked(List<QuoteResult> results, SourceConfig config) {
        return results.stream().map(r -> {
            if (r.ok()) {
                return PriceService.acceptable(r.priceToman()) ? r
                        : new QuoteResult(r.commodity(), r.path(), r.raw(), null, "قیمت " + r.priceToman().toPlainString() + " تومان پذیرفتنی نیست؛ واحد یا ضریب را بررسی کنید.");
            }
            return new QuoteResult(r.commodity(), r.path(), r.raw(), null, PriceHttpClient.redact(r.error(), config.secrets()));
        }).toList();
    }

    /** Fetches a source now and records its prices. */
    public RunResult run(long id) {
        return runSource(id, true);
    }

    /** Runs every enabled source whose interval has passed; called by the scheduler each minute. */
    public int runDue() {
        Instant now = clock.instant();
        int ran = 0;
        for (PriceSource source : sources.findByEnabledTrueOrderByPriorityAscIdAsc()) {
            if (source.getLastRunAt() != null && source.getLastRunAt().plus(Duration.ofMinutes(source.getIntervalMinutes())).isAfter(now)) {
                continue;
            }
            try {
                runSource(source.getId(), false);
                ran++;
            } catch (RuntimeException e) {
                log.error("Price source {} failed: {}", source.getId(), where(e));
            }
        }
        return ran;
    }

    /** Runs all enabled sources now (the «update prices» button). */
    public List<RunResult> runAll() {
        List<RunResult> results = new ArrayList<>();
        for (PriceSource source : sources.findByEnabledTrueOrderByPriorityAscIdAsc()) {
            try {
                results.add(runSource(source.getId(), false));
            } catch (RuntimeException e) {
                log.error("Price source {} failed: {}", source.getId(), where(e));
                results.add(new RunResult(UNEXPECTED, List.of(), 0));
            }
        }
        return results;
    }

    public int thinOldPrices() {
        return prices.thinFetched(KEEP_ALL_FOR, properties.timezone());
    }

    /**
     * Fetches outside any transaction, then records the prices and the run in one. {@code byHand}:
     * the admin asked for this source, so it takes part even when switched off, and a jump in its
     * price counts as confirmed.
     */
    private RunResult runSource(long id, boolean byHand) {
        if (!running.add(id)) {
            return new RunResult("این منبع همین حالا در حال دریافت قیمت است.", List.of(), 0);
        }
        try {
            PriceSource source = require(id);
            if (!byHand && !source.isEnabled()) {
                return new RunResult(null, List.of(), 0);
            }
            Instant now = clock.instant();
            SourceConfig config = config(source);
            List<QuoteResult> results;
            String error = null;
            try {
                results = checked(provider(source).fetch(config), config);
            } catch (PriceFetchException e) {
                results = List.of();
                error = PriceHttpClient.redact(e.getMessage(), config.secrets());
                log.warn("Price source {} ({}) failed: {}", source.getId(), source.getName(), error);
            } catch (RuntimeException e) {
                results = List.of();
                error = UNEXPECTED;
                log.error("Price source {} ({}) failed unexpectedly: {}", source.getId(), source.getName(), where(e));
            }
            List<QuoteResult> fetched = results;
            String failure = error;
            try {
                return transactions.execute(status -> record(id, now, fetched, failure, byHand));
            } catch (RuntimeException e) {
                // the run is still recorded, or the source would be fetched again every minute with nothing shown
                log.error("Recording price source {} failed: {}", id, where(e));
                transactions.executeWithoutResult(status -> sources.findById(id).ifPresent(s -> s.recordRun(now, 0, UNEXPECTED)));
                return new RunResult(UNEXPECTED, List.of(), 0);
            }
        } finally {
            running.remove(id);
        }
    }

    private RunResult record(long id, Instant fetchedAt, List<QuoteResult> results, String error, boolean byHand) {
        quotes.lock();
        Instant now = clock.instant();
        PriceSource source = require(id);
        if (error != null) {
            source.recordRun(fetchedAt, 0, error);
            return new RunResult(error, List.of(), 0);
        }
        // quotes of commodities the source no longer publishes must not stand for it
        quotes.forgetAllBut(source.getId(), mappings(source).stream().map(m -> commodities.require(0, m.commodity()).getId()).toList());
        Instant freshUntil = fetchedAt.plus(PriceSelection.freshness(source.getIntervalMinutes()));
        List<String> warnings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<Commodity> quoted = new LinkedHashSet<>();
        for (QuoteResult r : results) {
            Commodity commodity = commodities.require(0, r.commodity());
            if (!r.ok()) {
                failures.add(commodity.getNameFa() + ": " + r.error());
                continue;
            }
            quotes.store(source.getId(), commodity.getId(), r.priceToman(), fetchedAt, freshUntil);
            quoted.add(commodity);
        }
        int recorded = 0;
        for (Commodity commodity : quoted) {
            List<Quote> candidates = quotes.forCommodity(commodity.getId()).stream()
                    .map(q -> byHand && q.sourceId() == source.getId() && !q.enabled() ? q.switchedOn() : q)
                    .toList();
            Decision d = PriceSelection.decide(candidates, now, reference(commodity, now), byHand ? source.getId() : null);
            Quote chosen = d.chosen();
            if (chosen != null && prices.recordFetched(commodity, chosen.priceToman(), chosen.fetchedAt(), chosen.sourceName(), CONFIRM_EVERY, now)) {
                recorded++;
            }
            // this source's quote set aside, held, or far from the one in use: its row should say so
            boolean concerned = d.verdicts().stream().filter(v -> v.quote().sourceId() == source.getId()).anyMatch(v ->
                    v.status() == Status.OUTLIER || v.status() == Status.HELD
                            || v.status() == Status.BACKUP && chosen != null
                            && PriceSelection.deviation(v.quote().priceToman(), chosen.priceToman()).compareTo(PriceSelection.TOLERANCE) > 0);
            if (concerned && d.warning() != null) {
                warnings.add(commodity.getNameFa() + ": " + d.warning());
            }
        }
        int ok = (int) results.stream().filter(QuoteResult::ok).count();
        // what was set aside or held first: it matters more than a missing path, and the text is cut at 500 characters
        warnings.addAll(failures);
        source.recordRun(fetchedAt, ok, warnings.isEmpty() ? null : String.join(" | ", warnings));
        return new RunResult(null, results, recorded);
    }

    private BigDecimal reference(Commodity commodity, Instant now) {
        return prices.latestFetched(commodity, now.minus(REFERENCE_AGE)).map(PriceService.Fetched::price).orElse(null);
    }

    private PriceProvider provider(PriceSource source) {
        return providers.get(source.getKind());
    }

    private SourceConfig config(PriceSource source) {
        return new SourceConfig(source.getKind(), source.getUrl(), headers(source), source.getUnit(), mappings(source), apiKey(source));
    }

    private void apply(PriceSource source, SourceRequest r) {
        String name = PersianText.clean(r.name());
        if (name == null || name.isBlank() || name.length() > 40 || RESERVED_NAMES.contains(name.strip().toUpperCase())) {
            throw ApiException.badRequest("priceSource.invalidName");
        }
        if (r.kind() == null) {
            throw ApiException.badRequest("priceSource.kindRequired");
        }
        String url = r.url() == null || r.url().isBlank() ? null : r.url().trim();
        if (r.kind() == PriceSourceKind.JSON && url == null) {
            throw ApiException.badRequest("priceSource.invalidUrl");
        }
        if (url != null && !validUrl(url)) {
            throw ApiException.badRequest("priceSource.invalidUrl");
        }
        if (r.preset() != null && PriceSourcePresets.find(r.preset()).isEmpty()) {
            throw ApiException.badRequest("priceSource.unknownPreset");
        }
        int interval = r.intervalMinutes() == null ? 30 : r.intervalMinutes();
        if (interval < 5 || interval > 1440) {
            throw ApiException.badRequest("priceSource.invalidInterval");
        }
        List<PriceMapping> mappings = validMappings(r.kind(), r.mappings());
        // stored header values (API keys) only ever go to the address they were entered for; Nobitex
        // without an address means its default one
        String before = effectiveUrl(source.getKind(), source.getUrl());
        boolean moved = before != null && !before.replaceAll("/+$", "").equalsIgnoreCase(Objects.requireNonNullElse(effectiveUrl(r.kind(), url), "").replaceAll("/+$", ""));
        Map<String, String> headers = mergedHeaders(source, r.headers(), moved);
        boolean usesKey = (url != null && url.contains(SourceConfig.KEY)) || headers.values().stream().anyMatch(v -> v.contains(SourceConfig.KEY));
        String key = null;
        if (usesKey) {
            if (r.apiKey() != null && !r.apiKey().isBlank()) {
                // a key copied from a chat or a page may bring invisible marks or Persian digits along
                key = INVISIBLE.matcher(PersianText.normalizeDigits(r.apiKey())).replaceAll("").strip();
                if (!VISIBLE_ASCII.matcher(key).matches()) {
                    throw ApiException.badRequest("priceSource.invalidKey");
                }
            } else {
                key = apiKey(source);
                // like the headers: a stored key only ever goes to the address it was entered for
                if (key != null && moved) {
                    throw ApiException.badRequest("priceSource.keyForNewUrl");
                }
                if (key == null) {
                    throw ApiException.badRequest("priceSource.keyRequired");
                }
            }
            // the address as it will really be asked, with the key in it
            if (url != null && !OutboundUrls.isAllowed(url.replace(SourceConfig.KEY, URLEncoder.encode(key, StandardCharsets.UTF_8)))) {
                throw ApiException.badRequest("priceSource.invalidUrl");
            }
        }

        source.setName(name);
        source.setKind(r.kind());
        source.setUrl(url);
        source.setUnit(r.kind() == PriceSourceKind.NOBITEX ? PriceUnit.RIAL : r.unit() == null ? PriceUnit.TOMAN : r.unit());
        source.setMappings(json.writeValueAsString(mappings));
        source.setHeadersEncrypted(headers.isEmpty() ? null : cipher.encrypt(json.writeValueAsString(headers)));
        source.setApiKeyEncrypted(key == null ? null : cipher.encrypt(key));
        if (r.preset() != null || source.getId() == null) {
            source.setPreset(r.preset());
        }
        source.setIntervalMinutes(interval);
        if (r.enabled() != null) {
            source.setEnabled(r.enabled());
        }
    }

    private List<PriceMapping> validMappings(PriceSourceKind kind, List<PriceMapping> input) {
        if (input == null || input.isEmpty() || input.size() > 50) {
            throw ApiException.badRequest("priceSource.invalidMappings");
        }
        Set<String> seen = new HashSet<>();
        List<PriceMapping> result = new ArrayList<>();
        for (PriceMapping m : input) {
            if (m == null || m.commodity() == null || m.path() == null) {
                throw ApiException.badRequest("priceSource.invalidMappings");
            }
            String code = m.commodity().trim().toUpperCase();
            Commodity commodity;
            try {
                commodity = commodities.require(0, code);
            } catch (ApiException e) {
                throw ApiException.badRequest("priceSource.unknownCommodity", code);
            }
            if (commodity.isToman() || !seen.add(code)) {
                throw ApiException.badRequest("priceSource.invalidMappings");
            }
            String path = kind == PriceSourceKind.NOBITEX ? m.path().trim().toLowerCase() : m.path().trim();
            boolean validPath = kind == PriceSourceKind.NOBITEX ? SYMBOL.matcher(path).matches() : path.startsWith("/") && path.length() <= 300;
            if (!validPath) {
                throw ApiException.badRequest("priceSource.invalidPath", path);
            }
            BigDecimal multiplier = m.multiplier();
            if (multiplier != null && (multiplier.signum() <= 0 || multiplier.compareTo(MAX_MULTIPLIER) > 0)) {
                throw ApiException.badRequest("priceSource.invalidMultiplier");
            }
            PriceUnit unit = kind == PriceSourceKind.NOBITEX ? null : m.unit();
            result.add(new PriceMapping(code, path, multiplier == null || multiplier.compareTo(BigDecimal.ONE) == 0 ? null : multiplier, unit));
        }
        return result;
    }

    /** New header values replace stored ones; a header sent without a value keeps its stored value. */
    private Map<String, String> mergedHeaders(PriceSource source, List<HeaderInput> input, boolean moved) {
        Map<String, String> stored = headers(source);
        Map<String, String> result = new LinkedHashMap<>();
        if (input == null) {
            return result;
        }
        if (input.size() > 10) {
            throw ApiException.badRequest("priceSource.invalidHeaders");
        }
        for (HeaderInput h : input) {
            String name = h == null || h.name() == null ? "" : h.name().trim();
            if (!HEADER_NAME.matcher(name).matches() || RESERVED_HEADERS.contains(name.toLowerCase())) {
                throw ApiException.badRequest("priceSource.invalidHeaders");
            }
            boolean kept = h.value() == null || h.value().isEmpty();
            if (kept && moved && stored.containsKey(name)) {
                throw ApiException.badRequest("priceSource.headerForNewUrl");
            }
            String value = kept ? stored.get(name) : INVISIBLE.matcher(h.value()).replaceAll("").trim();
            // HTTP header values are plain ASCII; anything else fails when the request is made
            if (value == null || !HEADER_VALUE.matcher(value).matches()) {
                throw ApiException.badRequest("priceSource.invalidHeaders");
            }
            result.put(name, value);
        }
        return result;
    }

    private Map<String, String> headers(PriceSource source) {
        if (source.getHeadersEncrypted() == null) {
            return Map.of();
        }
        try {
            return json.readValue(cipher.decrypt(source.getHeadersEncrypted()), HEADERS);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored headers of price source " + source.getId() + " are not valid JSON", e);
        }
    }

    private String apiKey(PriceSource source) {
        return source.getApiKeyEncrypted() == null ? null : cipher.decrypt(source.getApiKeyEncrypted());
    }

    private List<PriceMapping> mappings(PriceSource source) {
        try {
            return json.readValue(source.getMappings(), MAPPINGS);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored mappings of price source " + source.getId() + " are not valid JSON", e);
        }
    }

    private static boolean validUrl(String url) {
        // {key} is filled in at request time, URL-encoded, and only in the path or the query: in the
        // host it would pick the server to call. Checked here with a stand-in, and again with the key.
        String standIn = url.replace(SourceConfig.KEY, "karnamekey");
        try {
            String authority = new URI(standIn).getRawAuthority();
            if (authority == null || authority.toLowerCase().contains("karnamekey")) {
                return false;
            }
        } catch (URISyntaxException e) {
            return false;
        }
        return OutboundUrls.isAllowed(standIn);
    }

    /** The address a source is read from: Nobitex without one uses its default. */
    private static String effectiveUrl(PriceSourceKind kind, String url) {
        return kind == PriceSourceKind.NOBITEX && url == null ? NobitexPriceProvider.DEFAULT_URL : url;
    }

    private SourceView view(PriceSource s) {
        Map<String, String> stored = headers(s);
        List<HeaderView> headers = stored.keySet().stream().map(name -> new HeaderView(name, true)).toList();
        boolean usesKey = (s.getUrl() != null && s.getUrl().contains(SourceConfig.KEY)) || stored.values().stream().anyMatch(v -> v.contains(SourceConfig.KEY));
        Instant next = !s.isEnabled() ? null : s.getLastRunAt() == null ? clock.instant() : s.getLastRunAt().plus(Duration.ofMinutes(s.getIntervalMinutes()));
        return new SourceView(s.getId(), s.getName(), s.getKind(), s.getUrl(), s.getUnit(), headers, mappings(s), s.getIntervalMinutes(),
                s.isEnabled(), s.getPriority(), s.getPreset(), usesKey, s.getApiKeyEncrypted() != null, s.getLastRunAt(), s.getLastSuccessAt(),
                s.getLastError(), s.getLastCount(), next);
    }

    private PriceSource require(long id) {
        return sources.findById(id).orElseThrow(() -> ApiException.notFound("priceSource.notFound"));
    }
}
