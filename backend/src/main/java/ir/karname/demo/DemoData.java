package ir.karname.demo;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountService.AccountRequest;
import ir.karname.account.AccountType;
import ir.karname.account.Bank;
import ir.karname.budget.BudgetService;
import ir.karname.budget.BudgetService.BudgetRequest;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.cheque.ChequeDirection;
import ir.karname.cheque.ChequeService;
import ir.karname.cheque.ChequeService.ChequeRequest;
import ir.karname.common.jalali.JalaliDate;
import ir.karname.common.jalali.JalaliMonth;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityKind;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.CommodityService.CustomCommodityRequest;
import ir.karname.commodity.PriceService;
import ir.karname.goal.GoalService;
import ir.karname.goal.GoalService.GoalRequest;
import ir.karname.loan.LoanMethod;
import ir.karname.loan.LoanService;
import ir.karname.loan.LoanService.InstallmentView;
import ir.karname.loan.LoanService.LoanRequest;
import ir.karname.loan.LoanService.LoanView;
import ir.karname.loan.LoanService.PaymentRequest;
import ir.karname.loan.LoanService.StartMode;
import ir.karname.recurring.Frequency;
import ir.karname.recurring.RecurringMode;
import ir.karname.recurring.RecurringService;
import ir.karname.recurring.RecurringService.RuleRequest;
import ir.karname.transaction.TransactionService;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionType;
import ir.karname.user.User;
import ir.karname.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a user with about six months of believable finances ending today: accounts in Toman,
 * dollars, gold, coins, crypto and shares; salary and everyday spending; purchases of currency
 * and gold; a loan being repaid; budgets, goals, recurring rules and cheques. Everything goes
 * through the services, so balances, implied prices and reports come out as for real data.
 */
@Service
public class DemoData {

    /** Months of history before the current one. */
    static final int MONTHS = 6;

    /** Toman prices: at the opening, at the start of each month, and today. */
    private static final Map<String, long[]> PRICES = Map.of(
            "USD", new long[] {82_000, 84_000, 86_500, 90_000, 93_500, 98_000, 101_000, 102_500},
            "EUR", new long[] {89_000, 91_500, 94_000, 98_000, 101_500, 106_000, 109_500, 111_000},
            "GOLD18", new long[] {6_900_000, 7_100_000, 7_350_000, 7_700_000, 8_050_000, 8_400_000, 8_800_000, 8_950_000},
            "COIN_EMAMI", new long[] {76_000_000, 78_000_000, 81_000_000, 85_000_000, 89_000_000, 92_500_000, 96_000_000, 98_000_000},
            "USDT", new long[] {83_000, 85_000, 87_500, 91_000, 94_500, 99_000, 102_000, 104_000});
    private static final long[] SHARE_PRICES = {5_200, 5_350, 5_600, 5_480, 5_900, 6_150, 6_400, 6_380};

    private final UserService users;
    private final AccountService accounts;
    private final CommodityService commodities;
    private final PriceService prices;
    private final CategoryService categories;
    private final TransactionService transactions;
    private final BudgetService budgets;
    private final GoalService goals;
    private final LoanService loans;
    private final RecurringService recurring;
    private final ChequeService cheques;
    private final Clock clock;

    DemoData(UserService users, AccountService accounts, CommodityService commodities, PriceService prices, CategoryService categories,
            TransactionService transactions, BudgetService budgets, GoalService goals, LoanService loans, RecurringService recurring,
            ChequeService cheques, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.commodities = commodities;
        this.prices = prices;
        this.categories = categories;
        this.transactions = transactions;
        this.budgets = budgets;
        this.goals = goals;
        this.loans = loans;
        this.recurring = recurring;
        this.cheques = cheques;
        this.clock = clock;
    }

    /** Creates the user and all its data in one transaction; returns the user's id. */
    @Transactional
    public long create(String username, String displayName, String password) {
        User user = users.provision(username, displayName, password);
        new Builder(user.getId()).build();
        return user.getId();
    }

    private final class Builder {

        private final long userId;
        private final LocalDate today = LocalDate.now(clock);
        private final ZoneId zone = clock.getZone();
        private final JalaliMonth current = JalaliMonth.from(today);
        private final JalaliMonth first = current.plusMonths(-MONTHS);
        private final LocalDate opening = first.startDate().minusDays(1);
        private final Map<String, Long> categoryIds;

