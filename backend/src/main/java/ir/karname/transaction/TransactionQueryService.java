package ir.karname.transaction;

import ir.karname.commodity.PriceService;
import ir.karname.commodity.PriceTimeline;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.persian.PersianNumbers;
import ir.karname.common.persian.PersianText;
import ir.karname.transaction.TransactionViews.TransactionView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Filtered, paginated transaction search with income/expense totals in Toman. */
@Service
public class TransactionQueryService {

    private final JdbcClient jdbc;
    private final TransactionRepository transactions;
    private final TransactionViews views;
    private final PriceService prices;
    private final KarnameProperties properties;

    public TransactionQueryService(JdbcClient jdbc, TransactionRepository transactions, TransactionViews views,
            PriceService prices, KarnameProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.views = views;
        this.prices = prices;
        this.properties = properties;
    }

    public record Filter(LocalDate from, LocalDate to, List<TransactionType> types, Long accountId, Long categoryId,
            boolean uncategorized, String q, BigDecimal minAmount, BigDecimal maxAmount, TransactionSource source) {

        public static Filter between(LocalDate from, LocalDate to) {
            return new Filter(from, to, null, null, null, false, null, null, null, null);
        }
    }

    public record TransactionPage(List<TransactionView> items, int page, int size, long total, BigDecimal incomeToman,
            BigDecimal expenseToman, int unpricedCount) {
    }

    private record Where(String sql, Map<String, Object> params) {
    }

    @Transactional(readOnly = true)
    public TransactionPage search(long userId, Filter filter, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 200);
        int pageIndex = Math.max(page, 0);
        Where where = where(userId, filter);

        long total = bind(jdbc.sql("SELECT count(*) FROM transactions t WHERE " + where.sql()), where.params())
                .query(Long.class).single();
        List<Long> ids = bind(jdbc.sql("SELECT t.id FROM transactions t WHERE " + where.sql()
                        + " ORDER BY t.occurred_on DESC, t.id DESC LIMIT :limit OFFSET :offset"), where.params())
                .param("limit", pageSize)
                .param("offset", (long) pageIndex * pageSize)
                .query(Long.class).list();
        Map<Long, Transaction> byId = transactions.findAllById(ids).stream().collect(Collectors.toMap(Transaction::getId, Function.identity()));
        TransactionViews.Context ctx = views.context(userId);
        List<TransactionView> items = ids.stream().map(byId::get).map(ctx::view).toList();

        Totals totals = totals(userId, where);
        return new TransactionPage(items, pageIndex, pageSize, total, totals.income(), totals.expense(), totals.unpriced());
    }

    /** Every matching transaction (no paging), oldest first; for exports and analytics. */
    @Transactional(readOnly = true)
    public List<Transaction> findAll(long userId, Filter filter) {
        Where where = where(userId, filter);
        List<Long> ids = bind(jdbc.sql("SELECT t.id FROM transactions t WHERE " + where.sql() + " ORDER BY t.occurred_on, t.id"),
                where.params()).query(Long.class).list();
        Map<Long, Transaction> byId = transactions.findAllById(ids).stream().collect(Collectors.toMap(Transaction::getId, Function.identity()));
        return ids.stream().map(byId::get).toList();
    }

    private record Totals(BigDecimal income, BigDecimal expense, int unpriced) {
    }

    private record Flow(TransactionType type, BigDecimal amount, long commodityId, LocalDate date) {
    }

    /**
     * Income and expense of the matching transactions in Toman. Amounts in other commodities are
     * converted at the price on their date; transfer fees count as expenses.
     */
    private Totals totals(long userId, Where where) {
        List<Flow> flows = bind(jdbc.sql("""
                SELECT t.type, t.amount, t.fee, a.commodity_id, t.occurred_on
                FROM transactions t JOIN accounts a ON a.id = t.account_id
                WHERE (t.type IN ('INCOME', 'EXPENSE') OR (t.type = 'TRANSFER' AND t.fee IS NOT NULL)) AND\s""" + where.sql()),
                where.params())
                .query((rs, n) -> {
                    TransactionType type = TransactionType.valueOf(rs.getString(1));
                    BigDecimal amount = type == TransactionType.TRANSFER ? rs.getBigDecimal(3) : rs.getBigDecimal(2);
                    return new Flow(type == TransactionType.TRANSFER ? TransactionType.EXPENSE : type, amount, rs.getLong(4),
                            rs.getDate(5).toLocalDate());
                })
                .list();
        Set<Long> commodityIds = new HashSet<>();
        flows.forEach(f -> commodityIds.add(f.commodityId()));
        PriceTimeline timeline = prices.timeline(userId, commodityIds);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        int unpriced = 0;
        for (Flow f : flows) {
            Optional<BigDecimal> toman = timeline.priceAt(f.commodityId(), f.date().atTime(LocalTime.MAX).atZone(properties.timezone()).toInstant())
                    .map(p -> f.amount().multiply(p.priceToman()));
            if (toman.isEmpty()) {
                unpriced++;
                continue;
            }
            if (f.type() == TransactionType.INCOME) {
                income = income.add(toman.get());
            } else {
                expense = expense.add(toman.get());
            }
        }
        return new Totals(income.setScale(0, RoundingMode.HALF_EVEN), expense.setScale(0, RoundingMode.HALF_EVEN), unpriced);
    }

    private Where where(long userId, Filter f) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        clauses.add("t.user_id = :userId");
        params.put("userId", userId);
        if (f.from() != null) {
            clauses.add("t.occurred_on >= :from");
            params.put("from", f.from());
        }
        if (f.to() != null) {
            clauses.add("t.occurred_on <= :to");
            params.put("to", f.to());
        }
        if (f.types() != null && !f.types().isEmpty()) {
            clauses.add("t.type = ANY(:types)");
            params.put("types", f.types().stream().map(Enum::name).toArray(String[]::new));
        }
        if (f.accountId() != null) {
            clauses.add("(t.account_id = :accountId OR t.to_account_id = :accountId)");
            params.put("accountId", f.accountId());
        }
        if (f.categoryId() != null) {
            clauses.add("t.category_id IN (SELECT c.id FROM categories c WHERE c.user_id = :userId AND (c.id = :categoryId OR c.parent_id = :categoryId))");
            params.put("categoryId", f.categoryId());
        } else if (f.uncategorized()) {
            clauses.add("t.category_id IS NULL AND t.type IN ('INCOME', 'EXPENSE')");
        }
        if (f.q() != null && !f.q().isBlank()) {
            String normalized = PersianText.normalizeForSearch(f.q());
            Optional<BigDecimal> number = PersianNumbers.parseDecimal(f.q());
            if (number.isPresent()) {
                clauses.add("(t.amount = :qNumber OR t.to_amount = :qNumber OR t.search_text LIKE :q)");
                params.put("qNumber", number.get());
            } else {
                clauses.add("t.search_text LIKE :q");
            }
            params.put("q", "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.minAmount() != null) {
            clauses.add("t.amount >= :minAmount");
            params.put("minAmount", f.minAmount());
        }
        if (f.maxAmount() != null) {
            clauses.add("t.amount <= :maxAmount");
            params.put("maxAmount", f.maxAmount());
        }
        if (f.source() != null) {
            clauses.add("t.source = :source");
            params.put("source", f.source().name());
        }
        return new Where(String.join(" AND ", clauses), params);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Map<String, Object> params) {
        return spec.params(params);
    }
}
