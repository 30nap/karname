package ir.karname.goal;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.PricePoint;
import ir.karname.commodity.PriceService;
import ir.karname.commodity.PriceTimeline;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Savings goals. Progress is the value of the linked asset accounts in the goal's unit (or a
 * manual amount); the trend of the last six month-ends projects when the goal will be reached.
 */
@Service
public class GoalService {

    private static final int TREND_MONTHS = 6;
    private static final int MAX_ACCOUNTS = 20;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e18");
    private static final Pattern ICON = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final LocalDate MIN_DATE = LocalDate.of(1921, 3, 21);

    private final GoalRepository goals;
    private final AccountService accounts;
    private final CommodityService commodityService;
    private final CommodityRepository commodities;
    private final PriceService prices;
    private final JdbcClient jdbc;
    private final KarnameProperties properties;
    private final Clock clock;

    public GoalService(GoalRepository goals, AccountService accounts, CommodityService commodityService, CommodityRepository commodities,
            PriceService prices, JdbcClient jdbc, KarnameProperties properties, Clock clock) {
        this.goals = goals;
        this.accounts = accounts;
        this.commodityService = commodityService;
        this.commodities = commodities;
        this.prices = prices;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public record GoalRequest(String name, String icon, BigDecimal targetAmount, String commodity, LocalDate targetDate,
            List<Long> accountIds, BigDecimal manualAmount, String notes, Boolean archived) {
    }

    /**
     * A goal with its progress, in the goal's unit unless named "Toman". {@code monthlyChange} is
     * the trend of the last six month-ends; {@code etaMonth} the Jalali month it reaches the target
     * at that pace; {@code requiredPerMonth} the pace needed to meet the target date.
     */
    public record GoalView(long id, String name, String icon, BigDecimal targetAmount, String commodity, LocalDate targetDate,
            List<Long> accountIds, BigDecimal manualAmount, String notes, boolean archived, BigDecimal currentAmount,
            BigDecimal currentToman, BigDecimal progress, BigDecimal remaining, boolean achieved, BigDecimal monthlyChange,
            String etaMonth, Integer monthsToGoal, Integer monthsLeft, BigDecimal requiredPerMonth, BigDecimal requiredPerMonthToman,
            Boolean onTrack, boolean missingPrices) {
    }

    @Transactional(readOnly = true)
    public List<GoalView> list(long userId, boolean includeArchived) {
        List<Goal> list = goals.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .filter(g -> includeArchived || !g.isArchived())
                .toList();
        if (list.isEmpty()) {
            return List.of();
        }
        Valuation valuation = new Valuation(userId, list);
        return list.stream().map(valuation::view).toList();
    }

    @Transactional(readOnly = true)
    public GoalView get(long userId, long id) {
        Goal goal = require(userId, id);
        return new Valuation(userId, List.of(goal)).view(goal);
    }

    @Transactional
    public GoalView create(long userId, GoalRequest request) {
        Goal goal = new Goal(userId);
        apply(userId, goal, request);
        goal.setSortOrder((int) goals.countByUserId(userId));
        goals.saveAndFlush(goal);
        return new Valuation(userId, List.of(goal)).view(goal);
    }

    @Transactional
    public GoalView update(long userId, long id, GoalRequest request) {
        Goal goal = require(userId, id);
        apply(userId, goal, request);
        goals.flush();
        return new Valuation(userId, List.of(goal)).view(goal);
    }

    @Transactional
    public void delete(long userId, long id) {
        goals.delete(require(userId, id));
    }

    private Goal require(long userId, long id) {
        return goals.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("goal.notFound"));
    }

    private void apply(long userId, Goal goal, GoalRequest request) {
        String name = PersianText.clean(request.name());
        if (name == null || name.length() > 100) {
            throw ApiException.badRequest("goal.invalidName");
        }
        if (!validAmount(request.targetAmount()) || request.targetAmount().signum() <= 0) {
            throw ApiException.badRequest("goal.invalidTarget");
        }
        if (request.manualAmount() != null && (!validAmount(request.manualAmount()) || request.manualAmount().signum() < 0)) {
            throw ApiException.badRequest("goal.invalidManualAmount");
        }
        LocalDate today = LocalDate.now(clock);
        if (request.targetDate() != null && (request.targetDate().isBefore(MIN_DATE) || request.targetDate().isAfter(today.plusYears(100)))) {
            throw ApiException.badRequest("goal.invalidDate");
        }
        String icon = request.icon() == null || request.icon().isBlank() ? null : request.icon().trim();
        if (icon != null && !ICON.matcher(icon).matches()) {
            throw ApiException.badRequest("goal.invalidIcon");
        }
        String notes = PersianText.clean(request.notes());
        if (notes != null && notes.length() > 2000) {
            throw ApiException.badRequest("goal.invalidName");
        }
        Commodity commodity = commodityService.require(userId, request.commodity() == null ? Commodity.TOMAN : request.commodity());
        Set<Long> accountIds = new LinkedHashSet<>(request.accountIds() == null ? List.of() : request.accountIds());
        if (accountIds.size() > MAX_ACCOUNTS) {
            throw ApiException.badRequest("goal.tooManyAccounts");
        }
        for (Long accountId : accountIds) {
            if (accounts.require(userId, accountId).getType().isLiability()) {
                throw ApiException.badRequest("goal.liabilityAccount");
            }
        }
        goal.setName(name);
        goal.setIcon(icon);
        goal.setTargetAmount(request.targetAmount());
        goal.setCommodityId(commodity.getId());
        goal.setTargetDate(request.targetDate());
        goal.setManualAmount(request.manualAmount());
        goal.setNotes(notes);
        goal.setAccountIds(accountIds);
        if (request.archived() != null) {
            goal.setArchived(request.archived());
        }
    }

