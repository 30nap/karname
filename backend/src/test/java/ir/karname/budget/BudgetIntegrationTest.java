package ir.karname.budget;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06); Mehr 1405 runs from 2026-09-23 to 2026-10-22. */
class BudgetIntegrationTest extends FinanceTestSupport {

    private void budget(TestUser user, String month, long categoryId, String amount, boolean recurring) throws Exception {
        mvc.perform(putAs(user, "/api/v1/budgets/{month}/{category}", Map.of("amount", amount, "recurring", recurring), month, categoryId))
                .andExpect(status().isOk());
    }

    @Test
    void recurringBudgetAppliesFromItsMonthOn() throws Exception {
        TestUser user = createUser("sina");
        long dining = category(user, "رستوران و کافه");
        budget(user, "1405-07", dining, "5000000", true);

        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-06")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-08"))
                .andExpect(jsonPath("$.items[0].categoryId").value(dining))
                .andExpect(jsonPath("$.items[0].amount").value("5000000"))
                .andExpect(jsonPath("$.items[0].since").value("1405-07"))
                .andExpect(jsonPath("$.daysElapsed").value(0));
        mvc.perform(getAs(user, "/api/v1/budgets"))
                .andExpect(jsonPath("$.month").value("1405-07"))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.daysInMonth").value(30))
                .andExpect(jsonPath("$.daysElapsed").value(14));
    }

    @Test
    void spendingCoversSubcategoriesWithStatusAndProjection() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "100000000");
        long dining = category(user, "رستوران و کافه");
        budget(user, "1405-07", dining, "5000000", true);
        expense(user, bank, "2500000", LocalDate.of(2026, 9, 25), category(user, "رستوران"), "شام");
        expense(user, bank, "1600000", LocalDate.of(2026, 10, 1), category(user, "کافه"), "کافه");
        expense(user, bank, "900000", LocalDate.of(2026, 9, 20), category(user, "رستوران"), "شهریور"); // previous month
        expense(user, bank, "3000000", LocalDate.of(2026, 10, 2), category(user, "سوپرمارکت"), "خرید");

        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-07"))
                .andExpect(jsonPath("$.items[0].spent").value("4100000"))
                .andExpect(jsonPath("$.items[0].remaining").value("900000"))
                .andExpect(jsonPath("$.items[0].ratio").value("0.82"))
                .andExpect(jsonPath("$.items[0].status").value("WARNING"))
                .andExpect(jsonPath("$.items[0].icon").value("utensils"));

        expense(user, bank, "1000000", LocalDate.of(2026, 10, 5), category(user, "رستوران"), "مهمانی");
        // spent so far, plus what the last month spent after its 14th day (0.9M on Shahrivar 29)
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-07"))
                .andExpect(jsonPath("$.items[0].spent").value("5100000"))
                .andExpect(jsonPath("$.items[0].remaining").value("-100000"))
                .andExpect(jsonPath("$.items[0].status").value("OVER"))
                .andExpect(jsonPath("$.items[0].projected").value("6000000"))
                .andExpect(jsonPath("$.totalBudget").value("5000000"))
                .andExpect(jsonPath("$.totalSpent").value("5100000"))
                .andExpect(jsonPath("$.totalExpense").value("8100000"))
                .andExpect(jsonPath("$.unbudgetedSpent").value("3000000"));
    }

    @Test
    void lumpSumsAreNotExtrapolated() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "500000000");
        long rent = category(user, "اجاره");
        // rent on the 3rd of Tir, Mordad, Shahrivar and Mehr
        for (LocalDate day : List.of(LocalDate.of(2026, 6, 24), LocalDate.of(2026, 7, 25), LocalDate.of(2026, 8, 25), LocalDate.of(2026, 9, 25))) {
            expense(user, bank, "18000000", day, rent, "اجاره");
        }
        budget(user, "1405-07", category(user, "مسکن"), "18000000", true);
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-07"))
                .andExpect(jsonPath("$.items[0].status").value("WARNING"))
                .andExpect(jsonPath("$.items[0].projected").value("18000000"));
    }

    @Test
    void withoutHistorySteadySpendingIsExtrapolatedAtItsPace() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "500000000");
        long supermarket = category(user, "سوپرمارکت");
        expense(user, bank, "1000000", LocalDate.of(2026, 9, 24), supermarket, "خرید");
        expense(user, bank, "1000000", LocalDate.of(2026, 9, 30), supermarket, "خرید");
        expense(user, bank, "1100000", LocalDate.of(2026, 10, 5), supermarket, "خرید");
        expense(user, bank, "18000000", LocalDate.of(2026, 9, 25), category(user, "اجاره"), "اجاره");
        budget(user, "1405-07", category(user, "خوراک و خواربار"), "8000000", true);
        budget(user, "1405-07", category(user, "مسکن"), "20000000", true);
        // 3.1M in 14 of 30 days; a single rent payment is not extrapolated
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-07"))
                .andExpect(jsonPath("$.items[0].name").value("خوراک و خواربار"))
                .andExpect(jsonPath("$.items[0].projected").value("6642857"))
                .andExpect(jsonPath("$.items[1].name").value("مسکن"))
                .andExpect(jsonPath("$.items[1].projected").doesNotExist());
    }

    @Test
    void parentAndChildBudgetsAreNotCountedTwice() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "100000000");
        long dining = category(user, "رستوران و کافه");
        long restaurant = category(user, "رستوران");
        budget(user, "1405-07", dining, "5000000", true);
        budget(user, "1405-07", restaurant, "3000000", true);
        expense(user, bank, "2000000", LocalDate.of(2026, 10, 1), restaurant, "شام");

        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-07"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].categoryId").value(dining))
                .andExpect(jsonPath("$.items[0].spent").value("2000000"))
                .andExpect(jsonPath("$.items[1].categoryId").value(restaurant))
                .andExpect(jsonPath("$.items[1].parentName").value("رستوران و کافه"))
                .andExpect(jsonPath("$.items[1].spent").value("2000000"))
                .andExpect(jsonPath("$.totalBudget").value("5000000"))
                .andExpect(jsonPath("$.totalSpent").value("2000000"));
    }

    @Test
    void oneOffBudgetsAndRemovalScopes() throws Exception {
        TestUser user = createUser("sina");
        long food = category(user, "خوراک و خواربار");
        budget(user, "1405-05", food, "5000000", true);
        budget(user, "1405-06", food, "8000000", false);

        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-06"))
                .andExpect(jsonPath("$.items[0].amount").value("8000000"))
                .andExpect(jsonPath("$.items[0].recurring").value(false));
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-07"))
                .andExpect(jsonPath("$.items[0].amount").value("5000000"))
                .andExpect(jsonPath("$.items[0].since").value("1405-05"));

        // only this month: the inherited amount is suppressed for 1405-07 alone
        mvc.perform(deleteAs(user, "/api/v1/budgets/1405-07/{category}?scope=MONTH", food))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-08")).andExpect(jsonPath("$.items[0].amount").value("5000000"));

        // from this month on
        mvc.perform(deleteAs(user, "/api/v1/budgets/1405-08/{category}", food)).andExpect(status().isOk());
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-08")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-11")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-06")).andExpect(jsonPath("$.items[0].amount").value("8000000"));
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-05")).andExpect(jsonPath("$.items[0].amount").value("5000000"));

        // removing a recurring budget for its own month keeps it for the months after
        budget(user, "1405-10", food, "6000000", true);
        mvc.perform(deleteAs(user, "/api/v1/budgets/1405-10/{category}?scope=MONTH", food)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-11"))
                .andExpect(jsonPath("$.items[0].amount").value("6000000"))
                .andExpect(jsonPath("$.items[0].since").value("1405-11"));
    }

    @Test
    void foreignCurrencySpendingIsValuedAtItsDate() throws Exception {
        TestUser user = createUser("sina");
        Account dollars = account(user, "دلار", AccountType.CURRENCY, "USD", "100");
        globalPrice("USD", "100000", Instant.parse("2026-09-01T06:00:00Z"));
        globalPrice("USD", "120000", Instant.parse("2026-10-03T06:00:00Z"));
        long online = category(user, "اشتراک و سرویس آنلاین");
        budget(user, "1405-07", online, "2000000", true);
        expense(user, dollars, "10", LocalDate.of(2026, 10, 1), category(user, "VPN و سرویس خارجی"), "VPN");

        mvc.perform(getAs(user, "/api/v1/budgets?month=1405-07"))
                .andExpect(jsonPath("$.items[0].spent").value("1000000"))
                .andExpect(jsonPath("$.items[0].unpricedCount").value(0));
    }

    @Test
    void rejectsInvalidBudgets() throws Exception {
        TestUser user = createUser("sina");
        long salary = category(user, "حقوق و دستمزد");
        long food = category(user, "خوراک و خواربار");
        mvc.perform(putAs(user, "/api/v1/budgets/1405-07/{category}", Map.of("amount", "1000"), salary))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("budget.categoryNotExpense"));
        mvc.perform(putAs(user, "/api/v1/budgets/1405-07/{category}", Map.of("amount", "0"), food))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("budget.invalidAmount"));
        mvc.perform(putAs(user, "/api/v1/budgets/1405-13/{category}", Map.of("amount", "1000"), food))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.invalidMonth"));
        mvc.perform(getAs(user, "/api/v1/budgets?month=oops")).andExpect(status().isBadRequest());
        mvc.perform(putAs(user, "/api/v1/budgets/1405-07/{category}", Map.of("amount", "1000"), 999999L)).andExpect(status().isNotFound());
    }

    @Test
    void suggestsBudgetsFromTheLastThreeMonths() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "500000000");
        long supermarket = category(user, "سوپرمارکت");
        long restaurant = category(user, "رستوران");
        expense(user, bank, "3000000", LocalDate.of(2026, 7, 1), supermarket, "تیر");
        expense(user, bank, "3600000", LocalDate.of(2026, 8, 1), supermarket, "مرداد");
        expense(user, bank, "3300000", LocalDate.of(2026, 9, 1), supermarket, "شهریور");
        expense(user, bank, "1200000", LocalDate.of(2026, 7, 10), restaurant, "تیر");
        expense(user, bank, "750000", LocalDate.of(2026, 9, 10), restaurant, "شهریور");
        expense(user, bank, "99000000", LocalDate.of(2026, 10, 1), supermarket, "this month does not count");
        budget(user, "1405-07", category(user, "خوراک و خواربار"), "4000000", true);

        mvc.perform(getAs(user, "/api/v1/budgets/suggestions?month=1405-07"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("خوراک و خواربار"))
                .andExpect(jsonPath("$[0].averageToman").value("3300000"))
                .andExpect(jsonPath("$[0].suggestedToman").value("3300000"))
                .andExpect(jsonPath("$[0].currentBudget").value("4000000"))
                .andExpect(jsonPath("$[1].name").value("رستوران و کافه"))
                .andExpect(jsonPath("$[1].averageToman").value("650000"))
                .andExpect(jsonPath("$[1].suggestedToman").value("650000"));
    }
}
