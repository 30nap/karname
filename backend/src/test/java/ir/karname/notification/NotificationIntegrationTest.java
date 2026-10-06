package ir.karname.notification;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06). */
class NotificationIntegrationTest extends FinanceTestSupport {

    @Autowired
    private NotificationService notifications;

    private long setUp(TestUser user) throws Exception {
        Account bank = bank(user, "ملت", "50000000");
        account(user, "دلار", AccountType.CURRENCY, "USD", "100");
        globalPrice("USD", "100000", Instant.parse("2026-09-01T06:00:00Z")); // a month old: stale
        // installment 1 (17 Shahrivar) is overdue, installment 2 (17 Mehr = 9 October) is three days away
        String loan = mvc.perform(postAs(user, "/api/v1/loans", new HashMap<>(Map.of("name", "وام مسکن", "principal", "12000000", "annualRate", "0",
                        "termMonths", 12, "firstDueDate", "2026-09-08", "start", "EXISTING", "paymentAccountId", bank.getId()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(postAs(user, "/api/v1/cheques", new HashMap<>(Map.of("direction", "ISSUED", "accountId", bank.getId(), "counterparty", "فروشگاه",
                "amount", "20000000", "dueDate", "2026-10-08")))).andExpect(status().isCreated());
        mvc.perform(putAs(user, "/api/v1/budgets/1405-07/{c}", Map.of("amount", "1000000"), category(user, "خوراک و خواربار")))
                .andExpect(status().isOk());
        expense(user, bank, "1500000", LocalDate.of(2026, 10, 2), category(user, "سوپرمارکت"), "خرید");
        mvc.perform(postAs(user, "/api/v1/goals", new HashMap<>(Map.of("name", "لپ‌تاپ", "targetAmount", "100", "manualAmount", "100"))))
                .andExpect(status().isCreated());
        mvc.perform(postAs(user, "/api/v1/recurring", new HashMap<>(Map.of("name", "اجاره", "type", "EXPENSE", "accountId", bank.getId(),
                "amount", "18000000", "frequency", "MONTHLY", "startDate", "2026-09-25", "mode", "REMIND")))).andExpect(status().isCreated());
        return readJson(loan).get("id").asLong();
    }

    private List<String> types(TestUser user, boolean unread) throws Exception {
        String json = mvc.perform(getAs(user, "/api/v1/notifications?unread=" + unread)).andReturn().getResponse().getContentAsString();
        return StreamSupport.stream(readJson(json).spliterator(), false).map(n -> n.get("type").asString()).sorted().toList();
    }

    @Test
    void rulesNotifyOnceAndResolve() throws Exception {
        TestUser user = createUser("sina");
        long loanId = setUp(user);

        assertThat(notifications.refresh(user.id())).isEqualTo(7);
        assertThat(notifications.refresh(user.id())).isZero();
        assertThat(types(user, true)).containsExactly("BUDGET_OVER", "CHEQUE_DUE", "GOAL_REACHED", "LOAN_DUE", "LOAN_OVERDUE", "PRICE_STALE",
                "RECURRING_DUE");
        mvc.perform(getAs(user, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(7));

        String json = mvc.perform(getAs(user, "/api/v1/notifications")).andReturn().getResponse().getContentAsString();
        JsonNode overdue = StreamSupport.stream(readJson(json).spliterator(), false)
                .filter(n -> n.get("type").asString().equals("LOAN_OVERDUE")).findFirst().orElseThrow();
        assertThat(overdue.get("title").asString()).isEqualTo("قسط معوق: وام مسکن");
        assertThat(overdue.get("body").asString()).isEqualTo("قسط ۱ به مبلغ ۱٬۰۰۰٬۰۰۰ تومان از ۱۷ شهریور ۱۴۰۵ پرداخت نشده است.");
        assertThat(overdue.get("severity").asString()).isEqualTo("CRITICAL");
        assertThat(overdue.get("link").asString()).isEqualTo("/loans/" + loanId);

        // paying the installment resolves its notification
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/1/payment", Map.of(), loanId)).andExpect(status().isOk());
        notifications.refresh(user.id());
        assertThat(types(user, true)).doesNotContain("LOAN_OVERDUE").hasSize(6);

        long budgetId = StreamSupport.stream(readJson(json).spliterator(), false)
                .filter(n -> n.get("type").asString().equals("BUDGET_OVER")).findFirst().orElseThrow().get("id").asLong();
        mvc.perform(postAs(user, "/api/v1/notifications/{id}/read", Map.of(), budgetId)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(5));
        mvc.perform(postAs(user, "/api/v1/notifications/read-all", Map.of())).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(0));
        assertThat(types(user, false)).hasSize(7);
    }

    @Test
    void writesLiftTheRefreshThrottle() throws Exception {
        TestUser user = createUser("sina");
        // a page load before there is anything to report starts the two-minute throttle
        mvc.perform(getAs(user, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(0));
        mvc.perform(postAs(user, "/api/v1/cheques", new HashMap<>(Map.of("direction", "ISSUED", "counterparty", "فروشگاه",
                "amount", "20000000", "dueDate", "2026-10-08")))).andExpect(status().isCreated());
        mvc.perform(getAs(user, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(1));
        // reading notifications is not a data change
        mvc.perform(postAs(user, "/api/v1/notifications/read-all", Map.of())).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void amountsFollowTheDisplayUnit() throws Exception {
        TestUser user = createUser("sina");
        setUp(user);
        mvc.perform(putAs(user, "/api/v1/settings", Map.of("displayUnit", "RIAL", "digitStyle", "PERSIAN", "theme", "SYSTEM",
                "wealthUnits", List.of("USD"), "aiEnabled", true, "aiShareDescriptions", true))).andExpect(status().isOk());
        notifications.refresh(user.id());
        String json = mvc.perform(getAs(user, "/api/v1/notifications")).andReturn().getResponse().getContentAsString();
        JsonNode cheque = StreamSupport.stream(readJson(json).spliterator(), false)
                .filter(n -> n.get("type").asString().equals("CHEQUE_DUE")).findFirst().orElseThrow();
        assertThat(cheque.get("body").asString()).isEqualTo("چک ۲۰۰٬۰۰۰٬۰۰۰ ریال به فروشگاه، سررسید ۱۶ مهر ۱۴۰۵. موجودی حساب را بررسی کنید.");
    }
}
