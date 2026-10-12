package ir.karname.pricefeed;

import ir.karname.pricefeed.PriceSelection.Quote;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The latest quote of every source for every commodity it publishes (table price_quotes). */
@Component
class PriceQuotes {

    /** Serializes recording, so a run started by hand and a scheduled one never choose at the same time. */
    private static final long LOCK = 0x4b61726e616d6501L;

    private static final String SELECT = """
            SELECT q.commodity_id, q.source_id, s.name, s.priority, s.enabled, q.fresh_until, q.price_toman, q.fetched_at
            FROM price_quotes q JOIN price_sources s ON s.id = q.source_id
            """;

    private final JdbcClient jdbc;

    PriceQuotes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the recording transaction; held until it ends. */
    void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(LOCK).query((rs, n) -> 1).list();
    }

    /** Keeps a source's latest quote; an older one (from a run that started earlier) does not replace a newer one. */
    void store(long sourceId, long commodityId, BigDecimal priceToman, Instant fetchedAt, Instant freshUntil) {
        jdbc.sql("""
                        INSERT INTO price_quotes (source_id, commodity_id, price_toman, fetched_at, fresh_until) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (source_id, commodity_id) DO UPDATE
                        SET price_toman = EXCLUDED.price_toman, fetched_at = EXCLUDED.fetched_at, fresh_until = EXCLUDED.fresh_until
                        WHERE price_quotes.fetched_at <= EXCLUDED.fetched_at
                        """)
                .params(sourceId, commodityId, priceToman, Timestamp.from(fetchedAt), Timestamp.from(freshUntil))
                .update();
    }

    /** Forgets a source's quotes: all of them, or those of the given commodities. */
    void forget(long sourceId, Collection<Long> commodityIds) {
        if (commodityIds == null) {
            jdbc.sql("DELETE FROM price_quotes WHERE source_id = ?").param(sourceId).update();
        } else if (!commodityIds.isEmpty()) {
            jdbc.sql("DELETE FROM price_quotes WHERE source_id = :source AND commodity_id IN (:ids)")
                    .param("source", sourceId).param("ids", commodityIds).update();
        }
    }

    /** Forgets a source's quotes for commodities it no longer publishes. */
    void forgetAllBut(long sourceId, Collection<Long> commodityIds) {
        if (commodityIds.isEmpty()) {
            forget(sourceId, null);
            return;
        }
        jdbc.sql("DELETE FROM price_quotes WHERE source_id = :source AND commodity_id NOT IN (:ids)")
                .param("source", sourceId).param("ids", commodityIds).update();
    }

    List<Quote> forCommodity(long commodityId) {
        return jdbc.sql(SELECT + " WHERE q.commodity_id = ?").param(commodityId).query((rs, n) -> quote(rs)).list();
    }

    /** Every quote, grouped by commodity code, commodities in their usual order. */
    Map<String, List<Quote>> all() {
        Map<String, List<Quote>> result = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT q.commodity_id, q.source_id, s.name, s.priority, s.enabled, q.fresh_until, q.price_toman, q.fetched_at, c.code
                        FROM price_quotes q JOIN price_sources s ON s.id = q.source_id JOIN commodities c ON c.id = q.commodity_id
                        ORDER BY c.sort_order, c.id
                        """)
                .query((rs, n) -> Map.entry(rs.getString(9), quote(rs))).list()
                .forEach(e -> result.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return result;
    }

    private static Quote quote(ResultSet rs) throws SQLException {
        return new Quote(rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5), rs.getBigDecimal(7), rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(6).toInstant());
    }
}
