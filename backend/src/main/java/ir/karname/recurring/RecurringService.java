package ir.karname.recurring;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.common.config.KarnameProperties;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.persian.PersianText;
import ir.karname.common.web.ApiException;
import ir.karname.transaction.Transaction;
import ir.karname.transaction.TransactionRepository;
import ir.karname.transaction.TransactionService;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionSource;
import ir.karname.transaction.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recurring transactions on Jalali calendar rules. A posted occurrence is the transaction with
 * external_ref {@code rec:{rule}:{date}}; skipped ones are stored in recurring_skips. AUTO rules are
 * posted by the scheduler from the later of their start and creation dates, catching up after
 * downtime; the unique external_ref keeps that idempotent.
 */
@Service
public class RecurringService {

    private static final Logger log = LoggerFactory.getLogger(RecurringService.class);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e18");
    /** How far back unhandled reminders are shown. */
    public static final int LOOKBACK_DAYS = 60;
    static final int UPCOMING_DAYS = 7;

    private final RecurringRuleRepository rules;
    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionService transactionService;
    private final TransactionRepository transactions;
    private final JdbcClient jdbc;
    private final TransactionTemplate perPosting;
    private final KarnameProperties properties;
    private final Clock clock;

    public RecurringService(RecurringRuleRepository rules, AccountService accounts, CategoryService categories,
            TransactionService transactionService, TransactionRepository transactions, JdbcClient jdbc, PlatformTransactionManager tm,
            KarnameProperties properties, Clock clock) {
        this.rules = rules;
        this.accounts = accounts;
        this.categories = categories;
        this.transactionService = transactionService;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.perPosting = new TransactionTemplate(tm);
        this.properties = properties;
        this.clock = clock;
    }

    public record RuleRequest(String name, TransactionType type, Long accountId, Long toAccountId, BigDecimal amount, BigDecimal toAmount,
            Long categoryId, String description, Frequency frequency, Integer interval, Integer dayOfMonth, Integer dayOfWeek,
            Integer monthOfYear, LocalDate startDate, LocalDate endDate, RecurringMode mode, Boolean active) {
    }

    public enum OccurrenceStatus {
        POSTED, SKIPPED, DUE, UPCOMING
    }

    public record Occurrence(long ruleId, String name, TransactionType type, RecurringMode mode, LocalDate date, BigDecimal amount,
            BigDecimal toAmount, long accountId, Long toAccountId, Long categoryId, OccurrenceStatus status, Long transactionId) {
    }

    /** {@code dueCount}: occurrences on or before today that are neither posted nor skipped. */
    public record RuleView(long id, String name, TransactionType type, long accountId, Long toAccountId, BigDecimal amount,
            BigDecimal toAmount, Long categoryId, String description, Frequency frequency, int interval, Integer dayOfMonth,
            Integer dayOfWeek, Integer monthOfYear, LocalDate startDate, LocalDate endDate, RecurringMode mode, boolean active,
            LocalDate nextDate, LocalDate lastPosted, int dueCount) {
    }

    /** Overrides for one posting: the actual date and amount (a bill that differs this month). */
    public record PostRequest(LocalDate date, BigDecimal amount) {
    }