    private static boolean validAmount(BigDecimal amount) {
        return amount != null && amount.compareTo(MAX_AMOUNT) < 0 && amount.stripTrailingZeros().scale() <= 8;
    }

    /** Loads balances and prices once for a set of goals. */
    private final class Valuation {

        private final JalaliMonth current;
        private final Instant now;
        private final Map<Long, Account> accountMap;
        private final Map<Long, Commodity> commodityMap;
        private final PriceTimeline timeline;
        /** Month-end instants of the trend window, oldest first; the last one is now. */
        private final List<Instant> points = new ArrayList<>();
        /** Balance of each linked account at each point. */
        private final Map<Long, BigDecimal[]> balances = new HashMap<>();
        /** Index of the first point at or after an account's first posting. */
        private final Map<Long, Integer> firstPoint = new HashMap<>();

        Valuation(long userId, List<Goal> list) {
            LocalDate today = LocalDate.now(clock);
            current = JalaliMonth.from(today);
            now = clock.instant();
            Set<Long> linked = list.stream().flatMap(g -> g.getAccountIds().stream()).collect(Collectors.toSet());
            accountMap = accounts.list(userId).stream().filter(a -> linked.contains(a.getId()))
                    .collect(Collectors.toMap(Account::getId, Function.identity()));
            Set<Long> commodityIds = new HashSet<>();
            list.forEach(g -> commodityIds.add(g.getCommodityId()));
            accountMap.values().forEach(a -> commodityIds.add(a.getCommodityId()));
            commodityMap = commodities.findAllById(commodityIds).stream().collect(Collectors.toMap(Commodity::getId, Function.identity()));
            timeline = prices.timeline(userId, commodityIds);

            List<LocalDate> ends = new ArrayList<>();
            for (int i = TREND_MONTHS; i >= 1; i--) {
                LocalDate end = current.plusMonths(-i).endDate();
                ends.add(end);
                points.add(end.atTime(LocalTime.MAX).atZone(properties.timezone()).toInstant());
            }
            ends.add(today);
            points.add(now);
            if (!accountMap.isEmpty()) {
                replay(userId, ends);
            }
        }

        private void replay(long userId, List<LocalDate> ends) {
            record Posting(long accountId, LocalDate date, BigDecimal delta) {
            }
            List<Posting> postings = jdbc.sql("""
                    SELECT account_id, occurred_on, delta FROM ledger_postings
                    WHERE user_id = :userId AND account_id IN (:ids) AND occurred_on <= :today
                    ORDER BY occurred_on
                    """)
                    .param("userId", userId)
                    .param("ids", accountMap.keySet())
                    .param("today", ends.getLast())
                    .query((rs, n) -> new Posting(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)))
                    .list();
            Map<Long, BigDecimal> running = new HashMap<>();
            int index = 0;
            for (int p = 0; p < ends.size(); p++) {
                while (index < postings.size() && !postings.get(index).date().isAfter(ends.get(p))) {
                    Posting posting = postings.get(index++);
                    running.merge(posting.accountId(), posting.delta(), BigDecimal::add);
                    firstPoint.putIfAbsent(posting.accountId(), p);
                }
                for (Long accountId : accountMap.keySet()) {
                    balances.computeIfAbsent(accountId, k -> new BigDecimal[ends.size()])[p] = running.getOrDefault(accountId, BigDecimal.ZERO);
                }
            }
        }