        Builder(long userId) {
            this.userId = userId;
            // leaf names are unique within a kind in the default set
            this.categoryIds = categories.list(userId).stream().collect(Collectors.toMap(
                    c -> c.getKind() + ":" + c.getName(), Category::getId, (a, b) -> a));
        }

        void build() {
            Commodity shares = commodities.createCustom(userId, new CustomCommodityRequest("سهام فولاد", "سهم", CommodityKind.SECURITY, 0));
            recordPrices(shares);

            Account mellat = account("ملت حقوق", AccountType.BANK, Commodity.TOMAN, Bank.MELLAT, List.of("4321"), null, "85000000");
            Account saman = account("کارت سامان", AccountType.BANK, Commodity.TOMAN, Bank.SAMAN, List.of("5678"), null, "12000000");
            Account cash = account("کیف پول", AccountType.CASH, Commodity.TOMAN, null, null, null, "3000000");
            Account usd = account("دلار نقد", AccountType.CURRENCY, "USD", null, null, null, "500");
            Account gold = account("طلای ۱۸ عیار", AccountType.GOLD, "GOLD18", null, null, null, "12");
            Account coin = account("سکه امامی", AccountType.GOLD, "COIN_EMAMI", null, null, null, "2");
            Account usdt = account("تتر", AccountType.CRYPTO, "USDT", null, null, null, "1500");
            account("سبد بورس", AccountType.INVESTMENT, shares.getCode(), null, null, null, "20000");
            account("طلب از علی", AccountType.RECEIVABLE, Commodity.TOMAN, null, null, "علی", "8000000");

            for (int m = 0; m <= MONTHS; m++) {
                month(m, mellat, saman, cash);
            }
            exchange(1, 10, mellat, usd, "USD", 300, "خرید دلار");
            exchange(3, 15, mellat, gold, "GOLD18", 5, "خرید طلا");
            exchange(5, 20, mellat, coin, "COIN_EMAMI", 1, "خرید سکه");
            LocalDate freelance = latest(10);
            transaction(TransactionType.INCOME, freelance, usd, "450", null, null, income("پروژه و درآمد آزاد"), "پروژه فریلنس");
            // a few recent ones left uncategorized, for the smart categorization
            transaction(TransactionType.EXPENSE, recent(4), mellat, "1450000", null, null, null, "رستوران شاندیز");
            transaction(TransactionType.EXPENSE, recent(2), saman, "310000", null, null, null, "داروخانه شبانه‌روزی");
            transaction(TransactionType.EXPENSE, recent(1), cash, "850000", null, null, null, null);

            loan(mellat);
            budgets();
            goals(usd, usdt, coin);
            rules(mellat);
            cheques(mellat);
        }

