package ir.karname.ledger;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.BalanceService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.PricePoint;
import ir.karname.commodity.PriceService;
import ir.karname.commodity.PriceTimeline;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.user.UserService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Net worth: every account's balance valued in Toman at the latest price, also expressed in
 * the user's alternative wealth units (e.g. dollars, grams of gold) to see through inflation.
 */
@Service
public class NetWorthService {

    private final AccountService accounts;
    private final BalanceService balances;
    private final CommodityRepository commodities;
    private final CommodityService commodityService;
    private final PriceService prices;
    private final UserService users;
    private final JdbcClient jdbc;
    private final KarnameProperties properties;
    private final Clock clock;

    public NetWorthService(AccountService accounts, BalanceService balances, CommodityRepository commodities,
            CommodityService commodityService, PriceService prices, UserService users, JdbcClient jdbc,
            KarnameProperties properties, Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.commodities = commodities;
        this.commodityService = commodityService;
        this.prices = prices;
        this.users = users;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public record AlternativeValue(String code, String nameFa, String unitFa, BigDecimal value, boolean stale) {
    }

    public record AllocationSlice(AssetClass assetClass, BigDecimal valueToman, BigDecimal share) {
    }

    public record UnpricedAccount(long accountId, String name, String commodity, BigDecimal balance) {
    }

    public record NetWorthView(LocalDate asOf, BigDecimal totalToman, BigDecimal assetsToman, BigDecimal liabilitiesToman,
            List<AlternativeValue> alternatives, List<AllocationSlice> allocation, List<UnpricedAccount> unpriced) {
    }

    public record HistoryPoint(String month, LocalDate date, BigDecimal totalToman, BigDecimal assetsToman,
            BigDecimal liabilitiesToman, Map<String, BigDecimal> alternatives, int unpricedAccounts) {
    }

    @Transactional(readOnly = true)
    public NetWorthView current(long userId) {
        LocalDate today = LocalDate.now(clock);
        List<Account> included = accounts.list(userId).stream().filter(Account::isIncludeInNetWorth).toList();
        Map<Long, BigDecimal> balanceMap = balances.balances(userId, today);
        Map<Long, Commodity> commodityMap = commodityMap(included);
        List<Commodity> wealthUnits = wealthUnits(userId);

        Set<Long> priceIds = new HashSet<>(commodityMap.keySet());
        wealthUnits.forEach(c -> priceIds.add(c.getId()));
        Map<Long, PricePoint> latest = prices.latestPrices(userId, priceIds, clock.instant());

        BigDecimal assets = BigDecimal.ZERO;
        BigDecimal liabilities = BigDecimal.ZERO;
        Map<AssetClass, BigDecimal> byClass = new EnumMap<>(AssetClass.class);
        List<UnpricedAccount> unpriced = new ArrayList<>();
        for (Account a : included) {
            BigDecimal balance = balanceMap.getOrDefault(a.getId(), BigDecimal.ZERO);
            if (balance.signum() == 0) {
                continue;
            }
            Commodity c = commodityMap.get(a.getCommodityId());
            PricePoint price = latest.get(c.getId());
            if (price == null) {
                unpriced.add(new UnpricedAccount(a.getId(), a.getName(), c.getCode(), balance));
                continue;
            }
            BigDecimal value = balance.multiply(price.priceToman());
            if (a.getType().isLiability()) {
                liabilities = liabilities.add(value.negate());
            } else {
                assets = assets.add(value);
                if (value.signum() > 0) {
                    byClass.merge(AssetClass.of(c.getKind()), value, BigDecimal::add);
                }
            }
        }
        BigDecimal total = assets.subtract(liabilities);
        BigDecimal positiveAssets = byClass.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AllocationSlice> allocation = new ArrayList<>();
        for (AssetClass assetClass : AssetClass.values()) {
            BigDecimal value = byClass.get(assetClass);
            if (value != null && value.signum() > 0) {
                BigDecimal share = value.divide(positiveAssets, 4, RoundingMode.HALF_EVEN);
                allocation.add(new AllocationSlice(assetClass, round(value), share));
            }
        }
        List<AlternativeValue> alternatives = new ArrayList<>();
        for (Commodity unit : wealthUnits) {
            PricePoint price = latest.get(unit.getId());
            if (price != null && price.priceToman().signum() > 0) {
                BigDecimal value = total.divide(price.priceToman(), Math.max(unit.getScale(), 2), RoundingMode.HALF_EVEN);
                alternatives.add(new AlternativeValue(unit.getCode(), unit.getNameFa(), unit.getUnitFa(), value,
                        commodityService.isStale(unit, price.pricedAt())));
            }
        }
        return new NetWorthView(today, round(total), round(assets), round(liabilities), alternatives, allocation, unpriced);
    }

    /** Net worth at the end of each of the last {@code months} Jalali months (the current one ends today). */
    @Transactional(readOnly = true)
    public List<HistoryPoint> history(long userId, int months) {
        int count = Math.min(Math.max(months, 1), 120);
        LocalDate today = LocalDate.now(clock);
        JalaliMonth current = JalaliMonth.from(today);
        List<Account> included = accounts.list(userId).stream().filter(Account::isIncludeInNetWorth).toList();
        Map<Long, Account> accountMap = included.stream().collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, Commodity> commodityMap = commodityMap(included);
        List<Commodity> wealthUnits = wealthUnits(userId);
        Set<Long> priceIds = new HashSet<>(commodityMap.keySet());
        wealthUnits.forEach(c -> priceIds.add(c.getId()));
        PriceTimeline timeline = prices.timeline(userId, priceIds);

        record Posting(long accountId, LocalDate date, BigDecimal delta) {
        }
        List<Posting> postings = jdbc.sql("""
                SELECT account_id, occurred_on, delta FROM ledger_postings
                WHERE user_id = :userId AND occurred_on <= :today
                ORDER BY occurred_on
                """)
                .param("userId", userId)
                .param("today", today)
                .query((rs, n) -> new Posting(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)))
                .list();

        Map<Long, BigDecimal> running = new HashMap<>();
        int index = 0;
        List<HistoryPoint> points = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            JalaliMonth month = current.plusMonths(-i);
            LocalDate end = i == 0 ? today : month.endDate();
            while (index < postings.size() && !postings.get(index).date().isAfter(end)) {
                Posting p = postings.get(index++);
                if (accountMap.containsKey(p.accountId())) {
                    running.merge(p.accountId(), p.delta(), BigDecimal::add);
                }
            }
            Instant at = i == 0 ? clock.instant() : end.atTime(LocalTime.MAX).atZone(properties.timezone()).toInstant();
            BigDecimal assets = BigDecimal.ZERO;
            BigDecimal liabilities = BigDecimal.ZERO;
            int unpriced = 0;
            for (Map.Entry<Long, BigDecimal> e : running.entrySet()) {
                if (e.getValue().signum() == 0) {
                    continue;
                }
                Account account = accountMap.get(e.getKey());
                Optional<PricePoint> price = timeline.priceAt(account.getCommodityId(), at);
                if (price.isEmpty()) {
                    unpriced++;
                    continue;
                }
                BigDecimal value = e.getValue().multiply(price.get().priceToman());
                if (account.getType().isLiability()) {
                    liabilities = liabilities.add(value.negate());
                } else {
                    assets = assets.add(value);
                }
            }
            BigDecimal total = assets.subtract(liabilities);
            Map<String, BigDecimal> alternatives = new LinkedHashMap<>();
            for (Commodity unit : wealthUnits) {
                timeline.priceAt(unit.getId(), at).ifPresent(p -> alternatives.put(unit.getCode(),
                        total.divide(p.priceToman(), Math.max(unit.getScale(), 2), RoundingMode.HALF_EVEN)));
            }
            points.add(new HistoryPoint(month.toString(), end, round(total), round(assets), round(liabilities), alternatives, unpriced));
        }
        return points;
    }

    private Map<Long, Commodity> commodityMap(List<Account> list) {
        return commodities.findAllById(list.stream().map(Account::getCommodityId).distinct().toList())
                .stream().collect(Collectors.toMap(Commodity::getId, Function.identity()));
    }

    private List<Commodity> wealthUnits(long userId) {
        List<Commodity> result = new ArrayList<>();
        for (String code : users.settings(userId).getWealthUnits()) {
            commodities.findVisibleByCode(userId, code).filter(c -> !c.isToman()).ifPresent(result::add);
        }
        return result;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_EVEN);
    }
}
