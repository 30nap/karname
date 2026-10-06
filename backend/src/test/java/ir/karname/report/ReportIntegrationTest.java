package ir.karname.report;

import ir.karname.account.Account;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06). */
class ReportIntegrationTest extends FinanceTestSupport {

    @Test
    void monthlyTotalsCountTransferFeesAsExpenses() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "100000000");
        Account cash = bank(user, "نقد", null);
        income(user, bank, "60000000", LocalDate.of(2026, 9, 1), category(user, "حقوق و دستمزد"), "حقوق شهریور");
        expense(user, bank, "20000000", LocalDate.of(2026, 9, 5), category(user, "اجاره"), "اجاره");
        transactionService.create(user.id(), new TransactionRequest(TransactionType.TRANSFER, LocalDate.of(2026, 9, 6), bank.getId(),
                new BigDecimal("1000000"), cash.getId(), null, new BigDecimal("5000"), null, "برداشت", null, List.of()));
        income(user, bank, "62000000", LocalDate.of(2026, 9, 23), null, "حقوق مهر");
        expense(user, bank, "10000000", LocalDate.of(2026, 10, 1), null, "خرید");

        mvc.perform(getAs(user, "/api/v1/reports/monthly?months=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].month").value("1405-06"))
                .andExpect(jsonPath("$[0].incomeToman").value("60000000"))
                .andExpect(jsonPath("$[0].expenseToman").value("20005000"))
                .andExpect(jsonPath("$[0].netToman").value("39995000"))
                .andExpect(jsonPath("$[0].savingsRate").value("0.6666"))
                .andExpect(jsonPath("$[0].partial").value(false))
                .andExpect(jsonPath("$[1].month").value("1405-07"))
                .andExpect(jsonPath("$[1].savingsRate").value("0.8387"))
                .andExpect(jsonPath("$[1].partial").value(true));
        mvc.perform(getAs(user, "/api/v1/reports/monthly?months=0")).andExpect(status().isBadRequest());
    }

    @Test
    void categoryBreakdownRollsUpSubcategoriesAndComparesPeriods() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "500000000");
        long restaurant = category(user, "رستوران");
        long cafe = category(user, "کافه");
        long supermarket = category(user, "سوپرمارکت");
        // Mehr
        expense(user, bank, "2000000", LocalDate.of(2026, 9, 25), restaurant, "شام");
        expense(user, bank, "1000000", LocalDate.of(2026, 10, 2), cafe, "کافه");
        expense(user, bank, "3500000", LocalDate.of(2026, 10, 3), supermarket, "خرید");
        expense(user, bank, "1000000", LocalDate.of(2026, 10, 4), null, "نامعلوم");
        // Shahrivar (previous month) and the two months before it
        expense(user, bank, "1000000", LocalDate.of(2026, 9, 10), restaurant, "شام");
        expense(user, bank, "4000000", LocalDate.of(2026, 9, 11), supermarket, "خرید");
        expense(user, bank, "2000000", LocalDate.of(2026, 8, 11), supermarket, "خرید");
        expense(user, bank, "2000000", LocalDate.of(2026, 7, 11), supermarket, "خرید");

        mvc.perform(getAs(user, "/api/v1/reports/categories?month=1405-07"))
                .andExpect(jsonPath("$.totalToman").value("7500000"))
                .andExpect(jsonPath("$.previousTotalToman").value("5000000"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].name").value("خوراک و خواربار"))
                .andExpect(jsonPath("$.items[0].valueToman").value("3500000"))
                .andExpect(jsonPath("$.items[0].share").value("0.4667"))
                .andExpect(jsonPath("$.items[0].previousToman").value("4000000"))
                .andExpect(jsonPath("$.items[0].averageToman").value("2666667"))
                .andExpect(jsonPath("$.items[1].name").value("رستوران و کافه"))
                .andExpect(jsonPath("$.items[1].valueToman").value("3000000"))
                .andExpect(jsonPath("$.items[1].count").value(2))
                .andExpect(jsonPath("$.items[1].children[0].name").value("رستوران"))
                .andExpect(jsonPath("$.items[1].children[0].valueToman").value("2000000"))
                .andExpect(jsonPath("$.items[1].children[0].icon").value("utensils"))
                .andExpect(jsonPath("$.items[1].children[1].name").value("کافه"))
                .andExpect(jsonPath("$.items[2].categoryId").doesNotExist())
                .andExpect(jsonPath("$.items[2].share").value("0.1333"));

        mvc.perform(getAs(user, "/api/v1/reports/categories?month=1405-07&span=3"))
                .andExpect(jsonPath("$.fromMonth").value("1405-05"))
                .andExpect(jsonPath("$.totalToman").value("14500000"))
                .andExpect(jsonPath("$.items[0].averageToman").doesNotExist());
        mvc.perform(getAs(user, "/api/v1/reports/categories?span=30")).andExpect(status().isBadRequest());
    }

    @Test
    void topExpensesAreSortedByValue() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "500000000");
        expense(user, bank, "2000000", LocalDate.of(2026, 9, 25), null, "شام");
        expense(user, bank, "3500000", LocalDate.of(2026, 10, 3), null, "خرید ماهانه");
        expense(user, bank, "500000", LocalDate.of(2026, 10, 4), null, "تاکسی");

        mvc.perform(getAs(user, "/api/v1/reports/top?limit=2"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].transaction.description").value("خرید ماهانه"))
                .andExpect(jsonPath("$[0].valueToman").value("3500000"))
                .andExpect(jsonPath("$[1].transaction.description").value("شام"));
    }

    @Test
    void flagsSpendingFarAboveACategorysHistory() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "900000000");
        long restaurant = category(user, "رستوران");
        long supermarket = category(user, "سوپرمارکت");
        // the 10th of Farvardin..Shahrivar 1405
        List<LocalDate> history = List.of(LocalDate.of(2026, 3, 30), LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 31),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1));
        String[] dining = {"900000", "1100000", "1000000", "1200000", "800000", "1000000"};
        for (int i = 0; i < history.size(); i++) {
            expense(user, bank, dining[i], history.get(i), restaurant, "رستوران");
            expense(user, bank, "3000000", history.get(i), supermarket, "خرید");
        }
        expense(user, bank, "3500000", LocalDate.of(2026, 10, 1), restaurant, "مهمانی");
        expense(user, bank, "3100000", LocalDate.of(2026, 10, 2), supermarket, "خرید");
        expense(user, bank, "5000000", LocalDate.of(2026, 10, 3), category(user, "سفر"), "سفر شمال");

        mvc.perform(getAs(user, "/api/v1/reports/anomalies"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("تفریح و سفر"))
                .andExpect(jsonPath("$[0].currentToman").value("5000000"))
                .andExpect(jsonPath("$[0].ratio").doesNotExist())
                .andExpect(jsonPath("$[1].name").value("رستوران و کافه"))
                .andExpect(jsonPath("$[1].averageToman").value("1000000"))
                .andExpect(jsonPath("$[1].ratio").value("3.5"))
                .andExpect(jsonPath("$[1].zScore").value("17.68"))
                .andExpect(jsonPath("$[1].historyMonths").value(6));
    }

    @Test
    void anomaliesNeedThreeMonthsOfHistory() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "900000000");
        long restaurant = category(user, "رستوران");
        expense(user, bank, "1000000", LocalDate.of(2026, 9, 1), restaurant, "رستوران");
        expense(user, bank, "9000000", LocalDate.of(2026, 10, 1), restaurant, "مهمانی");
        mvc.perform(getAs(user, "/api/v1/reports/anomalies")).andExpect(jsonPath("$.length()").value(0));
    }
}
