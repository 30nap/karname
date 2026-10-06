package ir.karname.transaction;

import ir.karname.category.Category;
import ir.karname.category.CategoryService;
import ir.karname.commodity.PriceService;
import ir.karname.commodity.PriceTimeline;
import ir.karname.common.config.KarnameProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Income and expense flows valued in Toman at the price of their day: the input of reports,
 * budgets and analytics. Transfer fees are expenses in the bank-fees category.
 */
@Service
public class FlowValuation {

    public static final String BANK_FEES_KEY = "bank_fees";

    private final JdbcClient jdbc;
    private final PriceService prices;
    private final CategoryService categories;
    private final KarnameProperties properties;

    public FlowValuation(JdbcClient jdbc, PriceService prices, CategoryService categories, KarnameProperties properties) {
        this.jdbc = jdbc;
        this.prices = prices;
        this.categories = categories;
        this.properties = properties;
    }

    public enum FlowKind {
        INCOME, EXPENSE
    }

    /**
     * One income or expense. {@code valueToman} is null when the account's commodity had no price
     * on that day; {@code fee} marks a transfer fee.
     */
    public record Flow(long transactionId, LocalDate date, FlowKind kind, Long categoryId, long accountId, long commodityId,
            BigDecimal amount, BigDecimal valueToman, String description, boolean fee) {

        public boolean priced() {
            return valueToman != null;
        }
    }

    private record Row(long id, LocalDate date, TransactionType type, Long categoryId, long accountId, long commodityId,
            BigDecimal amount, BigDecimal fee, String description) {
    }

    /** All flows dated within [from, to], oldest first. */
    @Transactional(readOnly = true)
    public List<Flow> flows(long userId, LocalDate from, LocalDate to) {
        List<Row> rows = jdbc.sql("""
                SELECT t.id, t.occurred_on, t.type, t.category_id, t.account_id, a.commodity_id, t.amount, t.fee, t.description
                FROM transactions t JOIN accounts a ON a.id = t.account_id
                WHERE t.user_id = :userId AND t.occurred_on BETWEEN :from AND :to
                  AND (t.type IN ('INCOME', 'EXPENSE') OR (t.type = 'TRANSFER' AND t.fee > 0))
                ORDER BY t.occurred_on, t.id
                """)
                .param("userId", userId)
                .param("from", from)
                .param("to", to)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getDate(2).toLocalDate(), TransactionType.valueOf(rs.getString(3)),
                        (Long) rs.getObject(4), rs.getLong(5), rs.getLong(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getString(9)))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> commodityIds = new HashSet<>();
        rows.forEach(r -> commodityIds.add(r.commodityId()));
        PriceTimeline timeline = prices.timeline(userId, commodityIds);
        Long feeCategory = categories.bySystemKey(userId, BANK_FEES_KEY).map(Category::getId).orElse(null);
        return rows.stream().map(r -> {
            boolean fee = r.type() == TransactionType.TRANSFER;
            BigDecimal amount = fee ? r.fee() : r.amount();
            BigDecimal value = timeline.priceAt(r.commodityId(), r.date().atTime(LocalTime.MAX).atZone(properties.timezone()).toInstant())
                    .map(p -> amount.multiply(p.priceToman()))
                    .orElse(null);
            FlowKind kind = r.type() == TransactionType.INCOME ? FlowKind.INCOME : FlowKind.EXPENSE;
            return new Flow(r.id(), r.date(), kind, fee ? feeCategory : r.categoryId(), r.accountId(), r.commodityId(), amount, value,
                    r.description(), fee);
        }).toList();
    }
}
