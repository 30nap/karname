package ir.karname.report;

import ir.karname.category.Category;
import ir.karname.category.CategoryService;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.common.web.ApiException;
import ir.karname.transaction.FlowValuation;
import ir.karname.transaction.FlowValuation.Flow;
import ir.karname.transaction.FlowValuation.FlowKind;
import ir.karname.transaction.Transaction;
import ir.karname.transaction.TransactionRepository;
import ir.karname.transaction.TransactionViews;
import ir.karname.transaction.TransactionViews.TransactionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministic analytics over income and expense flows valued in Toman. These numbers are what
 * the AI assistant explains; it never computes them itself.
 */
@Service
public class ReportService {

    static final int ANOMALY_HISTORY_MONTHS = 6;
    static final int MIN_HISTORY_MONTHS = 3;
    private static final BigDecimal Z_THRESHOLD = new BigDecimal("2");
    private static final BigDecimal MIN_RATIO = new BigDecimal("1.3");
    private static final BigDecimal FLAT_HISTORY_RATIO = new BigDecimal("1.5");
    /** A deviation must also matter relative to the whole budget: 5% of an average month's spending. */
    private static final BigDecimal MATERIALITY = new BigDecimal("0.05");
    /** A category with no history is flagged when it reaches 10% of an average month's spending. */
    private static final BigDecimal NEW_SPENDING = new BigDecimal("0.10");
    private static final int MAX_ANOMALIES = 5;

    private final FlowValuation flows;
    private final CategoryService categories;
    private final TransactionRepository transactions;
    private final TransactionViews views;
    private final Clock clock;

    public ReportService(FlowValuation flows, CategoryService categories, TransactionRepository transactions, TransactionViews views,
            Clock clock) {
        this.flows = flows;
        this.categories = categories;
        this.transactions = transactions;
        this.views = views;
        this.clock = clock;
    }

    public enum Kind {
        INCOME, EXPENSE;

        FlowKind flowKind() {
            return this == INCOME ? FlowKind.INCOME : FlowKind.EXPENSE;
        }
    }

    /** One Jalali month; {@code partial} marks the current month. */
    public record MonthTotals(String month, BigDecimal incomeToman, BigDecimal expenseToman, BigDecimal netToman,
            BigDecimal savingsRate, int unpricedCount, boolean partial) {
    }

    /**
     * A category's total in the period, compared with the previous period of the same length;
     * {@code averageToman} (single months only) is the mean of the three months before.
     */
    public record CategoryLine(Long categoryId, String name, String icon, BigDecimal valueToman, BigDecimal share, int count,
            BigDecimal previousToman, BigDecimal averageToman, List<CategoryLine> children) {
    }

    public record CategoryReport(String fromMonth, String toMonth, Kind kind, BigDecimal totalToman, BigDecimal previousTotalToman,
            int unpricedCount, List<CategoryLine> items) {
    }

    public record TopItem(TransactionView transaction, BigDecimal valueToman) {
    }

    /**
     * Spending in a category well above its own history. {@code ratio} and {@code zScore} are
     * null for spending in a category with no history.
     */
    public record Anomaly(long categoryId, String name, String icon, BigDecimal currentToman, BigDecimal averageToman,
            BigDecimal ratio, BigDecimal zScore, int historyMonths) {
    }