        private void month(int m, Account mellat, Account saman, Account cash) {
            JalaliMonth month = first.plusMonths(m);
            String name = JalaliDate.monthName(month.month());
            on(m, 1, d -> transaction(TransactionType.INCOME, d, mellat, m >= 3 ? "68000000" : "62000000", null, null,
                    income("حقوق و دستمزد"), "حقوق " + name));
            on(m, 3, d -> transaction(TransactionType.EXPENSE, d, mellat, "18000000", null, null, expense("اجاره"), "اجاره خانه"));
            int[] groceryDays = {2, 9, 16, 23};
            for (int i = 0; i < groceryDays.length; i++) {
                long amount = 2_400_000 + (long) ((m * 7 + i * 3) % 9) * 150_000;
                Account from = i % 2 == 0 ? mellat : saman;
                on(m, groceryDays[i], d -> transaction(TransactionType.EXPENSE, d, from, String.valueOf(amount), null, null,
                        expense("سوپرمارکت"), "خرید هفتگی هایپراستار"));
            }
            int[] rideDays = {4, 8, 12, 19, 26};
            for (int i = 0; i < rideDays.length; i++) {
                long amount = 85_000 + (long) ((m + i) % 5) * 55_000;
                on(m, rideDays[i], d -> transaction(TransactionType.EXPENSE, d, saman, String.valueOf(amount), null, null,
                        expense("تاکسی اینترنتی"), "اسنپ"));
            }
            on(m, 6, d -> transaction(TransactionType.EXPENSE, d, cash, "180000", null, null, expense("کافه"), "کافه"));
            on(m, 11, d -> transaction(TransactionType.EXPENSE, d, mellat, String.valueOf(950_000 + (m % 3) * 420_000L), null, null,
                    expense("رستوران"), "رستوران با خانواده"));
            on(m, 14, d -> transaction(TransactionType.EXPENSE, d, mellat, String.valueOf(320_000 + m * 25_000L), null, null,
                    expense("برق"), "قبض برق"));
            on(m, 15, d -> transaction(TransactionType.EXPENSE, d, mellat, "590000", null, null, expense("اینترنت"), "اینترنت خانگی"));
            on(m, 17, d -> transaction(TransactionType.EXPENSE, d, mellat, "450000", null, null, expense("VPN و سرویس خارجی"), "اشتراک VPN"));
            if (m % 2 == 0) {
                on(m, 21, d -> transaction(TransactionType.EXPENSE, d, mellat, "420000", null, null, expense("دارو"), "داروخانه"));
            }
            if (m == 2) {
                on(m, 18, d -> transaction(TransactionType.EXPENSE, d, saman, "3400000", null, null, expense("پوشاک"), "خرید لباس"));
            }
            on(m, 20, d -> transaction(TransactionType.TRANSFER, d, mellat, "9000000", saman, null, null, "شارژ کارت سامان"));
            on(m, 25, d -> transaction(TransactionType.TRANSFER, d, mellat, "1200000", cash, null, null, "برداشت نقدی"));
        }

        /** A purchase of {@code quantity} units at that month's price, paid from a Toman account. */
        private void exchange(int m, int day, Account from, Account to, String commodity, long quantity, String description) {
            on(m, day, d -> {
                BigDecimal price = BigDecimal.valueOf(PRICES.get(commodity)[m]);
                transaction(TransactionType.TRANSFER, d, from, price.multiply(BigDecimal.valueOf(quantity)).toPlainString(), to,
                        String.valueOf(quantity), null, description);
            });
        }

        private void loan(Account mellat) {
            LocalDate received = day(1, 8);
            LoanView loan = loans.create(userId, new LoanRequest("وام قرض‌الحسنه", Bank.MELLI, null, new BigDecimal("100000000"),
                    new BigDecimal("4"), 24, first.plusMonths(2).atDay(8).toGregorian(), LoanMethod.ANNUITY, null, mellat.getId(), null,
                    StartMode.NEW, mellat.getId(), received, null));
            for (InstallmentView installment : loan.installments()) {
                if (!installment.dueDate().isAfter(today)) {
                    loans.pay(userId, loan.id(), installment.number(), new PaymentRequest(mellat.getId(), installment.dueDate(), null));
                }
            }
        }

        private void budgets() {
            budgets.set(userId, first, expense("سوپرمارکت"), new BudgetRequest(new BigDecimal("11000000"), true));
            budgets.set(userId, first, expense("رستوران"), new BudgetRequest(new BigDecimal("2000000"), true));
            budgets.set(userId, first, expense("تاکسی اینترنتی"), new BudgetRequest(new BigDecimal("1300000"), true));
            budgets.set(userId, first, expense("کافه"), new BudgetRequest(new BigDecimal("500000"), true));
        }

        private void goals(Account usd, Account usdt, Account coin) {
            goals.create(userId, new GoalRequest("صندوق مهاجرت", null, new BigDecimal("15000"), "EUR", today.plusYears(2),
                    List.of(usd.getId(), usdt.getId()), null, "هزینه‌ی ویزا، بلیت و شش ماه اول زندگی", null));
            goals.create(userId, new GoalRequest("ده سکه برای آینده", null, BigDecimal.TEN, "COIN_EMAMI", null, List.of(coin.getId()),
                    null, null, null));
        }

