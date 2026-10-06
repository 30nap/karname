package ir.karname.account;

import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.PricePoint;
import ir.karname.commodity.PriceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds account views with current balance and Toman value. */
@Component
public class AccountViews {

    private final BalanceService balances;
    private final PriceService prices;
    private final CommodityService commodityService;
    private final CommodityRepository commodities;
    private final Clock clock;

    public AccountViews(BalanceService balances, PriceService prices, CommodityService commodityService,
            CommodityRepository commodities, Clock clock) {
        this.balances = balances;
        this.prices = prices;
        this.commodityService = commodityService;
        this.commodities = commodities;
        this.clock = clock;
    }

    public record AccountView(long id, String name, AccountType type, boolean liability, String commodity, Bank bank,
            List<String> identifierHints, String counterparty, String icon, boolean includeInNetWorth, boolean archived,
            String notes, int sortOrder, BigDecimal balance, BigDecimal valueToman, boolean priced, boolean priceStale,
            Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<AccountView> views(long userId, Collection<Account> accounts) {
        LocalDate today = LocalDate.now(clock);
        Map<Long, BigDecimal> balanceMap = balances.balances(userId, today);
        Map<Long, Commodity> commodityMap = commodities.findAllById(accounts.stream().map(Account::getCommodityId).distinct().toList())
                .stream().collect(Collectors.toMap(Commodity::getId, Function.identity()));
        Map<Long, PricePoint> priceMap = prices.latestPrices(userId, commodityMap.keySet(), clock.instant());
        return accounts.stream().map(a -> {
            Commodity c = commodityMap.get(a.getCommodityId());
            BigDecimal balance = balanceMap.getOrDefault(a.getId(), BigDecimal.ZERO);
            PricePoint price = priceMap.get(a.getCommodityId());
            BigDecimal value = price == null ? null : balance.multiply(price.priceToman()).setScale(0, RoundingMode.HALF_EVEN);
            boolean stale = price != null && !c.isToman() && commodityService.isStale(c, price.pricedAt());
            return new AccountView(a.getId(), a.getName(), a.getType(), a.getType().isLiability(), c.getCode(), a.getBank(),
                    a.getIdentifierHints(), a.getCounterparty(), a.getIcon(), a.isIncludeInNetWorth(), a.isArchived(), a.getNotes(),
                    a.getSortOrder(), balance, value, price != null, stale, a.getCreatedAt());
        }).toList();
    }

    public AccountView view(long userId, Account account) {
        return views(userId, List.of(account)).getFirst();
    }
}
