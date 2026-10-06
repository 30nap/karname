package ir.karname.forecast;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06); 1 Aban is 2026-10-23 and 3 Aban 2026-10-25. */
class ForecastIntegrationTest extends FinanceTestSupport {

    private void post(TestUser user, String url, Map<String, Object> body) throws Exception {
        mvc.perform(postAs(user, url, body)).andExpect(status().isCreated());
    }

    @Test
    void projectsTheLiquidBalanceFromKnownObligations() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "50000000");
        Account cash = account(user, "کیف پول", AccountType.CASH, "IRT", "2000000");
        account(user, "دلار", AccountType.CURRENCY, "USD", "100"); // not liquid

        Map<String, Object> salary = new HashMap<>(Map.of("name", "حقوق", "type", "INCOME", "accountId", bank.getId(), "amount", "60000000",
                "frequency", "MONTHLY", "dayOfMonth", 1, "startDate", "2026-03-21", "mode", "AUTO"));
        post(user, "/api/v1/recurring", salary);
        post(user, "/api/v1/recurring", new HashMap<>(Map.of("name", "اجاره", "type", "EXPENSE", "accountId", bank.getId(), "amount", "18000000",
                "frequency", "MONTHLY", "startDate", "2026-09-25", "mode", "REMIND")));
        // moving money between liquid accounts changes nothing
        post(user, "/api/v1/recurring", new HashMap<>(Map.of("name", "پول نقد", "type", "TRANSFER", "accountId", bank.getId(),
                "toAccountId", cash.getId(), "amount", "1000000", "frequency", "WEEKLY", "startDate", "2026-10-01", "mode", "REMIND")));
        post(user, "/api/v1/loans", new HashMap<>(Map.of("name", "وام قرض‌الحسنه", "principal", "12000000", "annualRate", "0", "termMonths", 12,
                "firstDueDate", "2026-09-12", "start", "EXISTING", "paymentAccountId", bank.getId())));
        post(user, "/api/v1/cheques", new HashMap<>(Map.of("direction", "ISSUED", "accountId", bank.getId(), "counterparty", "فروشگاه",
                "amount", "20000000", "dueDate", "2026-10-15")));
        post(user, "/api/v1/cheques", new HashMap<>(Map.of("direction", "RECEIVED", "counterparty", "علی", "amount", "5000000",
                "dueDate", "2026-10-20")));
        expense(user, bank, "2000000", LocalDate.of(2026, 10, 10), null, "قسط گوشی");

        // start 52M; today: rent of 3 Mehr and installment 1 are overdue (-19M)
        mvc.perform(getAs(user, "/api/v1/forecast?days=30"))
                .andExpect(jsonPath("$.from").value("2026-10-06"))
                .andExpect(jsonPath("$.to").value("2026-11-05"))
                .andExpect(jsonPath("$.startBalance").value("52000000"))
                .andExpect(jsonPath("$.events.length()").value(8))
                .andExpect(jsonPath("$.events[0].overdue").value(true))
                .andExpect(jsonPath("$.events[2].source").value("RECORDED"))
                .andExpect(jsonPath("$.events[2].amount").value("-2000000"))
                .andExpect(jsonPath("$.events[3].title").value("قسط ۲ وام قرض‌الحسنه"))
                .andExpect(jsonPath("$.events[5].amount").value("5000000"))
                .andExpect(jsonPath("$.events[6].date").value("2026-10-23"))
                .andExpect(jsonPath("$.events[6].amount").value("60000000"))
                .andExpect(jsonPath("$.points[0].balance").value("33000000"))
                .andExpect(jsonPath("$.minBalance").value("10000000"))
                .andExpect(jsonPath("$.minDate").value("2026-10-15"))
                .andExpect(jsonPath("$.inflow").value("65000000"))
                .andExpect(jsonPath("$.outflow").value("60000000"))
                .andExpect(jsonPath("$.endBalance").value("57000000"));
        mvc.perform(getAs(user, "/api/v1/forecast?days=0")).andExpect(status().isBadRequest());
    }
}
