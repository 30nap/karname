package ir.karname.pricefeed;

import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.PriceService;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.crypto.SecretCipher;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Automatic price sources: admin configuration, dry runs and scheduled fetching. A failing source
 * records its error and leaves the last good prices in place.
 */
@Service
public class PriceFeedService {

    private static final Logger log = LoggerFactory.getLogger(PriceFeedService.class);

    /** An unchanged price is re-recorded this often, so it does not look stale. */
    static final Duration CONFIRM_EVERY = Duration.ofHours(6);
    /** Fetched prices older than this are thinned to one per day. */
    static final Duration KEEP_ALL_FOR = Duration.ofDays(30);

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern SYMBOL = Pattern.compile("^[a-z0-9]{2,12}$");
    private static final BigDecimal MAX_MULTIPLIER = new BigDecimal("1e9");
    private static final TypeReference<List<PriceMapping>> MAPPINGS = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, String>> HEADERS = new TypeReference<>() {
    };

    private final PriceSourceRepository sources;
    private final Map<PriceSourceKind, PriceProvider> providers;
    private final CommodityService commodities;
    private final PriceService prices;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final TransactionTemplate transactions;
    private final KarnameProperties properties;
    private final Clock clock;

    public PriceFeedService(PriceSourceRepository sources, List<PriceProvider> providers, CommodityService commodities, PriceService prices,
            SecretCipher cipher, JsonMapper json, TransactionTemplate transactions, KarnameProperties properties, Clock clock) {
        this.sources = sources;
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

    public record SourceRequest(String name, PriceSourceKind kind, String url, PriceUnit unit, List<HeaderInput> headers,
            List<PriceMapping> mappings, Integer intervalMinutes, Boolean enabled) {
    }

    public record HeaderView(String name, boolean hasValue) {
    }

    public record SourceView(long id, String name, PriceSourceKind kind, String url, PriceUnit unit, List<HeaderView> headers,
            List<PriceMapping> mappings, int intervalMinutes, boolean enabled, Instant lastRunAt, Instant lastSuccessAt,
            String lastError, Integer lastCount, Instant nextRunAt) {
    }

    /** Outcome of a fetch: per-commodity results, or {@code error} when the source could not be read at all. */
    public record RunResult(String error, List<QuoteResult> results, int recorded) {
    }

    @Transactional(readOnly = true)
    public List<SourceView> list() {
        return sources.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional
    public SourceView create(SourceRequest request) {
        PriceSource source = new PriceSource();
        apply(source, request);
        if (sources.existsByName(source.getName())) {
            throw ApiException.conflict("priceSource.duplicateName");
        }
        return view(sources.saveAndFlush(source));
    }

    @Transactional
    public SourceView update(long id, SourceRequest request) {
        PriceSource source = require(id);
        apply(source, request);
        if (sources.existsByNameAndIdNot(source.getName(), id)) {
            throw ApiException.conflict("priceSource.duplicateName");
        }
        return view(sources.saveAndFlush(source));
    }

    @Transactional
    public void delete(long id) {
        sources.delete(require(id));
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
            draft.setUrl(stored.getUrl());
            draft.setHeadersEncrypted(stored.getHeadersEncrypted());
        }
        apply(draft, request);
        try {
            return new RunResult(null, provider(draft).fetch(config(draft)), 0);
        } catch (PriceFetchException e) {
            return new RunResult(e.getMessage(), List.of(), 0);
        }
    }

    /** Fetches a source now and records its prices. */
    public RunResult run(long id) {
        return runSource(id);
    }

    /** Runs every enabled source whose interval has passed; called by the scheduler each minute. */
    public int runDue() {
        Instant now = clock.instant();
        int ran = 0;
        for (PriceSource source : sources.findByEnabledTrueOrderByIdAsc()) {
            if (source.getLastRunAt() != null && source.getLastRunAt().plus(Duration.ofMinutes(source.getIntervalMinutes())).isAfter(now)) {
                continue;
            }
            try {
                runSource(source.getId());
                ran++;
            } catch (RuntimeException e) {
                log.error("Price source {} failed", source.getId(), e);
            }
        }
        return ran;
    }

    /** Runs all enabled sources now (the «update prices» button). */
    public List<RunResult> runAll() {
        List<RunResult> results = new ArrayList<>();
        for (PriceSource source : sources.findByEnabledTrueOrderByIdAsc()) {
            results.add(runSource(source.getId()));
        }
        return results;
    }

    public int thinOldPrices() {
        return prices.thinFetched(KEEP_ALL_FOR, properties.timezone());
    }

    /** Fetches outside any transaction, then records the prices and the run in one. */
    private RunResult runSource(long id) {
        PriceSource source = require(id);
        Instant now = clock.instant();
        List<QuoteResult> results;
        String error = null;
        try {
            results = provider(source).fetch(config(source));
        } catch (PriceFetchException e) {
            results = List.of();
            error = e.getMessage();
        }
        List<QuoteResult> fetched = results;
        String failure = error;
        return transactions.execute(status -> record(id, now, fetched, failure));
    }

    private RunResult record(long id, Instant now, List<QuoteResult> results, String error) {
        PriceSource source = require(id);
        if (error != null) {
            source.recordRun(now, 0, error);
            return new RunResult(error, List.of(), 0);
        }
        int recorded = 0;
        List<String> failures = new ArrayList<>();
        for (QuoteResult r : results) {
            Commodity commodity = commodities.require(0, r.commodity());
            if (!r.ok()) {
                failures.add(commodity.getNameFa() + ": " + r.error());
                continue;
            }
            if (prices.recordFetched(commodity, r.priceToman(), now, source.getName(), CONFIRM_EVERY)) {
                recorded++;
            }
        }
        int ok = (int) results.stream().filter(QuoteResult::ok).count();
        source.recordRun(now, ok, failures.isEmpty() ? null : String.join(" | ", failures));
        return new RunResult(null, results, recorded);
    }

    private PriceProvider provider(PriceSource source) {
        return providers.get(source.getKind());
    }

    private SourceConfig config(PriceSource source) {
        return new SourceConfig(source.getKind(), source.getUrl(), headers(source), source.getUnit(), mappings(source));
    }

    private void apply(PriceSource source, SourceRequest r) {
        String name = PersianText.clean(r.name());
        if (name == null || name.isBlank() || name.length() > 40) {
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
        int interval = r.intervalMinutes() == null ? 30 : r.intervalMinutes();
        if (interval < 5 || interval > 1440) {
            throw ApiException.badRequest("priceSource.invalidInterval");
        }
        List<PriceMapping> mappings = validMappings(r.kind(), r.mappings());
        // stored header values (API keys) only ever go to the address they were entered for
        boolean moved = source.getUrl() != null && (url == null || !source.getUrl().replaceAll("/+$", "").equalsIgnoreCase(url.replaceAll("/+$", "")));
        Map<String, String> headers = mergedHeaders(source, r.headers(), moved);

        source.setName(name);
        source.setKind(r.kind());
        source.setUrl(url);
        source.setUnit(r.kind() == PriceSourceKind.NOBITEX ? PriceUnit.RIAL : r.unit() == null ? PriceUnit.TOMAN : r.unit());
        source.setMappings(json.writeValueAsString(mappings));
        source.setHeadersEncrypted(headers.isEmpty() ? null : cipher.encrypt(json.writeValueAsString(headers)));
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
            result.add(new PriceMapping(code, path, multiplier == null || multiplier.compareTo(BigDecimal.ONE) == 0 ? null : multiplier));
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
            if (!HEADER_NAME.matcher(name).matches()) {
                throw ApiException.badRequest("priceSource.invalidHeaders");
            }
            boolean kept = h.value() == null || h.value().isEmpty();
            if (kept && moved && stored.containsKey(name)) {
                throw ApiException.badRequest("priceSource.headerForNewUrl");
            }
            String value = kept ? stored.get(name) : h.value().trim();
            if (value == null || value.length() > 2000 || value.contains("\n") || value.contains("\r")) {
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

    private List<PriceMapping> mappings(PriceSource source) {
        try {
            return json.readValue(source.getMappings(), MAPPINGS);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored mappings of price source " + source.getId() + " are not valid JSON", e);
        }
    }

    private static boolean validUrl(String url) {
        if (url.length() > 500) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private SourceView view(PriceSource s) {
        List<HeaderView> headers = headers(s).keySet().stream().map(name -> new HeaderView(name, true)).toList();
        Instant next = !s.isEnabled() ? null : s.getLastRunAt() == null ? clock.instant() : s.getLastRunAt().plus(Duration.ofMinutes(s.getIntervalMinutes()));
        return new SourceView(s.getId(), s.getName(), s.getKind(), s.getUrl(), s.getUnit(), headers, mappings(s), s.getIntervalMinutes(),
                s.isEnabled(), s.getLastRunAt(), s.getLastSuccessAt(), s.getLastError(), s.getLastCount(), next);
    }

    private PriceSource require(long id) {
        return sources.findById(id).orElseThrow(() -> ApiException.notFound("priceSource.notFound"));
    }
}
