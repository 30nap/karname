package ir.karname.ledger;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityKind;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.PricePoint;
import ir.karname.commodity.PriceService;
import ir.karname.ledger.CostBasisService.CostBasisView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The securities a user holds, one account per share or fund, seen together as a portfolio: how
 * many, at what average cost, what they are worth now and the gain or loss, with totals.
 */
@Service
public class PortfolioService {

    private final AccountService accounts;
    private final CommodityRepository commodities;
    private final CommodityService commodityService;
    private final PriceService prices;
    private final CostBasisService costBasis;
    private final Clock clock;

    public PortfolioService(AccountService accounts, CommodityRepository commodities, CommodityService commodityService, PriceService prices,
            CostBasisService costBasis, Clock clock) {
        this.accounts = accounts;
        this.commodities = commodities;
        this.commodityService = commodityService;
        this.prices = prices;
        this.costBasis = costBasis;
        this.clock = clock;
    }

    /**
     * One holding: {@code symbol} is the exchange symbol when the unit follows one; {@code costComplete}
     * false when some purchase could not be valued (its cost is then too low).
     */
    public record HoldingView(long accountId, String accountName, String commodity, String nameFa, String symbol, BigDecimal quantity,
            BigDecimal averageCostToman, BigDecimal costBasisToman, BigDecimal priceToman, Instant pricedAt, boolean priceStale,
            BigDecimal marketValueToman, BigDecimal unrealizedToman, BigDecimal realizedToman, boolean costComplete) {
    }

    /** Totals over the holdings with a price; {@code unpriced}: holdings left out of the value for want of one. */
    public record PortfolioView(List<HoldingView> holdings, BigDecimal costBasisToman, BigDecimal marketValueToman, BigDecimal unrealizedToman,
            BigDecimal realizedToman, int unpriced, boolean costComplete) {
    }

    @Transactional(readOnly = true)
    public PortfolioView securities(long userId) {
        List<Account> held = accounts.list(userId).stream().filter(a -> !a.isArchived() && !a.getType().isLiability()).toList();
        Map<Long, Commodity> units = commodities.findAllById(held.stream().map(Account::getCommodityId).distinct().toList()).stream()
                .filter(c -> c.getKind() == CommodityKind.SECURITY)
                .collect(Collectors.toMap(Commodity::getId, Function.identity()));
        Map<Long, PricePoint> latest = prices.latestPrices(userId, units.keySet(), clock.instant());
        List<HoldingView> holdings = new ArrayList<>();
        for (Account account : held) {
            Commodity unit = units.get(account.getCommodityId());
            if (unit == null) {
                continue;
            }
            CostBasisView basis = costBasis.forAccount(userId, account.getId()).orElse(null);
            if (basis == null || basis.quantity().signum() <= 0 && basis.realizedToman().signum() == 0) {
                continue;
            }
            PricePoint price = latest.get(unit.getId());
            holdings.add(new HoldingView(account.getId(), account.getName(), unit.getCode(), unit.getNameFa(), unit.getStockSymbol(),
                    basis.quantity(), basis.averageCostToman(), basis.costBasisToman(), price == null ? null : price.priceToman(),
                    price == null ? null : price.pricedAt(), price != null && commodityService.isStale(unit, price.pricedAt()),
                    basis.marketValueToman(), basis.unrealizedToman(), basis.realizedToman(), basis.costComplete()));
        }
        holdings.sort(Comparator.comparing((HoldingView h) -> h.marketValueToman() == null ? BigDecimal.ZERO : h.marketValueToman()).reversed());
        List<HoldingView> priced = holdings.stream().filter(h -> h.marketValueToman() != null).toList();
        return new PortfolioView(holdings,
                sum(priced.stream().map(HoldingView::costBasisToman).toList()),
                sum(priced.stream().map(HoldingView::marketValueToman).toList()),
                sum(priced.stream().map(HoldingView::unrealizedToman).toList()),
                sum(holdings.stream().map(HoldingView::realizedToman).toList()),
                (int) holdings.stream().filter(h -> h.marketValueToman() == null && h.quantity().signum() > 0).count(),
                holdings.stream().allMatch(HoldingView::costComplete));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
