package ir.karname.recurring;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today starts at 14 Mehr 1405 (2026-10-06); 1 Aban is 2026-10-23, 1 Azar 2026-11-22, 1 Dey 2026-12-22. */
class RecurringIntegrationTest extends FinanceTestSupport {

    @Autowired
    private RecurringService recurring;

    private Map<String, Object> rule(String name, String type, Account account, String amount, String mode) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", type);
        body.put("accountId", account.getId());
        body.put("amount", amount);
        body.put("frequency", "MONTHLY");
        body.put("mode", mode);
        return body;
    }

    private long create(TestUser user, Map<String, Object> body) throws Exception {
        String json = mvc.perform(postAs(user, "/api/v1/recurring", body)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return readJson(json).get("id").asLong();
    }

    @Test
    void autoRulesPostFromTheirCreationAndCatchUpAfterDowntime() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000000");
        Map<String, Object> body = rule("حقوق", "INCOME", bank, "60000000", "AUTO");
        body.put("dayOfMonth", 1);
        body.put("startDate", "2026-03-21");
        body.put("categoryId", category(user, "حقوق و دستمزد"));
        long id = create(user, body);
        mvc.perform(getAs(user, "/api/v1/recurring/{id}", id))
                .andExpect(jsonPath("$.nextDate").value("2026-10-23"))
                .andExpect(jsonPath("$.dueCount").value(0));

        // past months are not back-filled
        assertThat(recurring.runAuto(user.id())).isZero();

        clock.setDate(LocalDate.of(2026, 10, 24));
        assertThat(recurring.runAuto(user.id())).isEqualTo(1);
        assertThat(recurring.runAuto(user.id())).isZero();
        mvc.perform(getAs(user, "/api/v1/transactions?source=RECURRING"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].date").value("2026-10-23"))
                .andExpect(jsonPath("$.items[0].amount").value("60000000"));

        // two months of downtime
        clock.setDate(LocalDate.of(2026, 12, 25));
        assertThat(recurring.runAuto(user.id())).isEqualTo(2);
        mvc.perform(getAs(user, "/api/v1/recurring/{id}", id))
                .andExpect(jsonPath("$.lastPosted").value("2026-12-22"))
                .andExpect(jsonPath("$.nextDate").value("2027-01-21"));
    }

    @Test
    void remindRulesWaitForTheUser() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "100000000");
        Map<String, Object> body = rule("اجاره خانه", "EXPENSE", bank, "18000000", "REMIND");
        body.put("startDate", "2026-09-25"); // 3 Mehr
        body.put("categoryId", category(user, "اجاره"));
        long id = create(user, body);

        mvc.perform(getAs(user, "/api/v1/recurring/pending"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].date").value("2026-09-25"))
                .andExpect(jsonPath("$[0].status").value("DUE"));
        assertThat(recurring.runAuto(user.id())).isZero();

        String json = mvc.perform(postAs(user, "/api/v1/recurring/{id}/occurrences/2026-09-25/post",
                        Map.of("date", "2026-09-26", "amount", "18500000"), id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long txId = readJson(json).get("transactionId").asLong();
        mvc.perform(getAs(user, "/api/v1/transactions/{id}", txId))
                .andExpect(jsonPath("$.date").value("2026-09-26"))
                .andExpect(jsonPath("$.amount").value("18500000"))
                .andExpect(jsonPath("$.source").value("RECURRING"));
        mvc.perform(getAs(user, "/api/v1/recurring/pending")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(postAs(user, "/api/v1/recurring/{id}/occurrences/2026-09-25/post", Map.of(), id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("recurring.alreadyPosted"));
        mvc.perform(postAs(user, "/api/v1/recurring/{id}/occurrences/2026-09-26/post", Map.of(), id))
                .andExpect(jsonPath("$.code").value("recurring.notAnOccurrence"));

        // a week before 3 Aban, the next occurrence shows as upcoming; it can be skipped and restored
        clock.setDate(LocalDate.of(2026, 10, 20));
        mvc.perform(getAs(user, "/api/v1/recurring/pending"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].date").value("2026-10-25"))
                .andExpect(jsonPath("$[0].status").value("UPCOMING"));
        mvc.perform(postAs(user, "/api/v1/recurring/{id}/occurrences/2026-10-25/skip", Map.of(), id)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/recurring/pending")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(deleteAs(user, "/api/v1/recurring/{id}/occurrences/2026-10-25/skip", id)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/recurring/pending")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void weeklyOccurrencesWithStatuses() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "100000000");
        Map<String, Object> body = rule("خرید هفتگی", "EXPENSE", bank, "2500000", "REMIND");
        body.put("frequency", "WEEKLY");
        body.put("dayOfWeek", 6); // Friday
        body.put("startDate", "2026-10-01");
        create(user, body);
        mvc.perform(getAs(user, "/api/v1/recurring/occurrences?from=2026-10-01&to=2026-10-31"))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].date").value("2026-10-02"))
                .andExpect(jsonPath("$[0].status").value("DUE"))
                .andExpect(jsonPath("$[1].date").value("2026-10-09"))
                .andExpect(jsonPath("$[1].status").value("UPCOMING"))
                .andExpect(jsonPath("$[4].date").value("2026-10-30"));
    }

    @Test
    void validatesRules() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000000");
        Account dollars = account(user, "دلار", AccountType.CURRENCY, "USD", "10");

        Map<String, Object> exchange = rule("خرید دلار", "TRANSFER", bank, "1000000", "REMIND");
        exchange.put("toAccountId", dollars.getId());
        exchange.put("startDate", "2026-10-01");
        mvc.perform(postAs(user, "/api/v1/recurring", exchange)).andExpect(jsonPath("$.code").value("transaction.toAmountRequired"));

        Map<String, Object> wrongCategory = rule("x", "EXPENSE", bank, "1000", "REMIND");
        wrongCategory.put("startDate", "2026-10-01");
        wrongCategory.put("categoryId", category(user, "حقوق و دستمزد"));
        mvc.perform(postAs(user, "/api/v1/recurring", wrongCategory)).andExpect(jsonPath("$.code").value("category.kindMismatch"));

        Map<String, Object> badDates = rule("x", "EXPENSE", bank, "1000", "REMIND");
        badDates.put("startDate", "2026-10-01");
        badDates.put("endDate", "2026-09-01");
        mvc.perform(postAs(user, "/api/v1/recurring", badDates)).andExpect(jsonPath("$.code").value("recurring.invalidDates"));

        Map<String, Object> badInterval = rule("x", "EXPENSE", bank, "1000", "REMIND");
        badInterval.put("startDate", "2026-10-01");
        badInterval.put("interval", 13);
        mvc.perform(postAs(user, "/api/v1/recurring", badInterval)).andExpect(jsonPath("$.code").value("recurring.invalidInterval"));
    }
}