        private void rules(Account mellat) {
            JalaliMonth next = current.next();
            recurring.create(userId, new RuleRequest("اجاره خانه", TransactionType.EXPENSE, mellat.getId(), null, new BigDecimal("18000000"),
                    null, expense("اجاره"), "اجاره خانه", Frequency.MONTHLY, 1, 3, null, null, next.atDay(3).toGregorian(), null,
                    RecurringMode.REMIND, true));
            recurring.create(userId, new RuleRequest("اینترنت خانگی", TransactionType.EXPENSE, mellat.getId(), null, new BigDecimal("590000"),
                    null, expense("اینترنت"), "اینترنت خانگی", Frequency.MONTHLY, 1, 15, null, null, next.atDay(15).toGregorian(), null,
                    RecurringMode.AUTO, true));
            recurring.create(userId, new RuleRequest("حقوق", TransactionType.INCOME, mellat.getId(), null, new BigDecimal("68000000"),
                    null, income("حقوق و دستمزد"), "حقوق ماهانه", Frequency.MONTHLY, 1, 1, null, null, next.atDay(1).toGregorian(), null,
                    RecurringMode.AUTO, true));
        }

        private void cheques(Account mellat) {
            cheques.create(userId, new ChequeRequest(ChequeDirection.ISSUED, "1234567890123456", "۸۷۴۵۱۲", Bank.MELLAT, mellat.getId(), null,
                    null, "نمایشگاه مبل", new BigDecimal("45000000"), today.minusDays(10), today.plusDays(18), "خرید مبل", null));
            cheques.create(userId, new ChequeRequest(ChequeDirection.RECEIVED, "6543210987654321", "۳۲۱۹۸۷", Bank.PASARGAD, mellat.getId(),
                    null, income("پروژه و درآمد آزاد"), "شرکت آلفا", new BigDecimal("25000000"), today.minusDays(3), today.plusDays(40),
                    "تسویه‌ی پروژه", null));
        }

        // ------------------------------------------------------------ helpers

        private Account account(String name, AccountType type, String commodity, Bank bank, List<String> hints, String counterparty,
                String openingBalance) {
            if (!commodity.equals(Commodity.TOMAN)) {
                recordPrices(commodities.require(userId, commodity));
            }
            return accounts.create(userId, new AccountRequest(name, type, commodity, bank, hints, counterparty, null, true, null,
                    new BigDecimal(openingBalance), opening));
        }

        private void recordPrices(Commodity commodity) {
            long[] series = PRICES.containsKey(commodity.getCode()) ? PRICES.get(commodity.getCode()) : SHARE_PRICES;
            Instant last = null;
            for (int i = 0; i < series.length; i++) {
                LocalDate date = i == 0 ? opening : i <= MONTHS ? first.plusMonths(i).startDate() : today;
                Instant at = date.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant();
                if (last != null && !at.isAfter(last)) {
                    at = last.plusSeconds(3600);
                }
                if (at.isAfter(clock.instant())) {
                    at = clock.instant();
                }
                if (last == null || at.isAfter(last)) {
                    prices.recordManual(userId, commodity, BigDecimal.valueOf(series[i]), at, false);
                    last = at;
                }
            }
        }

        private void transaction(TransactionType type, LocalDate date, Account account, String amount, Account to, String toAmount,
                Long categoryId, String description) {
            transactions.create(userId, new TransactionRequest(type, date, account.getId(), new BigDecimal(amount),
                    to == null ? null : to.getId(), toAmount == null ? null : new BigDecimal(toAmount), null, categoryId, description, null,
                    List.of()));
        }

        /** Runs {@code action} for that day of month {@code m}, unless the day is still to come. */
        private void on(int m, int day, java.util.function.Consumer<LocalDate> action) {
            LocalDate date = day(m, day);
            if (!date.isAfter(today)) {
                action.accept(date);
            }
        }

        private LocalDate day(int m, int day) {
            JalaliMonth month = first.plusMonths(m);
            return month.atDay(Math.min(day, month.lengthOfMonth())).toGregorian();
        }

        /** That day of this month if it has passed, else of the previous month. */
        private LocalDate latest(int day) {
            LocalDate date = day(MONTHS, day);
            return date.isAfter(today) ? day(MONTHS - 1, day) : date;
        }

        private LocalDate recent(int daysAgo) {
            return today.minusDays(daysAgo);
        }

        private Long expense(String name) {
            return category(CategoryKind.EXPENSE, name);
        }

        private Long income(String name) {
            return category(CategoryKind.INCOME, name);
        }

        private Long category(CategoryKind kind, String name) {
            Long id = categoryIds.get(kind + ":" + name);
            if (id == null) {
                throw new IllegalStateException("Default category missing: " + name);
            }
            return id;
        }
    }
}
