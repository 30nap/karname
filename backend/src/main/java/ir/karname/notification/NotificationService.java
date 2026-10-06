package ir.karname.notification;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.BalanceService;
import ir.karname.budget.BudgetService;
import ir.karname.budget.BudgetService.BudgetItem;
import ir.karname.cheque.ChequeDirection;
import ir.karname.cheque.ChequeService;
import ir.karname.cheque.ChequeService.ChequeView;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.common.persian.PersianFormat;
import ir.karname.common.web.ApiException;
import ir.karname.goal.GoalService;
import ir.karname.goal.GoalService.GoalView;
import ir.karname.loan.LoanService;
import ir.karname.loan.LoanService.DueInstallment;
import ir.karname.recurring.RecurringMode;
import ir.karname.recurring.RecurringService;
import ir.karname.recurring.RecurringService.Occurrence;
import ir.karname.recurring.RecurringService.OccurrenceStatus;
import ir.karname.user.DisplayUnit;
import ir.karname.user.UserService;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-app notifications, derived from the user's data by deterministic rules: installments and
 * cheques coming due or overdue, budgets at 80% / over, goals reached, stale prices of held assets
 * and recurring reminders. Each has a dedupe key, so a condition notifies once; when it resolves
 * (installment paid, cheque cleared) its unread notification is marked read.
 */
@Service
public class NotificationService {

    static final int DUE_SOON_DAYS = 3;
    private static final int MAX_LIST = 100;
    /** Types whose notifications disappear once the condition no longer holds. */
    private static final Set<String> RESOLVABLE = Set.of("LOAN_DUE", "LOAN_OVERDUE", "CHEQUE_DUE", "CHEQUE_OVERDUE", "RECURRING_DUE");