    @Transactional(readOnly = true)
    public List<RuleView> list(long userId) {
        LocalDate today = LocalDate.now(clock);
        return rules.findByUserIdOrderByIdAsc(userId).stream().map(r -> view(userId, r, today))
                // what comes next first: active rules by their next date, then paused ones, then those that ended
                .sorted(Comparator.comparing((RuleView r) -> !r.active())
                        .thenComparing(RuleView::nextDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public RuleView get(long userId, long id) {
        return view(userId, require(userId, id), LocalDate.now(clock));
    }

    @Transactional
    public RuleView create(long userId, RuleRequest request) {
        RecurringRule rule = new RecurringRule(userId, clock.instant());
        apply(userId, rule, request);
        rules.saveAndFlush(rule);
        return view(userId, rule, LocalDate.now(clock));
    }

    @Transactional
    public RuleView update(long userId, long id, RuleRequest request) {
        RecurringRule rule = require(userId, id);
        apply(userId, rule, request);
        rules.flush();
        return view(userId, rule, LocalDate.now(clock));
    }

    /** Deletes the rule; transactions it already posted stay. */
    @Transactional
    public void delete(long userId, long id) {
        rules.delete(require(userId, id));
    }

    /** Every occurrence of the user's rules in [from, to] with its status. */
    @Transactional(readOnly = true)
    public List<Occurrence> occurrences(long userId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        List<Occurrence> result = new ArrayList<>();
        for (RecurringRule rule : rules.findByUserIdOrderByIdAsc(userId)) {
            if (!rule.isActive()) {
                continue;
            }
            Map<LocalDate, Long> posted = posted(userId, rule);
            Set<LocalDate> skipped = skipped(rule);
            LocalDate start = postingStart(rule);
            for (LocalDate date : rule.recurrence().between(start.isAfter(from) ? start : from, to)) {
                result.add(occurrence(rule, date, posted, skipped, today));
            }
        }
        result.sort(Comparator.comparing(Occurrence::date));
        return result;
    }

    /** Reminders waiting for the user: due (within {@link #LOOKBACK_DAYS}) and coming in the next week. */
    @Transactional(readOnly = true)
    public List<Occurrence> pending(long userId) {
        LocalDate today = LocalDate.now(clock);
        return occurrences(userId, today.minusDays(LOOKBACK_DAYS), today.plusDays(UPCOMING_DAYS)).stream()
                .filter(o -> o.status() == OccurrenceStatus.DUE || o.status() == OccurrenceStatus.UPCOMING)
                .toList();
    }

    @Transactional
    public Transaction post(long userId, long id, LocalDate date, PostRequest request) {
        RecurringRule rule = require(userId, id);
        requireOccurrence(rule, date);
        if (transactions.existsByUserIdAndExternalRef(userId, ref(rule, date))) {
            throw ApiException.conflict("recurring.alreadyPosted");
        }
        jdbc.sql("DELETE FROM recurring_skips WHERE rule_id = ? AND due_date = ?").params(rule.getId(), date).update();
        LocalDate actual = request != null && request.date() != null ? request.date() : date;
        BigDecimal amount = request != null && request.amount() != null ? request.amount() : null;
        return postInternal(userId, rule, date, actual, amount);
    }

    @Transactional
    public void skip(long userId, long id, LocalDate date) {
        RecurringRule rule = require(userId, id);
        requireOccurrence(rule, date);
        if (transactions.existsByUserIdAndExternalRef(userId, ref(rule, date))) {
            throw ApiException.conflict("recurring.alreadyPosted");
        }
        jdbc.sql("INSERT INTO recurring_skips (rule_id, due_date) VALUES (?, ?) ON CONFLICT DO NOTHING").params(rule.getId(), date).update();
    }

    @Transactional
    public void unskip(long userId, long id, LocalDate date) {
        RecurringRule rule = require(userId, id);
        jdbc.sql("DELETE FROM recurring_skips WHERE rule_id = ? AND due_date = ?").params(rule.getId(), date).update();
    }

    /**
     * Posts the user's due AUTO occurrences, each in its own transaction so one failing rule (an
     * archived account, say) does not block the others. Returns how many were posted.
     */
    public int runAuto(long userId) {
        LocalDate today = LocalDate.now(clock);
        int posted = 0;
        for (RecurringRule rule : rules.findByUserIdOrderByIdAsc(userId)) {
            if (!rule.isActive() || rule.getMode() != RecurringMode.AUTO) {
                continue;
            }
            LocalDate from = postingStart(rule);
            Map<LocalDate, Long> done = posted(userId, rule);
            Set<LocalDate> skipped = skipped(rule);
            for (LocalDate date : rule.recurrence().between(from, today)) {
                if (done.containsKey(date) || skipped.contains(date)) {
                    continue;
                }
                try {
                    perPosting.executeWithoutResult(status -> postInternal(userId, rule, date, date, null));
                    posted++;
                } catch (DataIntegrityViolationException e) {
                    // posted concurrently by another run
                } catch (ApiException e) {
                    log.warn("Recurring rule {} could not post {}: {}", rule.getId(), date, e.code());
                }
            }
        }
        return posted;
    }

    List<Long> usersWithAutoRules() {
        return rules.findUsersWithAutoRules();
    }

    private Transaction postInternal(long userId, RecurringRule rule, LocalDate occurrence, LocalDate actual, BigDecimal amount) {
        BigDecimal value = amount != null ? amount : rule.getAmount();
        TransactionRequest request = new TransactionRequest(rule.getType(), actual, rule.getAccountId(), value, rule.getToAccountId(),
                rule.getToAmount(), null, rule.getCategoryId(), rule.getDescription() != null ? rule.getDescription() : rule.getName(), null,
                List.of());
        return transactionService.create(userId, request, TransactionSource.RECURRING, ref(rule, occurrence));
    }

    private RuleView view(long userId, RecurringRule rule, LocalDate today) {
        Map<LocalDate, Long> posted = posted(userId, rule);
        Set<LocalDate> skipped = skipped(rule);
        LocalDate next = null;
        for (LocalDate d : rule.recurrence().between(today, today.plusDays(800))) {
            if (!posted.containsKey(d) && !skipped.contains(d)) {
                next = d;
                break;
            }
        }
        int due = 0;
        if (rule.isActive()) {
            LocalDate lookback = today.minusDays(LOOKBACK_DAYS);
            LocalDate start = postingStart(rule);
            for (LocalDate d : rule.recurrence().between(start.isAfter(lookback) ? start : lookback, today)) {
                if (!posted.containsKey(d) && !skipped.contains(d)) {
                    due++;
                }
            }
        }
        LocalDate last = posted.keySet().stream().max(Comparator.naturalOrder()).orElse(null);
        return new RuleView(rule.getId(), rule.getName(), rule.getType(), rule.getAccountId(), rule.getToAccountId(), rule.getAmount(),
                rule.getToAmount(), rule.getCategoryId(), rule.getDescription(), rule.getFrequency(), rule.getIntervalN(), rule.getDayOfMonth(),
                rule.getDayOfWeek(), rule.getMonthOfYear(), rule.getStartDate(), rule.getEndDate(), rule.getMode(), rule.isActive(), next, last, due);
    }

    /**
     * First date the rule is expected to produce transactions: its start, or for AUTO rules the
     * later of start and creation (AUTO never back-fills the time before it existed).
     */
    private LocalDate postingStart(RecurringRule rule) {
        if (rule.getMode() != RecurringMode.AUTO) {
            return rule.getStartDate();
        }
        LocalDate created = rule.getCreatedAt().atZone(properties.timezone()).toLocalDate();
        return created.isAfter(rule.getStartDate()) ? created : rule.getStartDate();
    }

    private static Occurrence occurrence(RecurringRule rule, LocalDate date, Map<LocalDate, Long> posted, Set<LocalDate> skipped, LocalDate today) {
        OccurrenceStatus status = posted.containsKey(date) ? OccurrenceStatus.POSTED
                : skipped.contains(date) ? OccurrenceStatus.SKIPPED
                : date.isAfter(today) ? OccurrenceStatus.UPCOMING : OccurrenceStatus.DUE;
        return new Occurrence(rule.getId(), rule.getName(), rule.getType(), rule.getMode(), date, rule.getAmount(), rule.getToAmount(),
                rule.getAccountId(), rule.getToAccountId(), rule.getCategoryId(), status, posted.get(date));
    }

    /** Posted occurrence dates of a rule, with their transaction ids. */
    private Map<LocalDate, Long> posted(long userId, RecurringRule rule) {
        String prefix = "rec:" + rule.getId() + ":";
        Map<LocalDate, Long> result = new HashMap<>();
        for (Transaction tx : transactions.findByUserIdAndExternalRefStartingWith(userId, prefix)) {
            try {
                result.put(LocalDate.parse(tx.getExternalRef().substring(prefix.length())), tx.getId());
            } catch (DateTimeParseException e) {
                // not one of ours
            }
        }
        return result;
    }

    private Set<LocalDate> skipped(RecurringRule rule) {
        return new HashSet<>(jdbc.sql("SELECT due_date FROM recurring_skips WHERE rule_id = ?").param(rule.getId())
                .query((rs, n) -> rs.getDate(1).toLocalDate()).list());
    }

    private static void requireOccurrence(RecurringRule rule, LocalDate date) {
        if (date == null || !rule.recurrence().between(date, date).contains(date)) {
            throw ApiException.badRequest("recurring.notAnOccurrence");
        }
    }

    static String ref(RecurringRule rule, LocalDate date) {
        return "rec:" + rule.getId() + ":" + date;
    }

    private RecurringRule require(long userId, long id) {
        return rules.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("recurring.notFound"));
    }

    private void apply(long userId, RecurringRule rule, RuleRequest r) {
        String name = PersianText.clean(r.name());
        if (name == null || name.length() > 100) {
            throw ApiException.badRequest("recurring.invalidName");
        }
        TransactionType type = r.type();
        if (type != TransactionType.INCOME && type != TransactionType.EXPENSE && type != TransactionType.TRANSFER) {
            throw ApiException.badRequest("transaction.invalidType");
        }
        if (r.amount() == null || r.amount().signum() <= 0 || r.amount().compareTo(MAX_AMOUNT) >= 0 || r.amount().stripTrailingZeros().scale() > 8) {
            throw ApiException.badRequest("transaction.invalidAmount");
        }
        if (r.accountId() == null) {
            throw ApiException.badRequest("transaction.accountRequired");
        }
        Account account = accounts.require(userId, r.accountId());
        Long toAccountId = null;
        BigDecimal toAmount = null;
        Long categoryId = null;
        if (type == TransactionType.TRANSFER) {
            if (r.toAccountId() == null) {
                throw ApiException.badRequest("transaction.toAccountRequired");
            }
            Account to = accounts.require(userId, r.toAccountId());
            if (to.getId().equals(account.getId())) {
                throw ApiException.badRequest("transaction.sameAccount");
            }
            if (!to.getCommodityId().equals(account.getCommodityId())) {
                if (r.toAmount() == null || r.toAmount().signum() <= 0) {
                    throw ApiException.badRequest("transaction.toAmountRequired");
                }
                toAmount = r.toAmount();
            }
            toAccountId = to.getId();
        } else if (r.categoryId() != null) {
            categoryId = categories.requireForKind(userId, r.categoryId(), type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE)
                    .getId();
        }
        Frequency frequency = r.frequency();
        if (frequency == null) {
            throw ApiException.badRequest("recurring.invalidFrequency");
        }
        int interval = r.interval() == null ? 1 : r.interval();
        if (interval < 1 || interval > 12) {
            throw ApiException.badRequest("recurring.invalidInterval");
        }
        if (r.startDate() == null || (r.endDate() != null && r.endDate().isBefore(r.startDate()))) {
            throw ApiException.badRequest("recurring.invalidDates");
        }
        Integer dayOfMonth = null;
        Integer dayOfWeek = null;
        Integer monthOfYear = null;
        switch (frequency) {
            case WEEKLY -> dayOfWeek = r.dayOfWeek() != null ? r.dayOfWeek() : Recurrence.weekday(r.startDate());
            case MONTHLY -> dayOfMonth = r.dayOfMonth() != null ? r.dayOfMonth() : JalaliDate.from(r.startDate()).day();
            case YEARLY -> {
                JalaliDate start = JalaliDate.from(r.startDate());
                dayOfMonth = r.dayOfMonth() != null ? r.dayOfMonth() : start.day();
                monthOfYear = r.monthOfYear() != null ? r.monthOfYear() : start.month();
            }
        }
        if ((dayOfMonth != null && (dayOfMonth < 1 || dayOfMonth > 31)) || (dayOfWeek != null && (dayOfWeek < 0 || dayOfWeek > 6))
                || (monthOfYear != null && (monthOfYear < 1 || monthOfYear > 12))) {
            throw ApiException.badRequest("recurring.invalidDay");
        }
        String description = PersianText.clean(r.description());
        if (description != null && description.length() > 300) {
            throw ApiException.badRequest("transaction.descriptionTooLong");
        }
        rule.setName(name);
        rule.setType(type);
        rule.setAccountId(account.getId());
        rule.setToAccountId(toAccountId);
        rule.setAmount(r.amount());
        rule.setToAmount(toAmount);
        rule.setCategoryId(categoryId);
        rule.setDescription(description);
        rule.setFrequency(frequency);
        rule.setIntervalN(interval);
        rule.setDayOfMonth(dayOfMonth);
        rule.setDayOfWeek(dayOfWeek);
        rule.setMonthOfYear(monthOfYear);
        rule.setStartDate(r.startDate());
        rule.setEndDate(r.endDate());
        rule.setMode(r.mode() == null ? RecurringMode.REMIND : r.mode());
        if (r.active() != null) {
            rule.setActive(r.active());
        }
    }
}
