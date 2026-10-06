package ir.karname.budget;

import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.common.web.ApiException;
import ir.karname.transaction.FlowValuation;
import ir.karname.transaction.FlowValuation.Flow;
import ir.karname.transaction.FlowValuation.FlowKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monthly budgets per expense category. A budget on a top-level category covers its
 * subcategories too; spending is valued in Toman at the price of each day.
 */
@Service
public class BudgetService {

    private static final BigDecimal WARNING_RATIO = new BigDecimal("0.8");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e18");
    /** Pace-based projections from the first few days of a month are noise. */
    private static final int MIN_DAYS_FOR_PROJECTION = 5;

    private final BudgetRepository budgets;
    private final CategoryService categories;
    private final FlowValuation flows;
    private final Clock clock;

    public BudgetService(BudgetRepository budgets, CategoryService categories, FlowValuation flows, Clock clock) {
        this.budgets = budgets;
        this.categories = categories;
        this.flows = flows;
        this.clock = clock;
    }

    public enum Status {
        OK, WARNING, OVER
    }

    public enum RemoveScope {
        /** Only this month; later months keep their budget. */
        MONTH,
        /** This month and every later month. */
        FORWARD
    }

    public record BudgetRequest(BigDecimal amount, Boolean recurring) {
    }

    /**
     * One category's budget in a month. {@code since} is the month the amount was set; {@code
     * projected} estimates the month's total spending (current month only, see {@link #projection}).
     */
    public record BudgetItem(long categoryId, String name, String icon, Long parentId, String parentName, BigDecimal amount,
            BigDecimal spent, BigDecimal remaining, BigDecimal ratio, Status status, boolean recurring, String since,
            BigDecimal projected, int unpricedCount) {
    }

    /** {@code totalBudget} and {@code totalSpent} count a subcategory once even when its parent also has a budget. */
    public record BudgetMonth(String month, int daysInMonth, int daysElapsed, boolean current, BigDecimal totalBudget,
            BigDecimal totalSpent, BigDecimal unbudgetedSpent, BigDecimal totalExpense, List<BudgetItem> items) {
    }

    public record Suggestion(long categoryId, String name, String icon, BigDecimal averageToman, BigDecimal suggestedToman,
            BigDecimal currentBudget) {
    }

    @Transactional(readOnly = true)
    public BudgetMonth month(long userId, JalaliMonth month) {
        LocalDate today = LocalDate.now(clock);
        JalaliMonth current = JalaliMonth.from(today);
        int daysInMonth = month.lengthOfMonth();
        int cmp = month.compareTo(current);
        int daysElapsed = cmp < 0 ? daysInMonth : cmp == 0 ? JalaliDate.from(today).day() : 0;

        CategoryIndex index = new CategoryIndex(categories.list(userId));
        Map<Long, Budget> effective = effective(userId, month);
        // Every expense dated in the month counts, including ones recorded ahead of their date.
        List<Flow> expenses = flows.flows(userId, month.startDate(), month.endDate()).stream()
                .filter(f -> f.kind() == FlowKind.EXPENSE)
                .toList();
        Projection projection = cmp == 0 && !effective.isEmpty() ? projection(userId, month, daysElapsed) : null;

        List<BudgetItem> items = new ArrayList<>();
        Set<Long> coveredAll = new HashSet<>();
        BigDecimal totalBudget = BigDecimal.ZERO;
        for (Budget b : effective.values().stream().sorted(Comparator.comparingInt(b -> index.order(b.getCategoryId()))).toList()) {
            Category category = index.get(b.getCategoryId());
            if (category == null) {
                continue;
            }
            Set<Long> covered = index.covered(category);
            coveredAll.addAll(covered);
            Long parentId = category.getParentId();
            if (parentId == null || !effective.containsKey(parentId)) {
                totalBudget = totalBudget.add(b.getAmountToman());
            }
            BigDecimal spent = BigDecimal.ZERO;
            BigDecimal spentToDate = BigDecimal.ZERO;
            int count = 0;
            int unpriced = 0;
            for (Flow f : expenses) {
                if (f.categoryId() == null || !covered.contains(f.categoryId())) {
                    continue;
                }
                if (!f.priced()) {
                    unpriced++;
                    continue;
                }
                spent = spent.add(f.valueToman());
                if (!f.date().isAfter(today)) {
                    spentToDate = spentToDate.add(f.valueToman());
                    count++;
                }
            }
            BigDecimal amount = b.getAmountToman();
            BigDecimal projected = projection == null ? null
                    : projection.project(covered, spentToDate, spent.subtract(spentToDate), count, daysInMonth);
            Category parent = parentId == null ? null : index.get(parentId);
            String icon = category.getIcon() != null ? category.getIcon() : parent == null ? null : parent.getIcon();
            items.add(new BudgetItem(category.getId(), category.getName(), icon, parentId, parent == null ? null : parent.getName(),
                    round(amount), round(spent), round(amount.subtract(spent)), ratio(spent, amount), status(spent, amount),
                    b.isRecurring(), b.getMonth(), projected, unpriced));
        }

        BigDecimal totalSpent = BigDecimal.ZERO;
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (Flow f : expenses) {
            if (!f.priced()) {
                continue;
            }
            totalExpense = totalExpense.add(f.valueToman());
            if (f.categoryId() != null && coveredAll.contains(f.categoryId())) {
                totalSpent = totalSpent.add(f.valueToman());
            }
        }
        return new BudgetMonth(month.toString(), daysInMonth, daysElapsed, cmp == 0, round(totalBudget), round(totalSpent),
                round(totalExpense.subtract(totalSpent)), round(totalExpense), items);
    }