    private final NotificationRepository notifications;
    private final JdbcClient jdbc;
    private final LoanService loans;
    private final ChequeService cheques;
    private final BudgetService budgets;
    private final GoalService goals;
    private final RecurringService recurring;
    private final CommodityService commodities;
    private final AccountService accounts;
    private final BalanceService balances;
    private final UserService users;
    private final Clock clock;
    private final Cache<Long, Instant> lastRefresh = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(2)).maximumSize(10_000).build();

    public NotificationService(NotificationRepository notifications, JdbcClient jdbc, LoanService loans, ChequeService cheques,
            BudgetService budgets, GoalService goals, RecurringService recurring, CommodityService commodities, AccountService accounts,
            BalanceService balances, UserService users, Clock clock) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.loans = loans;
        this.cheques = cheques;
        this.budgets = budgets;
        this.goals = goals;
        this.recurring = recurring;
        this.commodities = commodities;
        this.accounts = accounts;
        this.balances = balances;
        this.users = users;
        this.clock = clock;
    }

    public record NotificationView(long id, String type, Severity severity, String title, String body, String link, Instant createdAt,
            boolean read) {

        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getSeverity(), n.getTitle(), n.getBody(), n.getLink(), n.getCreatedAt(),
                    n.getReadAt() != null);
        }
    }

    record Candidate(String key, String type, Severity severity, String title, String body, String link) {
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(long userId, boolean unreadOnly) {
        List<Notification> list = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, Limit.of(MAX_LIST))
                : notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, Limit.of(MAX_LIST));
        return list.stream().map(NotificationView::of).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(long userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(long userId, long id) {
        Notification n = notifications.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("notification.notFound"));
        if (n.getReadAt() == null) {
            n.setReadAt(clock.instant());
        }
    }

    @Transactional
    public int markAllRead(long userId) {
        return jdbc.sql("UPDATE notifications SET read_at = :now WHERE user_id = :userId AND read_at IS NULL")
                .param("now", java.sql.Timestamp.from(clock.instant()))
                .param("userId", userId)
                .update();
    }

    /**
     * {@link #refresh} at most every two minutes per user, since it runs on page loads; a change to the
     * user's data ({@link #markStale}) lifts the wait so the next page load shows its consequences.
     */
    @Transactional
    public void refreshThrottled(long userId) {
        if (lastRefresh.getIfPresent(userId) == null) {
            lastRefresh.put(userId, clock.instant());
            refresh(userId);
        }
    }

    /** The user's data changed: re-evaluate the rules on the next {@link #refreshThrottled}. */
    public void markStale(long userId) {
        lastRefresh.invalidate(userId);
    }

    /** Evaluates every rule for the user; returns the number of new notifications. */
    @Transactional
    public int refresh(long userId) {
        LocalDate today = LocalDate.now(clock);
        boolean rial = users.settings(userId).getDisplayUnit() == DisplayUnit.RIAL;
        List<Candidate> candidates = new ArrayList<>();
        installments(userId, today, rial, candidates);
        cheques(userId, today, rial, candidates);
        budgets(userId, today, rial, candidates);
        goals(userId, candidates);
        prices(userId, today, candidates);
        reminders(userId, today, rial, candidates);

        int created = 0;
        Instant now = clock.instant();
        for (Candidate c : candidates) {
            created += jdbc.sql("""
                    INSERT INTO notifications (user_id, type, severity, title, body, link, dedupe_key, created_at)
                    VALUES (:userId, :type, :severity, :title, :body, :link, :key, :now)
                    ON CONFLICT (user_id, dedupe_key) DO NOTHING
                    """)
                    .param("userId", userId)
                    .param("type", c.type())
                    .param("severity", c.severity().name())
                    .param("title", c.title())
                    .param("body", c.body())
                    .param("link", c.link())
                    .param("key", c.key())
                    .param("now", java.sql.Timestamp.from(now))
                    .update();
        }
        resolve(userId, candidates, now);
        return created;
    }

    /** Housekeeping: drops read notifications older than 60 days. */
    @Transactional
    public int purgeOld() {
        return jdbc.sql("DELETE FROM notifications WHERE read_at IS NOT NULL AND created_at < :before")
                .param("before", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(60))))
                .update();
    }

    private void resolve(long userId, List<Candidate> candidates, Instant now) {
        Set<String> active = new HashSet<>();
        candidates.forEach(c -> active.add(c.key()));
        for (Notification n : notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, Limit.of(500))) {
            if (RESOLVABLE.contains(n.getType()) && !active.contains(n.getDedupeKey())) {
                n.setReadAt(now);
            }
        }
    }

    private void installments(long userId, LocalDate today, boolean rial, List<Candidate> out) {
        for (DueInstallment i : loans.due(userId, today.plusDays(DUE_SOON_DAYS))) {
            String number = PersianFormat.number(i.number());
            String amount = PersianFormat.money(i.amount(), rial);
            String date = JalaliDate.from(i.dueDate()).toDisplayString();
            String link = "/loans/" + i.loanId();
            if (i.dueDate().isBefore(today)) {
                out.add(new Candidate("loan-overdue:" + i.loanId() + ":" + i.number(), "LOAN_OVERDUE", Severity.CRITICAL,
                        "قسط معوق: " + i.loanName(), "قسط " + number + " به مبلغ " + amount + " از " + date + " پرداخت نشده است.", link));
            } else {
                out.add(new Candidate("loan-due:" + i.loanId() + ":" + i.number(), "LOAN_DUE", Severity.WARNING,
                        "سررسید قسط " + i.loanName(), "قسط " + number + " به مبلغ " + amount + "، سررسید " + date + ".", link));
            }
        }
    }

    private void cheques(long userId, LocalDate today, boolean rial, List<Candidate> out) {
        for (ChequeView c : cheques.pendingUntil(userId, today.plusDays(DUE_SOON_DAYS))) {
            boolean issued = c.direction() == ChequeDirection.ISSUED;
            String who = c.counterparty() != null ? (issued ? " به " : " از ") + c.counterparty() : "";
            String what = "چک " + PersianFormat.money(c.amount(), rial) + who;
            String date = JalaliDate.from(c.dueDate()).toDisplayString();
            if (c.overdue()) {
                out.add(new Candidate("cheque-overdue:" + c.id(), "CHEQUE_OVERDUE", issued ? Severity.CRITICAL : Severity.WARNING,
                        issued ? "چک صادره‌ی سررسیدگذشته" : "چک دریافتی وصول‌نشده",
                        what + " از " + date + " سررسید شده و وضعیتش مشخص نشده است.", "/cheques"));
            } else {
                out.add(new Candidate("cheque-due:" + c.id(), "CHEQUE_DUE", Severity.WARNING,
                        issued ? "سررسید چک صادره" : "سررسید چک دریافتی",
                        what + "، سررسید " + date + (issued ? ". موجودی حساب را بررسی کنید." : "؛ برای وصول اقدام کنید."), "/cheques"));
            }
        }
    }

    private void budgets(long userId, LocalDate today, boolean rial, List<Candidate> out) {
        JalaliMonth month = JalaliMonth.from(today);
        for (BudgetItem item : budgets.month(userId, month).items()) {
            String spent = PersianFormat.money(item.spent(), rial);
            String amount = PersianFormat.money(item.amount(), rial);
            switch (item.status()) {
                case OVER -> out.add(new Candidate("budget:" + month + ":" + item.categoryId() + ":OVER", "BUDGET_OVER", Severity.CRITICAL,
                        "بودجه‌ی " + item.name() + " تمام شد",
                        spent + " از بودجه‌ی " + amount + " خرج شده؛ " + PersianFormat.money(item.remaining().negate(), rial) + " بیشتر از بودجه.",
                        "/budgets"));
                case WARNING -> out.add(new Candidate("budget:" + month + ":" + item.categoryId() + ":WARNING", "BUDGET_WARNING", Severity.WARNING,
                        PersianFormat.number(item.ratio().multiply(BigDecimal.valueOf(100))) + "٪ بودجه‌ی " + item.name() + " مصرف شد",
                        spent + " از " + amount + " خرج شده و " + PersianFormat.money(item.remaining(), rial) + " مانده است.", "/budgets"));
                case OK -> {
                }
            }
        }
    }

    private void goals(long userId, List<Candidate> out) {
        for (GoalView g : goals.list(userId, false)) {
            if (g.achieved()) {
                out.add(new Candidate("goal-reached:" + g.id(), "GOAL_REACHED", Severity.INFO, "به هدف «" + g.name() + "» رسیدید",
                        "پس‌انداز شما به مبلغ هدف رسیده است. تبریک!", "/goals"));
            }
        }
    }

    /** Stale prices of commodities the user actually holds; at most once a week per commodity. */
    private void prices(long userId, LocalDate today, List<Candidate> out) {
        Map<Long, BigDecimal> held = new HashMap<>();
        Map<Long, BigDecimal> current = balances.balances(userId, today);
        for (Account a : accounts.list(userId)) {
            if (!a.isArchived() && a.isIncludeInNetWorth()) {
                held.merge(a.getCommodityId(), current.getOrDefault(a.getId(), BigDecimal.ZERO), BigDecimal::add);
            }
        }
        LocalDate week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        for (CommodityView c : commodities.list(userId)) {
            if (c.latestPrice() == null || !c.latestPrice().stale()) {
                continue;
            }
            long id = commodities.require(userId, c.code()).getId();
            if (held.getOrDefault(id, BigDecimal.ZERO).signum() != 0) {
                String date = JalaliDate.from(c.latestPrice().pricedAt().atZone(clock.getZone()).toLocalDate()).toDisplayString();
                out.add(new Candidate("price-stale:" + c.code() + ":" + week, "PRICE_STALE", Severity.WARNING, "قیمت " + c.nameFa() + " قدیمی است",
                        "آخرین قیمت " + c.nameFa() + " مربوط به " + date + " است؛ برای ارزش‌گذاری دقیق دارایی‌ها، قیمت را به‌روز کنید.", "/assets"));
            }
        }
    }

    private void reminders(long userId, LocalDate today, boolean rial, List<Candidate> out) {
        long toman = commodities.toman().getId();
        Set<Long> tomanAccounts = new HashSet<>();
        accounts.list(userId).stream().filter(a -> a.getCommodityId() == toman).forEach(a -> tomanAccounts.add(a.getId()));
        for (Occurrence o : recurring.pending(userId)) {
            if (o.mode() != RecurringMode.REMIND || o.status() != OccurrenceStatus.DUE) {
                continue;
            }
            String amount = tomanAccounts.contains(o.accountId()) ? " به مبلغ " + PersianFormat.money(o.amount(), rial) : "";
            out.add(new Candidate("rec-due:" + o.ruleId() + ":" + o.date(), "RECURRING_DUE", Severity.INFO, "یادآوری: " + o.name(),
                    "نوبت " + JalaliDate.from(o.date()).toDisplayString() + amount + " هنوز ثبت نشده است.", "/recurring"));
        }
    }
}
