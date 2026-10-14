package ir.karname.pricefeed;

import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityKind;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.commodity.CommodityService.CustomCommodityRequest;
import ir.karname.commodity.PriceService;
import ir.karname.common.web.ApiException;
import ir.karname.pricefeed.TsetmcClient.StockInstrument;
import ir.karname.pricefeed.TsetmcClient.StockQuote;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Short database transactions bracket each network call; failed quotes never replace good prices. */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final Duration INTERVAL = Duration.ofMinutes(30);
    private final CommodityRepository repository;
    private final CommodityService commodities;
    private final PriceService prices;
    private final TsetmcClient client;
    private final Clock clock;
    private final EntityManager entities;
    private final TransactionTemplate transactions;
    // Fixed stripes bound memory even when users add and remove many symbols.
    private final ReentrantLock[] userLocks = locks();
    private final ReentrantLock[] fetchLocks = locks();
    private final AtomicBoolean running = new AtomicBoolean();

    public StockService(CommodityRepository repository, CommodityService commodities, PriceService prices,
            TsetmcClient client, Clock clock, EntityManager entities, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.commodities = commodities;
        this.prices = prices;
        this.client = client;
        this.clock = clock;
        this.entities = entities;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<StockInstrument> search(String query) {
        try {
            return client.search(query);
        } catch (PriceFetchException ex) {
            throw ApiException.unavailable("stock.unavailable", ex.getMessage());
        }
    }

    public CommodityView create(long userId, String instrumentId) {
        TsetmcClient.validateId(instrumentId);
        ReentrantLock lock = stripe(userLocks, Long.hashCode(userId));
        String code;
        boolean created = false;
        lock.lock();
        try {
            Commodity existing = repository.findByUserIdAndStockInstrumentId(userId, instrumentId).orElse(null);
            if (existing != null) {
                return view(userId, existing.getCode());
            }
            StockInstrument instrument = metadata(instrumentId);
            try {
                code = transactions.execute(status -> {
                    Commodity duplicate = repository.findByUserIdAndStockInstrumentId(userId, instrumentId).orElse(null);
                    if (duplicate != null) {
                        return duplicate.getCode();
                    }
                    Commodity commodity = commodities.createCustom(userId,
                            new CustomCommodityRequest(instrument.symbol(), "سهم", CommodityKind.SECURITY, 0));
                    bind(commodity, instrument);
                    return commodity.getCode();
                });
                created = true;
            } catch (DataIntegrityViolationException ex) {
                // The database's unique constraint also protects simultaneous requests on separate instances.
                code = repository.findByUserIdAndStockInstrumentId(userId, instrumentId)
                        .map(Commodity::getCode).orElseThrow(() -> ex);
            }
        } finally {
            lock.unlock();
        }
        return created ? refresh(userId, code) : view(userId, code);
    }

    public CommodityView link(long userId, String code, String instrumentId) {
        TsetmcClient.validateId(instrumentId);
        Commodity before = requireOwn(userId, code);
        checkBinding(before, instrumentId);
        if (instrumentId.equals(before.getStockInstrumentId())) {
            return view(userId, code);
        }
        StockInstrument instrument = metadata(instrumentId);
        ReentrantLock lock = stripe(userLocks, Long.hashCode(userId));
        lock.lock();
        try {
            transactions.executeWithoutResult(status -> {
                Commodity commodity = lockOwn(userId, code);
                checkBinding(commodity, instrumentId);
                Commodity duplicate = repository.findByUserIdAndStockInstrumentId(userId, instrumentId).orElse(null);
                if (duplicate != null && !duplicate.getId().equals(commodity.getId())) {
                    throw ApiException.conflict("stock.alreadyAdded");
                }
                if (commodity.getStockInstrumentId() == null) {
                    bind(commodity, instrument);
                }
            });
        } finally {
            lock.unlock();
        }
        return refresh(userId, code);
    }

    public CommodityView unlink(long userId, String code) {
        transactions.executeWithoutResult(status -> {
            Commodity commodity = lockOwn(userId, code);
            commodity.setStockInstrumentId(null);
            commodity.setStockSymbol(null);
            commodity.setStockLastAttemptAt(null);
            commodity.setStockLastSuccessAt(null);
            commodity.setStockLastError(null);
        });
        return view(userId, code);
    }

    public CommodityView refresh(long userId, String code) {
        Commodity commodity = requireOwn(userId, code);
        String instrumentId = commodity.getStockInstrumentId();
        if (instrumentId == null) {
            throw ApiException.badRequest("stock.notLinked");
        }
        ReentrantLock lock = stripe(fetchLocks, instrumentId.hashCode());
        if (!lock.tryLock()) {
            throw ApiException.conflict("stock.busy");
        }
        try {
            Attempt attempt = claim(commodity.getId(), instrumentId, false);
            if (attempt != null) {
                complete(attempt, fetch(instrumentId));
            }
        } finally {
            lock.unlock();
        }
        return view(userId, code);
    }

    /** One request per due instrument, with each owner's price and status stored separately. */
    public void runDue() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, List<Long>> groups = new LinkedHashMap<>();
            for (Commodity commodity : repository.findByStockInstrumentIdIsNotNullAndActiveTrue()) {
                if (due(commodity)) {
                    groups.computeIfAbsent(commodity.getStockInstrumentId(), ignored -> new ArrayList<>()).add(commodity.getId());
                }
            }
            for (Map.Entry<String, List<Long>> entry : groups.entrySet()) {
                ReentrantLock lock = stripe(fetchLocks, entry.getKey().hashCode());
                if (!lock.tryLock()) {
                    continue;
                }
                try {
                    List<Attempt> attempts = new ArrayList<>();
                    for (Long id : entry.getValue()) {
                        try {
                            Attempt attempt = claim(id, entry.getKey(), true);
                            if (attempt != null) {
                                attempts.add(attempt);
                            }
                        } catch (RuntimeException ex) {
                            log.warn("Could not start automatic stock quote for commodity {}", id, ex);
                        }
                    }
                    if (attempts.isEmpty()) {
                        continue;
                    }
                    Outcome outcome = fetch(entry.getKey());
                    for (Attempt attempt : attempts) {
                        try {
                            complete(attempt, outcome);
                        } catch (RuntimeException ex) {
                            log.warn("Could not save automatic stock quote for commodity {}", attempt.commodityId(), ex);
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
        } finally {
            running.set(false);
        }
    }

    private record Attempt(long commodityId, String instrumentId, long version) {
    }

    private record Outcome(StockQuote quote, String error) {
    }

    private Attempt claim(long id, String instrumentId, boolean onlyIfDue) {
        return transactions.execute(status -> {
            Commodity commodity = locked(id);
            if (commodity == null || !commodity.isActive() || !instrumentId.equals(commodity.getStockInstrumentId())
                    || onlyIfDue && !due(commodity)) {
                return null;
            }
            commodity.setStockLastAttemptAt(clock.instant());
            entities.flush();
            return new Attempt(id, instrumentId, commodity.getVersion());
        });
    }

    private Outcome fetch(String instrumentId) {
        try {
            return new Outcome(client.quote(instrumentId), null);
        } catch (PriceFetchException ex) {
            return new Outcome(null, bounded(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Could not fetch TSETMC quote", ex);
            return new Outcome(null, "دریافت قیمت بورس انجام نشد؛ دوباره تلاش کنید.");
        }
    }

    private void complete(Attempt attempt, Outcome outcome) {
        transactions.executeWithoutResult(status -> {
            Commodity commodity = locked(attempt.commodityId());
            // Unlinking/relinking, editing, or deleting during HTTP must invalidate the old response.
            if (commodity == null || !commodity.isActive() || commodity.getVersion() != attempt.version()
                    || !attempt.instrumentId().equals(commodity.getStockInstrumentId())) {
                return;
            }
            if (outcome.quote() != null) {
                prices.recordStockQuote(commodity, outcome.quote().priceToman(), outcome.quote().quotedAt());
                commodity.setStockLastSuccessAt(clock.instant());
            }
            commodity.setStockLastError(outcome.error());
        });
    }

    private StockInstrument metadata(String instrumentId) {
        try {
            return client.instrument(instrumentId);
        } catch (PriceFetchException ex) {
            throw ApiException.unavailable("stock.unavailable", ex.getMessage());
        }
    }

    private Commodity requireOwn(long userId, String code) {
        Commodity commodity = commodities.require(userId, code);
        if (!commodity.isCustom()) {
            throw ApiException.forbidden("commodity.builtIn");
        }
        return commodity;
    }

    private Commodity lockOwn(long userId, String code) {
        Commodity commodity = locked(requireOwn(userId, code).getId());
        if (commodity == null || !commodity.isActive() || !Objects.equals(commodity.getUserId(), userId)) {
            throw ApiException.notFound("commodity.notFound");
        }
        return commodity;
    }

    private Commodity locked(long id) {
        Commodity commodity = entities.find(Commodity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (commodity != null) {
            // Reload even with OpenEntityManagerInView: the quote may have been in flight for seconds.
            entities.refresh(commodity, LockModeType.PESSIMISTIC_WRITE);
        }
        return commodity;
    }

    private CommodityView view(long userId, String code) {
        Commodity commodity = requireOwn(userId, code);
        return commodities.view(commodity, prices.latestPrices(userId, List.of(commodity.getId()), clock.instant()).get(commodity.getId()));
    }

    private static void checkBinding(Commodity commodity, String instrumentId) {
        if (commodity.getKind() != CommodityKind.SECURITY || commodity.getScale() != 0) {
            throw ApiException.badRequest("stock.securityRequired");
        }
        if (commodity.getStockInstrumentId() != null && !instrumentId.equals(commodity.getStockInstrumentId())) {
            throw ApiException.conflict("stock.unlinkFirst");
        }
    }

    private static void bind(Commodity commodity, StockInstrument instrument) {
        commodity.setStockInstrumentId(instrument.instrumentId());
        commodity.setStockSymbol(instrument.symbol());
        commodity.setStockLastAttemptAt(null);
        commodity.setStockLastSuccessAt(null);
        commodity.setStockLastError(null);
    }

    private boolean due(Commodity commodity) {
        Instant last = commodity.getStockLastAttemptAt();
        return last == null || !last.isAfter(clock.instant().minus(INTERVAL));
    }

    private static String bounded(String message) {
        String clean = message == null ? "دریافت قیمت بورس انجام نشد." : message.replaceAll("[\\p{Cc}]+", " ").strip();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[64];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private static ReentrantLock stripe(ReentrantLock[] locks, int hash) {
        return locks[Math.floorMod(hash, locks.length)];
    }
}
