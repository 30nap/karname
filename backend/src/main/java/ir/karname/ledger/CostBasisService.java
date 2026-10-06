package ir.karname.ledger;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.PricePoint;
import ir.karname.commodity.PriceService;
import ir.karname.commodity.PriceTimeline;
import ir.karname.common.config.KarnameProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Average cost and profit/loss of a non-Toman holding (gold, currency, crypto, funds), using the
 * weighted-average-cost method: buying adds to the cost basis, selling/spending removes the
 * average cost of the units disposed and realizes the difference to their value at that time.
 */
@Service
public class CostBasisService {

    private final AccountService accounts;
    private final CommodityRepository commodities;
    private final PriceService prices;
    private final JdbcClient jdbc;
    private final KarnameProperties properties;
    private final Clock clock;

    public CostBasisService(AccountService accounts, CommodityRepository commodities, PriceService prices, JdbcClient jdbc,
            KarnameProperties properties, Clock clock) {
        this.accounts = accounts;
        this.commodities = commodities;
        this.prices = prices;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param costComplete false when some acquisitions had no known Toman value (cost basis is then a lower bound)
     */
    public record CostBasisView(long accountId, String commodity, BigDecimal quantity, BigDecimal averageCostToman,
            BigDecimal costBasisToman, BigDecimal marketValueToman, BigDecimal unrealizedToman, BigDecimal realizedToman,
            boolean costComplete) {
    }

    /** One movement of the holding with the Toman value that crossed the account boundary (null if unknown). */
    record Movement(LocalDate date, BigDecimal quantity, BigDecimal tomanValue) {
    }

    @Transactional(readOnly = true)
    public Optional<CostBasisView> forAccount(long userId, long accountId) {
        Account account = accounts.require(userId, accountId);
        Commodity commodity = commodities.findById(account.getCommodityId()).orElseThrow();
        if (commodity.isToman() || account.getType().isLiability()) {
            return Optional.empty();
        }
        record Row(String type, LocalDate date, long accountId, BigDecimal amount, Long toAccountId, BigDecimal toAmount,
                BigDecimal fee, long fromCommodity, Long toCommodity) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT t.type, t.occurred_on, t.account_id, t.amount, t.to_account_id, t.to_amount, t.fee,
                       a.commodity_id, ta.commodity_id
                FROM transactions t
                JOIN accounts a ON a.id = t.account_id
                LEFT JOIN accounts ta ON ta.id = t.to_account_id
                WHERE t.user_id = :userId AND (t.account_id = :accountId OR t.to_account_id = :accountId)
                ORDER BY t.occurred_on, t.id
                """)
                .param("userId", userId)
                .param("accountId", accountId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getLong(3), rs.getBigDecimal(4),
                        (Long) rs.getObject(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getLong(8), (Long) rs.getObject(9)))
                .list();

        Set<Long> ids = new HashSet<>();
        ids.add(commodity.getId());
        rows.forEach(r -> {
            ids.add(r.fromCommodity());
            if (r.toCommodity() != null) {
                ids.add(r.toCommodity());
            }
        });
        PriceTimeline timeline = prices.timeline(userId, ids);

        List<Movement> movements = rows.stream().map(r -> {
            Instant at = endOfDay(r.date());
            boolean incoming = r.toAccountId() != null && r.toAccountId() == accountId;
            if ("TRANSFER".equals(r.type())) {
                if (incoming) {
                    // paid with the source account's commodity
                    return new Movement(r.date(), r.toAmount(), value(timeline, r.fromCommodity(), r.amount(), at));
                }
                BigDecimal quantity = r.amount().add(r.fee() == null ? BigDecimal.ZERO : r.fee());
                return new Movement(r.date(), quantity.negate(), value(timeline, r.toCommodity(), r.toAmount(), at));
            }
            BigDecimal signed = switch (r.type()) {
                case "INCOME" -> r.amount();
                case "EXPENSE" -> r.amount().negate();
                default -> r.amount(); // OPENING / ADJUSTMENT are signed
            };
            return new Movement(r.date(), signed, value(timeline, commodity.getId(), signed.abs(), at));
        }).toList();

        Optional<PricePoint> currentPrice = timeline.priceAt(commodity.getId(), clock.instant());
        return Optional.of(compute(accountId, commodity.getCode(), movements, currentPrice.map(PricePoint::priceToman).orElse(null)));
    }

    /** Weighted-average-cost computation over the account's movements (oldest first). */
    static CostBasisView compute(long accountId, String commodity, List<Movement> movements, BigDecimal currentPrice) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        boolean complete = true;
        for (Movement m : movements) {
            if (m.quantity().signum() > 0) {
                quantity = quantity.add(m.quantity());
                if (m.tomanValue() == null) {
                    complete = false;
                } else {
                    cost = cost.add(m.tomanValue());
                }
            } else if (m.quantity().signum() < 0) {
                BigDecimal disposed = m.quantity().negate();
                if (quantity.signum() <= 0) {
                    quantity = quantity.subtract(disposed);
                    continue;
                }
                BigDecimal fraction = disposed.min(quantity).divide(quantity, 12, RoundingMode.HALF_EVEN);
                BigDecimal costOut = cost.multiply(fraction);
                cost = cost.subtract(costOut);
                quantity = quantity.subtract(disposed);
                if (m.tomanValue() != null) {
                    realized = realized.add(m.tomanValue().subtract(costOut));
                }
            }
        }
        if (quantity.signum() <= 0) {
            cost = BigDecimal.ZERO;
        }
        BigDecimal average = quantity.signum() > 0 ? cost.divide(quantity, 2, RoundingMode.HALF_EVEN) : null;
        BigDecimal market = currentPrice == null ? null : quantity.multiply(currentPrice);
        BigDecimal unrealized = market == null || quantity.signum() <= 0 ? null : market.subtract(cost);
        return new CostBasisView(accountId, commodity, quantity.stripTrailingZeros(), average, round(cost),
                market == null ? null : round(market), unrealized == null ? null : round(unrealized), round(realized), complete);
    }

    private BigDecimal value(PriceTimeline timeline, Long commodityId, BigDecimal quantity, Instant at) {
        if (commodityId == null || quantity == null) {
            return null;
        }
        return timeline.priceAt(commodityId, at).map(p -> quantity.multiply(p.priceToman())).orElse(null);
    }

    private Instant endOfDay(LocalDate date) {
        Instant end = date.atTime(LocalTime.MAX).atZone(properties.timezone()).toInstant();
        Instant now = clock.instant();
        return end.isAfter(now) ? now : end;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_EVEN);
    }
}
