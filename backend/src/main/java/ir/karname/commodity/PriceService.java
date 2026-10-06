package ir.karname.commodity;

import ir.karname.common.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Price lookups and recording. A user sees instance-wide prices and their own; at any moment the
 * most recent of those wins.
 */
@Service
public class PriceService {

    private static final BigDecimal MAX_PRICE = new BigDecimal("1e15");

    private final JdbcClient jdbc;
    private final PriceRepository prices;
    private final CommodityRepository commodities;
    private final Clock clock;

    public PriceService(JdbcClient jdbc, PriceRepository prices, CommodityRepository commodities, Clock clock) {
        this.jdbc = jdbc;
        this.prices = prices;
        this.commodities = commodities;
        this.clock = clock;
    }

    /** Latest known price (at or before {@code at}) for each commodity; Toman maps to 1. */
    @Transactional(readOnly = true)
    public Map<Long, PricePoint> latestPrices(long userId, Collection<Long> commodityIds, Instant at) {
        Map<Long, PricePoint> result = new HashMap<>();
        if (commodityIds.isEmpty()) {
            return result;
        }
        Set<Long> toman = tomanIds();
        Long[] ids = commodityIds.stream().filter(id -> !toman.contains(id)).distinct().toArray(Long[]::new);
        commodityIds.stream().filter(toman::contains).forEach(id -> result.put(id, PricePoint.toman(id)));
        if (ids.length == 0) {
            return result;
        }
        jdbc.sql("""
                SELECT DISTINCT ON (p.commodity_id) p.commodity_id, p.price_toman, p.priced_at, p.source, p.user_id
                FROM prices p
                WHERE p.commodity_id = ANY(:ids) AND (p.user_id IS NULL OR p.user_id = :userId) AND p.priced_at <= :at
                ORDER BY p.commodity_id, p.priced_at DESC, p.id DESC
                """)
                .param("ids", ids)
                .param("userId", userId)
                .param("at", Timestamp.from(at))
                .query((rs, n) -> new PricePoint(rs.getLong(1), rs.getBigDecimal(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getObject(5) != null))
                .list()
                .forEach(p -> result.put(p.commodityId(), p));
        return result;
    }

    @Transactional(readOnly = true)
    public PriceTimeline timeline(long userId, Collection<Long> commodityIds) {
        Set<Long> toman = tomanIds();
        Long[] ids = commodityIds.stream().filter(id -> !toman.contains(id)).distinct().toArray(Long[]::new);
        if (ids.length == 0) {
            return new PriceTimeline(List.of(), toman);
        }
        List<PricePoint> points = jdbc.sql("""
                SELECT p.commodity_id, p.price_toman, p.priced_at, p.source, p.user_id
                FROM prices p
                WHERE p.commodity_id = ANY(:ids) AND (p.user_id IS NULL OR p.user_id = :userId)
                ORDER BY p.priced_at, p.id
                """)
                .param("ids", ids)
                .param("userId", userId)
                .query((rs, n) -> new PricePoint(rs.getLong(1), rs.getBigDecimal(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getObject(5) != null))
                .list();
        return new PriceTimeline(points, toman);
    }

    public record PriceRecordView(long id, String commodity, BigDecimal priceToman, Instant pricedAt, String source,
            boolean personal) {
    }

    @Transactional(readOnly = true)
    public List<PriceRecordView> history(long userId, Commodity commodity, int limit) {
        return jdbc.sql("""
                SELECT p.id, p.price_toman, p.priced_at, p.source, p.user_id
                FROM prices p
                WHERE p.commodity_id = :commodityId AND (p.user_id IS NULL OR p.user_id = :userId)
                ORDER BY p.priced_at DESC, p.id DESC
                LIMIT :limit
                """)
                .param("commodityId", commodity.getId())
                .param("userId", userId)
                .param("limit", Math.min(Math.max(limit, 1), 1000))
                .query((rs, n) -> new PriceRecordView(rs.getLong(1), commodity.getCode(), rs.getBigDecimal(2),
                        rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getObject(5) != null))
                .list();
    }

    /** Records a price entered by hand: personal, or instance-wide when {@code global} (admins only). */
    @Transactional
    public Price recordManual(long userId, Commodity commodity, BigDecimal priceToman, Instant pricedAt, boolean global) {
        validate(commodity, priceToman);
        Instant at = pricedAt == null ? clock.instant() : pricedAt;
        if (at.isAfter(clock.instant().plusSeconds(3600))) {
            throw ApiException.badRequest("price.future");
        }
        return prices.save(new Price(commodity.getId(), global ? null : userId, normalize(priceToman), at, Price.SOURCE_MANUAL, null));
    }

    private record Fetched(BigDecimal price, Instant at) {
    }

    /**
     * Records a price fetched from an automatic source (instance-wide), unless the source's last
     * price for the commodity is the same and younger than {@code confirmEvery}: an unchanged
     * price is re-recorded only that often, enough to show it is current. Returns whether a row was added.
     */
    @Transactional
    public boolean recordFetched(Commodity commodity, BigDecimal priceToman, Instant pricedAt, String source, Duration confirmEvery) {
        validate(commodity, priceToman);
        BigDecimal price = normalize(priceToman);
        boolean unchanged = jdbc.sql("""
                        SELECT price_toman, priced_at FROM prices
                        WHERE commodity_id = ? AND user_id IS NULL AND source = ?
                        ORDER BY priced_at DESC, id DESC LIMIT 1
                        """)
                .params(commodity.getId(), source)
                .query((rs, n) -> new Fetched(rs.getBigDecimal(1), rs.getTimestamp(2).toInstant()))
                .optional()
                .filter(last -> last.price().compareTo(price) == 0 && last.at().isAfter(pricedAt.minus(confirmEvery)))
                .isPresent();
        if (unchanged) {
            return false;
        }
        prices.save(new Price(commodity.getId(), null, price, pricedAt, source, null));
        return true;
    }

    /**
     * Thins fetched prices older than {@code keepAll} to the last one of each day (in {@code zone})
     * per commodity and source, so years of half-hourly fetches stay small. Manual and implied
     * prices are never touched. Returns the number of rows removed.
     */
    @Transactional
    public int thinFetched(Duration keepAll, ZoneId zone) {
        return jdbc.sql("""
                DELETE FROM prices p USING (
                    SELECT id, row_number() OVER (
                        PARTITION BY commodity_id, source, (priced_at AT TIME ZONE :zone)::date
                        ORDER BY priced_at DESC, id DESC) AS rn
                    FROM prices
                    WHERE user_id IS NULL AND source NOT IN ('MANUAL', 'TRANSACTION') AND priced_at < :before
                ) old
                WHERE p.id = old.id AND old.rn > 1
                """)
                .param("zone", zone.getId())
                .param("before", Timestamp.from(clock.instant().minus(keepAll)))
                .update();
    }

    /** Records the price implied by an exchange transaction (e.g. a coin bought for 80M Toman). */
    @Transactional
    public void recordImplied(long userId, long commodityId, BigDecimal priceToman, Instant pricedAt, long transactionId) {
        if (priceToman.signum() > 0 && priceToman.compareTo(MAX_PRICE) < 0) {
            prices.save(new Price(commodityId, userId, normalize(priceToman), pricedAt, Price.SOURCE_TRANSACTION, transactionId));
        }
    }

    @Transactional
    public void deleteForTransaction(long transactionId) {
        prices.deleteByTransactionId(transactionId);
    }

    @Transactional
    public void delete(long userId, long priceId, boolean admin) {
        Price price = prices.findById(priceId).orElseThrow(() -> ApiException.notFound("price.notFound"));
        boolean own = price.getUserId() != null && price.getUserId() == userId;
        boolean globalManual = price.getUserId() == null && admin;
        if (!own && !globalManual) {
            throw ApiException.notFound("price.notFound");
        }
        prices.delete(price);
    }

    private volatile Set<Long> tomanIds;

    private Set<Long> tomanIds() {
        Set<Long> ids = tomanIds;
        if (ids == null) {
            Set<Long> found = new HashSet<>();
            commodities.findBuiltIn(Commodity.TOMAN).ifPresent(c -> found.add(c.getId()));
            ids = Set.copyOf(found);
            tomanIds = ids;
        }
        return ids;
    }

    private static void validate(Commodity commodity, BigDecimal priceToman) {
        if (commodity.isToman()) {
            throw ApiException.badRequest("price.tomanFixed");
        }
        if (priceToman == null || priceToman.signum() <= 0 || priceToman.compareTo(MAX_PRICE) >= 0) {
            throw ApiException.badRequest("price.invalid");
        }
    }

    private static BigDecimal normalize(BigDecimal price) {
        return price.setScale(Math.min(Math.max(price.scale(), 0), 8), RoundingMode.HALF_EVEN);
    }
}