    @Transactional
    public void set(long userId, JalaliMonth month, long categoryId, BudgetRequest request) {
        Category category = categories.require(userId, categoryId);
        if (category.getKind() != CategoryKind.EXPENSE) {
            throw ApiException.badRequest("budget.categoryNotExpense");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) >= 0 || amount.stripTrailingZeros().scale() > 8) {
            throw ApiException.badRequest("budget.invalidAmount");
        }
        boolean recurring = request.recurring() == null || request.recurring();
        String key = month.toString();
        budgets.findByUserIdAndCategoryIdAndMonth(userId, categoryId, key).ifPresentOrElse(
                b -> {
                    b.setAmountToman(amount);
                    b.setRecurring(recurring);
                },
                () -> budgets.save(new Budget(userId, categoryId, key, amount, recurring)));
    }

    /**
     * Removes a category's budget for one month, or from that month on. Amounts inherited from
     * earlier months are suppressed with zero rows, so the history of earlier months stays intact.
     */
    @Transactional
    public void remove(long userId, JalaliMonth month, long categoryId, RemoveScope scope) {
        categories.require(userId, categoryId);
        String key = month.toString();
        if (scope == RemoveScope.FORWARD) {
            budgets.deleteFrom(userId, categoryId, key);
            budgets.flush();
            if (inherited(userId, categoryId, key)) {
                budgets.save(new Budget(userId, categoryId, key, BigDecimal.ZERO, true));
            }
            return;
        }
        BigDecimal carry = null;
        Budget atMonth = budgets.findByUserIdAndCategoryIdAndMonth(userId, categoryId, key).orElse(null);
        if (atMonth != null) {
            if (atMonth.isRecurring() && atMonth.getAmountToman().signum() > 0) {
                carry = atMonth.getAmountToman();
            }
            budgets.delete(atMonth);
            budgets.flush();
        }
        String next = month.next().toString();
        if (carry != null && budgets.findByUserIdAndCategoryIdAndMonth(userId, categoryId, next).isEmpty()) {
            budgets.save(new Budget(userId, categoryId, next, carry, true));
        }
        if (inherited(userId, categoryId, key)) {
            budgets.save(new Budget(userId, categoryId, key, BigDecimal.ZERO, false));
        }
    }

    /**
     * Month-end estimate for the current month. Spending already made plus what the user usually
     * spends in the rest of a month: the average, over the previous three months with any expense,
     * of the spending dated after today's day of the month. Rent paid on the 3rd is therefore not
     * extrapolated. Without history, steady spending (three or more expenses, at least five days
     * in) is extrapolated at its daily pace; otherwise there is no estimate.
     */
    private Projection projection(long userId, JalaliMonth month, int day) {
        List<Flow> history = flows.flows(userId, month.plusMonths(-3).startDate(), month.previous().endDate()).stream()
                .filter(f -> f.kind() == FlowKind.EXPENSE && f.priced())
                .toList();
        Set<JalaliMonth> activeMonths = new HashSet<>();
        history.forEach(f -> activeMonths.add(JalaliMonth.from(f.date())));
        List<Flow> later = history.stream().filter(f -> f.categoryId() != null && JalaliDate.from(f.date()).day() > day).toList();
        return new Projection(later, activeMonths.size(), day);
    }

    private record Projection(List<Flow> laterInMonth, int historyMonths, int day) {

        BigDecimal project(Set<Long> covered, BigDecimal spentToDate, BigDecimal ahead, int count, int daysInMonth) {
            if (historyMonths > 0) {
                BigDecimal usual = laterInMonth.stream().filter(f -> covered.contains(f.categoryId())).map(Flow::valueToman)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(historyMonths), 2, RoundingMode.HALF_EVEN);
                return round(spentToDate.add(usual.max(ahead)));
            }
            if (count >= 3 && day >= MIN_DAYS_FOR_PROJECTION) {
                return round(spentToDate.multiply(BigDecimal.valueOf(daysInMonth)).divide(BigDecimal.valueOf(day), 2, RoundingMode.HALF_EVEN)
                        .add(ahead));
            }
            return null;
        }
    }

    /**
     * Suggested budgets for top-level expense categories: the average monthly spending of the
     * three months before {@code month} (counting only months since the first expense), rounded up
     * to two significant digits.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> suggestions(long userId, JalaliMonth month) {
        JalaliMonth first = month.plusMonths(-3);
        List<Flow> expenses = flows.flows(userId, first.startDate(), month.previous().endDate()).stream()
                .filter(f -> f.kind() == FlowKind.EXPENSE && f.priced() && f.categoryId() != null)
                .toList();
        if (expenses.isEmpty()) {
            return List.of();
        }
        long months = Math.max(1, JalaliMonth.from(expenses.getFirst().date()).monthsUntil(month));
        CategoryIndex index = new CategoryIndex(categories.list(userId));
        Map<Long, BigDecimal> byRoot = new LinkedHashMap<>();
        for (Flow f : expenses) {
            Category root = index.root(f.categoryId());
            if (root != null) {
                byRoot.merge(root.getId(), f.valueToman(), BigDecimal::add);
            }
        }
        Map<Long, Budget> effective = effective(userId, month);
        List<Suggestion> result = new ArrayList<>();
        byRoot.forEach((id, total) -> {
            Category c = index.get(id);
            BigDecimal average = total.divide(BigDecimal.valueOf(months), 0, RoundingMode.HALF_EVEN);
            if (average.signum() > 0 && !c.isArchived()) {
                Budget current = effective.get(id);
                result.add(new Suggestion(id, c.getName(), c.getIcon(), average, niceCeil(average),
                        current == null ? null : round(current.getAmountToman())));
            }
        });
        result.sort(Comparator.comparing(Suggestion::averageToman).reversed());
        return result;
    }

    /** Budgets in effect in {@code month}, by category; zero amounts ("no budget") are left out. */
    private Map<Long, Budget> effective(long userId, JalaliMonth month) {
        String key = month.toString();
        Map<Long, Budget> result = new HashMap<>();
        for (Budget b : budgets.findUpTo(userId, key)) {
            if (b.getMonth().equals(key) || b.isRecurring()) {
                result.put(b.getCategoryId(), b);
            }
        }
        result.values().removeIf(b -> b.getAmountToman().signum() == 0);
        return result;
    }

    private boolean inherited(long userId, long categoryId, String month) {
        return budgets.findFirstByUserIdAndCategoryIdAndRecurringTrueAndMonthLessThanOrderByMonthDesc(userId, categoryId, month)
                .filter(b -> b.getAmountToman().signum() > 0)
                .isPresent();
    }

    static BigDecimal ratio(BigDecimal spent, BigDecimal amount) {
        return amount.signum() == 0 ? BigDecimal.ZERO : spent.divide(amount, 4, RoundingMode.HALF_EVEN);
    }

    static Status status(BigDecimal spent, BigDecimal amount) {
        if (spent.compareTo(amount) > 0) {
            return Status.OVER;
        }
        return spent.compareTo(amount.multiply(WARNING_RATIO)) >= 0 ? Status.WARNING : Status.OK;
    }

    /** Rounds up to two significant digits: 3,456,789 → 3,500,000; 862,000 → 870,000. */
    static BigDecimal niceCeil(BigDecimal value) {
        BigDecimal integer = value.setScale(0, RoundingMode.CEILING);
        int digits = integer.precision();
        if (digits <= 2) {
            return integer;
        }
        BigDecimal step = BigDecimal.TEN.pow(digits - 2);
        return integer.divide(step, 0, RoundingMode.CEILING).multiply(step);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_EVEN);
    }

    /** Category lookups: display order, parents, and the categories a budget covers. */
    private static final class CategoryIndex {

        private final Map<Long, Category> byId = new HashMap<>();
        private final Map<Long, List<Long>> children = new HashMap<>();
        private final Map<Long, Integer> order = new HashMap<>();

        CategoryIndex(List<Category> list) {
            list.forEach(c -> byId.put(c.getId(), c));
            list.stream().filter(c -> c.getParentId() != null)
                    .forEach(c -> children.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c.getId()));
            int i = 0;
            for (Category root : list.stream().filter(c -> c.getParentId() == null).toList()) {
                order.put(root.getId(), i++);
                for (Long child : children.getOrDefault(root.getId(), List.of())) {
                    order.put(child, i++);
                }
            }
        }

        Category get(Long id) {
            return byId.get(id);
        }

        Category root(Long id) {
            Category c = byId.get(id);
            return c == null || c.getParentId() == null ? c : byId.get(c.getParentId());
        }

        int order(Long id) {
            return order.getOrDefault(id, Integer.MAX_VALUE);
        }

        Set<Long> covered(Category c) {
            Set<Long> ids = new HashSet<>();
            ids.add(c.getId());
            if (c.getParentId() == null) {
                ids.addAll(children.getOrDefault(c.getId(), List.of()));
            }
            return ids;
        }
    }
}
