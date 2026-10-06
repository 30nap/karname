package ir.karname.forecast;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountType;
import ir.karname.account.BalanceService;
import ir.karname.cheque.ChequeDirection;
import ir.karname.cheque.ChequeService;
import ir.karname.cheque.ChequeService.ChequeView;
import ir.karname.commodity.CommodityService;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import ir.karname.loan.LoanService;
import ir.karname.loan.LoanService.DueInstallment;
import ir.karname.recurring.RecurringService;
import ir.karname.recurring.RecurringService.Occurrence;
import ir.karname.recurring.RecurringService.OccurrenceStatus;
import ir.karname.transaction.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Projected balance of the user's liquid Toman accounts (cash, bank, e-wallet) over the coming
 * days: future-dated transactions already recorded, recurring occurrences, unpaid loan
 * installments and pending cheques. Overdue items are expected today. Deterministic: no estimates
 * of ordinary spending are added.
 */
@Service
public class ForecastService {

    static final Set<AccountType> LIQUID = EnumSet.of(AccountType.CASH, AccountType.BANK, AccountType.EWALLET);
    private static final int MAX_DAYS = 365;

    private final AccountService accounts;
    private final BalanceService balances;
    private final CommodityService commodities;
    private final RecurringService recurring;
    private final LoanService loans;
    private final ChequeService cheques;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ForecastService(AccountService accounts, BalanceService balances, CommodityService commodities, RecurringService recurring,
            LoanService loans, ChequeService cheques, JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.commodities = commodities;
        this.recurring = recurring;
        this.loans = loans;
        this.cheques = cheques;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public enum Source {
        RECORDED, RECURRING, LOAN, CHEQUE
    }

    /** One expected movement; {@code amount} is its signed effect on the liquid balance. */
    public record Event(LocalDate date, Source source, String title, BigDecimal amount, boolean overdue, String link) {
    }

    public record Point(LocalDate date, BigDecimal balance) {
    }

    public record Forecast(LocalDate from, LocalDate to, BigDecimal startBalance, BigDecimal endBalance, BigDecimal minBalance,
            LocalDate minDate, BigDecimal inflow, BigDecimal outflow, List<Event> events, List<Point> points) {
    }

    @Transactional(readOnly = true)
    public Forecast forecast(long userId, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw ApiException.badRequest("forecast.invalidDays");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate to = today.plusDays(days);
        long toman = commodities.toman().getId();
        Map<Long, Account> liquid = accounts.list(userId).stream()
                .filter(a -> LIQUID.contains(a.getType()) && !a.isArchived() && a.isIncludeInNetWorth() && a.getCommodityId() == toman)
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        BigDecimal start = BigDecimal.ZERO;
        Map<Long, BigDecimal> current = balances.balances(userId, today);
        for (Long id : liquid.keySet()) {
            start = start.add(current.getOrDefault(id, BigDecimal.ZERO));
        }

        List<Event> events = new ArrayList<>();
        recorded(userId, liquid.keySet(), today, to, events);
        for (Occurrence o : recurring.occurrences(userId, today.minusDays(RecurringService.LOOKBACK_DAYS), to)) {
            boolean due = o.status() == OccurrenceStatus.DUE;
            if (!due && !(o.status() == OccurrenceStatus.UPCOMING && o.date().isAfter(today))) {
                continue;
            }
            BigDecimal effect = effect(o, liquid.keySet());
            if (effect.signum() != 0) {
                events.add(new Event(due ? today : o.date(), Source.RECURRING, o.name(), effect, due, "/recurring"));
            }
        }
        for (DueInstallment i : loans.due(userId, to)) {
            boolean overdue = i.dueDate().isBefore(today);
            String title = "قسط " + PersianText.toPersianDigits(String.valueOf(i.number())) + " " + i.loanName();
            events.add(new Event(overdue ? today : i.dueDate(), Source.LOAN, title, i.amount().negate(), overdue, "/loans/" + i.loanId()));
        }
        for (ChequeView c : cheques.pendingUntil(userId, to)) {
            boolean issued = c.direction() == ChequeDirection.ISSUED;
            String who = c.counterparty() != null ? " " + (issued ? "به " : "از ") + c.counterparty() : "";
            String title = (issued ? "چک صادره" : "چک دریافتی") + who;
            events.add(new Event(c.overdue() ? today : c.dueDate(), Source.CHEQUE, title, issued ? c.amount().negate() : c.amount(), c.overdue(),
                    "/cheques"));
        }
        events.sort(Comparator.comparing(Event::date).thenComparing(e -> e.amount().signum() < 0 ? 1 : 0));

        TreeMap<LocalDate, BigDecimal> byDay = new TreeMap<>();
        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal outflow = BigDecimal.ZERO;
        for (Event e : events) {
            byDay.merge(e.date(), e.amount(), BigDecimal::add);
            if (e.amount().signum() > 0) {
                inflow = inflow.add(e.amount());
            } else {
                outflow = outflow.add(e.amount().negate());
            }
        }
        List<Point> points = new ArrayList<>();
        BigDecimal balance = start;
        BigDecimal min = start;
        LocalDate minDate = today;
        points.add(new Point(today, start));
        for (Map.Entry<LocalDate, BigDecimal> day : byDay.entrySet()) {
            balance = balance.add(day.getValue());
            if (day.getKey().equals(today)) {
                points.set(0, new Point(today, balance));
            } else {
                points.add(new Point(day.getKey(), balance));
            }
            if (balance.compareTo(min) < 0) {
                min = balance;
                minDate = day.getKey();
            }
        }
        if (!points.getLast().date().equals(to)) {
            points.add(new Point(to, balance));
        }
        return new Forecast(today, to, start, balance, min, minDate, inflow, outflow, events, points);
    }

    /** Transactions already recorded with future dates on liquid accounts. */
    private void recorded(long userId, Set<Long> liquid, LocalDate today, LocalDate to, List<Event> events) {
        if (liquid.isEmpty()) {
            return;
        }
        record Row(LocalDate date, BigDecimal delta, String description, String type) {
        }
        jdbc.sql("""
                SELECT p.occurred_on, sum(p.delta), max(coalesce(t.description, '')), max(t.type)
                FROM ledger_postings p JOIN transactions t ON t.id = p.transaction_id
                WHERE p.user_id = :userId AND p.account_id IN (:ids) AND p.occurred_on > :today AND p.occurred_on <= :to
                GROUP BY p.transaction_id, p.occurred_on
                """)
                .param("userId", userId)
                .param("ids", liquid)
                .param("today", today)
                .param("to", to)
                .query((rs, n) -> new Row(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)))
                .list()
                .stream()
                .filter(r -> r.delta().signum() != 0)
                .forEach(r -> events.add(new Event(r.date(), Source.RECORDED, r.description().isEmpty() ? "تراکنش ثبت‌شده" : r.description(),
                        r.delta(), false, "/transactions")));
    }

    private static BigDecimal effect(Occurrence o, Set<Long> liquid) {
        boolean fromLiquid = liquid.contains(o.accountId());
        if (o.type() == TransactionType.INCOME) {
            return fromLiquid ? o.amount() : BigDecimal.ZERO;
        }
        if (o.type() == TransactionType.EXPENSE) {
            return fromLiquid ? o.amount().negate() : BigDecimal.ZERO;
        }
        boolean toLiquid = o.toAccountId() != null && liquid.contains(o.toAccountId());
        if (fromLiquid == toLiquid) {
            return BigDecimal.ZERO;
        }
        // Liquid accounts are in Toman, so the liquid side of the transfer is already in Toman.
        return fromLiquid ? o.amount().negate() : (o.toAmount() != null ? o.toAmount() : o.amount());
    }
}