        GoalView view(Goal goal) {
            Commodity unit = commodityMap.get(goal.getCommodityId());
            int scale = unit.isToman() ? 0 : unit.getScale();
            // paces keep two decimals in units counted whole: a fifth of a coin a month is neither 0 nor 1
            int rateScale = unit.isToman() ? 0 : Math.max(scale, 2);
            Optional<BigDecimal> unitPrice = price(unit.getId(), now);
            boolean linked = !goal.getAccountIds().isEmpty();

            BigDecimal currentAmount;
            BigDecimal currentToman;
            BigDecimal trend = null;
            boolean missing = false;
            if (linked) {
                List<BigDecimal> series = new ArrayList<>();
                int start = goal.getAccountIds().stream().map(id -> firstPoint.getOrDefault(id, points.size() - 1))
                        .min(Integer::compare).orElse(points.size() - 1);
                for (int p = 0; p < points.size(); p++) {
                    series.add(p < start ? null : valueAt(goal, unit, p).orElse(null));
                }
                Optional<BigDecimal> toman = tomanAt(goal, points.size() - 1);
                missing = toman.isEmpty() || unitPrice.isEmpty();
                currentToman = toman.map(GoalService::round0).orElse(null);
                currentAmount = series.getLast() == null ? null : series.getLast().setScale(scale, RoundingMode.HALF_EVEN);
                trend = GoalProjection.monthlyTrend(series);
            } else {
                currentAmount = goal.getManualAmount() == null ? BigDecimal.ZERO : goal.getManualAmount();
                currentToman = unitPrice.map(p -> round0(currentAmount.multiply(p))).orElse(null);
                missing = unitPrice.isEmpty() && !unit.isToman();
            }

            BigDecimal target = goal.getTargetAmount();
            BigDecimal progress = null;
            BigDecimal remaining = null;
            boolean achieved = false;
            Integer monthsToGoal = null;
            String etaMonth = null;
            if (currentAmount != null) {
                progress = currentAmount.divide(target, 4, RoundingMode.HALF_EVEN);
                remaining = target.subtract(currentAmount).max(BigDecimal.ZERO).setScale(scale, RoundingMode.HALF_EVEN);
                achieved = currentAmount.compareTo(target) >= 0;
                monthsToGoal = GoalProjection.monthsToGoal(remaining, trend);
                etaMonth = monthsToGoal == null || achieved ? null : current.plusMonths(monthsToGoal).toString();
            }

            Integer monthsLeft = null;
            BigDecimal required = null;
            BigDecimal requiredToman = null;
            Boolean onTrack = null;
            if (goal.getTargetDate() != null) {
                JalaliMonth targetMonth = JalaliMonth.from(goal.getTargetDate());
                monthsLeft = (int) Math.max(0, current.monthsUntil(targetMonth));
                boolean passed = goal.getTargetDate().isBefore(LocalDate.now(clock));
                if (achieved) {
                    onTrack = true;
                } else if (passed) {
                    onTrack = false;
                } else if (remaining != null) {
                    required = remaining.divide(BigDecimal.valueOf(GoalProjection.paceMonths(current, targetMonth)), rateScale,
                            RoundingMode.CEILING);
                    BigDecimal req = required;
                    requiredToman = unitPrice.map(p -> round0(req.multiply(p))).orElse(null);
                    if (trend != null) {
                        onTrack = etaMonth != null && JalaliMonth.parse(etaMonth).compareTo(targetMonth) <= 0;
                    }
                }
            }
            return new GoalView(goal.getId(), goal.getName(), goal.getIcon(), target, unit.getCode(), goal.getTargetDate(),
                    List.copyOf(goal.getAccountIds()), goal.getManualAmount(), goal.getNotes(), goal.isArchived(), currentAmount,
                    currentToman, progress, remaining, achieved, trend == null ? null : trend.setScale(rateScale, RoundingMode.HALF_EVEN),
                    etaMonth, achieved ? Integer.valueOf(0) : monthsToGoal, monthsLeft, required, requiredToman, onTrack, missing);
        }

        /** Value of the goal's accounts at point {@code p} in Toman; empty when a non-zero balance has no price. */
        private Optional<BigDecimal> tomanAt(Goal goal, int p) {
            BigDecimal total = BigDecimal.ZERO;
            for (Long accountId : goal.getAccountIds()) {
                Account account = accountMap.get(accountId);
                BigDecimal[] series = balances.get(accountId);
                BigDecimal balance = series == null ? BigDecimal.ZERO : series[p];
                if (account == null || balance.signum() == 0) {
                    continue;
                }
                Optional<BigDecimal> price = price(account.getCommodityId(), points.get(p));
                if (price.isEmpty()) {
                    return Optional.empty();
                }
                total = total.add(balance.multiply(price.get()));
            }
            return Optional.of(total);
        }

        private Optional<BigDecimal> valueAt(Goal goal, Commodity unit, int p) {
            Optional<BigDecimal> unitPrice = price(unit.getId(), points.get(p));
            return tomanAt(goal, p).flatMap(toman -> unitPrice.map(price -> toman.divide(price, 8, RoundingMode.HALF_EVEN)));
        }

        private Optional<BigDecimal> price(long commodityId, Instant at) {
            return timeline.priceAt(commodityId, at).map(PricePoint::priceToman);
        }
    }

    private static BigDecimal round0(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_EVEN);
    }
}