    @Transactional(readOnly = true)
    public List<MonthTotals> monthly(long userId, int months) {
        if (months < 1 || months > 36) {
            throw ApiException.badRequest("report.invalidSpan");
        }
        JalaliMonth current = JalaliMonth.from(LocalDate.now(clock));
        JalaliMonth first = current.plusMonths(-(months - 1));
        Map<JalaliMonth, List<Flow>> byMonth = flows.flows(userId, first.startDate(), current.endDate()).stream()
                .collect(Collectors.groupingBy(f -> JalaliMonth.from(f.date())));
        List<MonthTotals> result = new ArrayList<>();
        for (JalaliMonth m = first; m.compareTo(current) <= 0; m = m.next()) {
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;
            int unpriced = 0;
            for (Flow f : byMonth.getOrDefault(m, List.of())) {
                if (!f.priced()) {
                    unpriced++;
                } else if (f.kind() == FlowKind.INCOME) {
                    income = income.add(f.valueToman());
                } else {
                    expense = expense.add(f.valueToman());
                }
            }
            BigDecimal net = income.subtract(expense);
            BigDecimal rate = income.signum() > 0 ? net.divide(income, 4, RoundingMode.HALF_EVEN) : null;
            result.add(new MonthTotals(m.toString(), round(income), round(expense), round(net), rate, unpriced, m.equals(current)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public CategoryReport categories(long userId, JalaliMonth month, int span, Kind kind) {
        checkSpan(span);
        JalaliMonth from = month.plusMonths(-(span - 1));
        JalaliMonth previousFrom = month.plusMonths(-(2L * span - 1));
        JalaliMonth previousTo = month.plusMonths(-span);
        JalaliMonth averageFrom = month.plusMonths(-3);
        JalaliMonth earliest = span == 1 ? (averageFrom.compareTo(previousFrom) < 0 ? averageFrom : previousFrom) : previousFrom;
        List<Flow> all = flows.flows(userId, earliest.startDate(), month.endDate()).stream()
                .filter(f -> f.kind() == kind.flowKind())
                .toList();
        CategoryTree tree = new CategoryTree(categories.list(userId));

        Map<Long, Totals> current = new HashMap<>();
        Map<Long, Totals> previous = new HashMap<>();
        Map<Long, Totals> average = new HashMap<>();
        int unpriced = 0;
        for (Flow f : all) {
            JalaliMonth m = JalaliMonth.from(f.date());
            boolean inCurrent = m.compareTo(from) >= 0;
            if (!f.priced()) {
                if (inCurrent) {
                    unpriced++;
                }
                continue;
            }
            Long key = f.categoryId();
            if (inCurrent) {
                current.computeIfAbsent(key, k -> new Totals()).add(f.valueToman());
            } else {
                if (m.compareTo(previousFrom) >= 0 && m.compareTo(previousTo) <= 0) {
                    previous.computeIfAbsent(key, k -> new Totals()).add(f.valueToman());
                }
                if (span == 1 && m.compareTo(averageFrom) >= 0) {
                    average.computeIfAbsent(key, k -> new Totals()).add(f.valueToman());
                }
            }
        }

        BigDecimal total = current.values().stream().map(t -> t.value).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal previousTotal = previous.values().stream().map(t -> t.value).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Leaf totals roll up into their top-level category.
        Map<Long, List<Long>> leavesByRoot = new LinkedHashMap<>();
        Set<Long> keys = new HashSet<>(current.keySet());
        keys.addAll(previous.keySet());
        for (Long key : keys) {
            Long root = key == null ? null : tree.rootId(key);
            leavesByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(key);
        }
        List<CategoryLine> items = new ArrayList<>();
        leavesByRoot.forEach((rootId, leaves) -> {
            List<CategoryLine> children = new ArrayList<>();
            Totals rootCurrent = new Totals();
            Totals rootPrevious = new Totals();
            Totals rootAverage = new Totals();
            for (Long leaf : leaves) {
                Totals c = current.getOrDefault(leaf, Totals.EMPTY);
                Totals p = previous.getOrDefault(leaf, Totals.EMPTY);
                Totals a = average.getOrDefault(leaf, Totals.EMPTY);
                rootCurrent.merge(c);
                rootPrevious.merge(p);
                rootAverage.merge(a);
                if (leaf != null && !leaf.equals(rootId)) {
                    Category category = tree.get(leaf);
                    children.add(line(leaf, category == null ? null : category.getName(), tree.icon(leaf), c, p, a, total, span, List.of()));
                }
            }
            children.sort(Comparator.comparing(CategoryLine::valueToman).reversed());
            Category root = rootId == null ? null : tree.get(rootId);
            items.add(line(rootId, root == null ? null : root.getName(), rootId == null ? null : tree.icon(rootId), rootCurrent,
                    rootPrevious, rootAverage, total, span, children));
        });
        items.sort(Comparator.comparing(CategoryLine::valueToman).reversed());
        return new CategoryReport(from.toString(), month.toString(), kind, round(total), round(previousTotal), unpriced, items);
    }

    @Transactional(readOnly = true)
    public List<TopItem> top(long userId, JalaliMonth month, int span, Kind kind, int limit) {
        checkSpan(span);
        int size = Math.min(Math.max(limit, 1), 50);
        JalaliMonth from = month.plusMonths(-(span - 1));
        List<Flow> top = flows.flows(userId, from.startDate(), month.endDate()).stream()
                .filter(f -> f.kind() == kind.flowKind() && f.priced() && !f.fee())
                .sorted(Comparator.comparing(Flow::valueToman).reversed())
                .limit(size)
                .toList();
        Map<Long, Transaction> byId = transactions.findAllById(top.stream().map(Flow::transactionId).toList()).stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity()));
        Map<Long, TransactionView> viewById = views.views(userId, byId.values()).stream()
                .collect(Collectors.toMap(TransactionView::id, Function.identity()));
        return top.stream().map(f -> new TopItem(viewById.get(f.transactionId()), round(f.valueToman()))).toList();
    }

    /**
     * Top-level expense categories whose spending in {@code month} (so far, for the current
     * month) is far above their own last six months: at least two standard deviations and 30%
     * above the mean, and material relative to an average month's spending.
     */
    @Transactional(readOnly = true)
    public List<Anomaly> anomalies(long userId, JalaliMonth month) {
        JalaliMonth historyFrom = month.plusMonths(-ANOMALY_HISTORY_MONTHS);
        List<Flow> expenses = flows.flows(userId, historyFrom.startDate(), month.endDate()).stream()
                .filter(f -> f.kind() == FlowKind.EXPENSE && f.priced() && f.categoryId() != null)
                .toList();
        List<Flow> history = expenses.stream().filter(f -> f.date().isBefore(month.startDate())).toList();
        if (history.isEmpty()) {
            return List.of();
        }
        // Months before the first recorded expense are not "zero spending" months.
        JalaliMonth firstMonth = JalaliMonth.from(history.getFirst().date());
        int months = (int) firstMonth.monthsUntil(month);
        if (months < MIN_HISTORY_MONTHS) {
            return List.of();
        }
        CategoryTree tree = new CategoryTree(categories.list(userId));
        Map<Long, BigDecimal[]> series = new HashMap<>();
        Map<Long, BigDecimal> currentByRoot = new HashMap<>();
        BigDecimal historyTotal = BigDecimal.ZERO;
        for (Flow f : expenses) {
            Long root = tree.rootId(f.categoryId());
            JalaliMonth m = JalaliMonth.from(f.date());
            if (m.equals(month)) {
                currentByRoot.merge(root, f.valueToman(), BigDecimal::add);
            } else {
                BigDecimal[] values = series.computeIfAbsent(root, k -> zeros(months));
                int index = (int) firstMonth.monthsUntil(m);
                values[index] = values[index].add(f.valueToman());
                historyTotal = historyTotal.add(f.valueToman());
            }
        }
        BigDecimal averageMonth = historyTotal.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_EVEN);
        BigDecimal material = averageMonth.multiply(MATERIALITY);

        List<Anomaly> result = new ArrayList<>();
        currentByRoot.forEach((rootId, current) -> {
            Category category = tree.get(rootId);
            if (category == null) {
                return;
            }
            BigDecimal[] values = series.get(rootId);
            if (values == null) {
                if (current.compareTo(averageMonth.multiply(NEW_SPENDING)) >= 0) {
                    result.add(new Anomaly(rootId, category.getName(), category.getIcon(), round(current), BigDecimal.ZERO, null, null, months));
                }
                return;
            }
            BigDecimal mean = mean(values);
            BigDecimal deviation = stdDev(values, mean);
            BigDecimal excess = current.subtract(mean);
            if (mean.signum() == 0 || excess.compareTo(material) < 0 || current.compareTo(mean.multiply(MIN_RATIO)) < 0) {
                return;
            }
            BigDecimal z = deviation.signum() == 0 ? null : excess.divide(deviation, 2, RoundingMode.HALF_EVEN);
            boolean unusual = z == null ? current.compareTo(mean.multiply(FLAT_HISTORY_RATIO)) >= 0 : z.compareTo(Z_THRESHOLD) >= 0;
            if (unusual) {
                result.add(new Anomaly(rootId, category.getName(), category.getIcon(), round(current), round(mean),
                        current.divide(mean, 2, RoundingMode.HALF_EVEN), z, months));
            }
        });
        result.sort(Comparator.comparing((Anomaly a) -> a.currentToman().subtract(a.averageToman())).reversed());
        return result.size() > MAX_ANOMALIES ? result.subList(0, MAX_ANOMALIES) : result;
    }

    private static CategoryLine line(Long id, String name, String icon, Totals current, Totals previous, Totals average,
            BigDecimal total, int span, List<CategoryLine> children) {
        BigDecimal share = total.signum() == 0 ? BigDecimal.ZERO : current.value.divide(total, 4, RoundingMode.HALF_EVEN);
        BigDecimal avg = span == 1 ? average.value.divide(BigDecimal.valueOf(3), 0, RoundingMode.HALF_EVEN) : null;
        return new CategoryLine(id, name, icon, round(current.value), share, current.count, round(previous.value), avg, children);
    }

    private static void checkSpan(int span) {
        if (span < 1 || span > 24) {
            throw ApiException.badRequest("report.invalidSpan");
        }
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] values = new BigDecimal[n];
        Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    static BigDecimal mean(BigDecimal[] values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.length), 4, RoundingMode.HALF_EVEN);
    }

    /** Sample standard deviation. */
    static BigDecimal stdDev(BigDecimal[] values, BigDecimal mean) {
        if (values.length < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal squares = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal d = v.subtract(mean);
            squares = squares.add(d.multiply(d));
        }
        return squares.divide(BigDecimal.valueOf(values.length - 1L), 8, RoundingMode.HALF_EVEN).sqrt(MathContext.DECIMAL64);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_EVEN);
    }

    private static final class Totals {
        static final Totals EMPTY = new Totals();

        BigDecimal value = BigDecimal.ZERO;
        int count;

        void add(BigDecimal v) {
            value = value.add(v);
            count++;
        }

        void merge(Totals other) {
            value = value.add(other.value);
            count += other.count;
        }
    }

    private static final class CategoryTree {

        private final Map<Long, Category> byId = new HashMap<>();

        CategoryTree(List<Category> list) {
            list.forEach(c -> byId.put(c.getId(), c));
        }

        Category get(Long id) {
            return byId.get(id);
        }

        Long rootId(Long id) {
            Category c = byId.get(id);
            return c == null || c.getParentId() == null ? id : c.getParentId();
        }

        String icon(Long id) {
            Category c = byId.get(id);
            if (c == null) {
                return null;
            }
            if (c.getIcon() != null || c.getParentId() == null) {
                return c.getIcon();
            }
            Category parent = byId.get(c.getParentId());
            return parent == null ? null : parent.getIcon();
        }
    }
}
