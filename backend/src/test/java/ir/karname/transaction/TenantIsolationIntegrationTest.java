package ir.karname.transaction;

import ir.karname.account.Account;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One user must never see, change or reference another user's data. */
class TenantIsolationIntegrationTest extends FinanceTestSupport {

    @Test
    void usersCannotReachEachOthersData() throws Exception {
        TestUser alice = createUser("alice");
        TestUser bob = createUser("bob");
        Account aliceBank = bank(alice, "ملت", "1000000");
        long aliceTx = expense(alice, aliceBank, "1000", TODAY, null, "x").getId();
        long aliceCategory = category(alice, "سوپرمارکت");
        Account bobBank = bank(bob, "سامان", "500");

        mvc.perform(getAs(bob, "/api/v1/accounts/{id}", aliceBank.getId())).andExpect(status().isNotFound());
        mvc.perform(putAs(bob, "/api/v1/accounts/{id}", Map.of("name", "hack", "type", "BANK"), aliceBank.getId())).andExpect(status().isNotFound());
        mvc.perform(deleteAs(bob, "/api/v1/accounts/{id}?force=true", aliceBank.getId())).andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/accounts/{id}/reconcile", Map.of("actualBalance", "1"), aliceBank.getId())).andExpect(status().isNotFound());
        mvc.perform(getAs(bob, "/api/v1/accounts/{id}/cost-basis", aliceBank.getId())).andExpect(status().isNotFound());

        mvc.perform(getAs(bob, "/api/v1/transactions/{id}", aliceTx)).andExpect(status().isNotFound());
        mvc.perform(deleteAs(bob, "/api/v1/transactions/{id}", aliceTx)).andExpect(status().isNotFound());
        mvc.perform(putAs(bob, "/api/v1/transactions/{id}", Map.of("type", "EXPENSE", "accountId", bobBank.getId(), "amount", "1", "date", "2026-10-06"), aliceTx))
                .andExpect(status().isNotFound());
        mvc.perform(getAs(bob, "/api/v1/transactions")).andExpect(jsonPath("$.total").value(1)); // only Bob's opening

        // cannot post into Alice's account or with her category
        mvc.perform(postAs(bob, "/api/v1/transactions", Map.of("type", "EXPENSE", "accountId", aliceBank.getId(), "amount", "1", "date", "2026-10-06")))
                .andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/transactions", Map.of("type", "EXPENSE", "accountId", bobBank.getId(), "amount", "1", "date", "2026-10-06", "categoryId", aliceCategory)))
                .andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/transactions", Map.of("type", "TRANSFER", "accountId", bobBank.getId(), "toAccountId", aliceBank.getId(), "amount", "1", "date", "2026-10-06")))
                .andExpect(status().isNotFound());

        mvc.perform(putAs(bob, "/api/v1/categories/{id}", Map.of("name", "x"), aliceCategory)).andExpect(status().isNotFound());
        mvc.perform(deleteAs(bob, "/api/v1/categories/{id}", aliceCategory)).andExpect(status().isNotFound());

        // Alice's data is untouched
        mvc.perform(getAs(alice, "/api/v1/accounts/{id}", aliceBank.getId())).andExpect(jsonPath("$.balance").value("999000"));
        mvc.perform(getAs(bob, "/api/v1/net-worth")).andExpect(jsonPath("$.totalToman").value("500"));
    }

    @Test
    void planningDataIsIsolated() throws Exception {
        TestUser alice = createUser("alice");
        TestUser bob = createUser("bob");
        Account aliceBank = bank(alice, "ملت", "1000000");
        long aliceFood = category(alice, "خوراک و خواربار");
        mvc.perform(putAs(alice, "/api/v1/budgets/1405-07/{c}", Map.of("amount", "500000"), aliceFood)).andExpect(status().isOk());
        expense(alice, aliceBank, "400000", TODAY, category(alice, "سوپرمارکت"), "خرید");
        String created = mvc.perform(postAs(alice, "/api/v1/goals", Map.of("name", "سفر", "targetAmount", "5000000",
                        "accountIds", List.of(aliceBank.getId()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long goalId = readJson(created).get("id").asLong();

        mvc.perform(putAs(bob, "/api/v1/budgets/1405-07/{c}", Map.of("amount", "1"), aliceFood)).andExpect(status().isNotFound());
        mvc.perform(deleteAs(bob, "/api/v1/budgets/1405-07/{c}", aliceFood)).andExpect(status().isNotFound());
        mvc.perform(getAs(bob, "/api/v1/budgets"))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalExpense").value("0"));
        mvc.perform(getAs(bob, "/api/v1/budgets/suggestions")).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(getAs(bob, "/api/v1/goals/{id}", goalId)).andExpect(status().isNotFound());
        mvc.perform(putAs(bob, "/api/v1/goals/{id}", Map.of("name", "x", "targetAmount", "1"), goalId)).andExpect(status().isNotFound());
        mvc.perform(deleteAs(bob, "/api/v1/goals/{id}", goalId)).andExpect(status().isNotFound());
        mvc.perform(getAs(bob, "/api/v1/goals")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(postAs(bob, "/api/v1/goals", Map.of("name", "x", "targetAmount", "1", "accountIds", List.of(aliceBank.getId()))))
                .andExpect(status().isNotFound());

        mvc.perform(getAs(bob, "/api/v1/reports/monthly?months=1")).andExpect(jsonPath("$[0].expenseToman").value("0"));
        mvc.perform(getAs(bob, "/api/v1/reports/categories")).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(getAs(bob, "/api/v1/reports/top")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(getAs(bob, "/api/v1/reports/anomalies")).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(getAs(alice, "/api/v1/budgets")).andExpect(jsonPath("$.items[0].spent").value("400000"));
        mvc.perform(getAs(alice, "/api/v1/goals/{id}", goalId)).andExpect(jsonPath("$.currentAmount").value("600000"));
    }

    @Test
    void obligationsAreIsolated() throws Exception {
        TestUser alice = createUser("alice");
        TestUser bob = createUser("bob");
        Account aliceBank = bank(alice, "ملت", "1000000");
        Account bobBank = bank(bob, "سامان", "1000000");
        long loan = readJson(mvc.perform(postAs(alice, "/api/v1/loans", Map.of("name", "وام", "principal", "1200000", "annualRate", "0",
                        "termMonths", 12, "firstDueDate", "2026-09-12", "start", "EXISTING")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long rule = readJson(mvc.perform(postAs(alice, "/api/v1/recurring", Map.of("name", "اجاره", "type", "EXPENSE", "accountId",
                        aliceBank.getId(), "amount", "1000", "frequency", "MONTHLY", "startDate", "2026-09-25", "mode", "REMIND")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long cheque = readJson(mvc.perform(postAs(alice, "/api/v1/cheques", Map.of("direction", "ISSUED", "accountId", aliceBank.getId(),
                        "amount", "1000", "dueDate", "2026-10-08")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(getAs(bob, "/api/v1/loans/{id}", loan)).andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/loans/{id}/installments/1/payment", Map.of("accountId", bobBank.getId()), loan)).andExpect(status().isNotFound());
        mvc.perform(deleteAs(bob, "/api/v1/loans/{id}?withAccount=true", loan)).andExpect(status().isNotFound());
        mvc.perform(getAs(bob, "/api/v1/loans")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(postAs(bob, "/api/v1/loans", Map.of("name", "x", "principal", "1000", "annualRate", "0", "termMonths", 2,
                "firstDueDate", "2026-11-01", "start", "NEW", "depositAccountId", aliceBank.getId()))).andExpect(status().isNotFound());

        mvc.perform(getAs(bob, "/api/v1/recurring/{id}", rule)).andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/recurring/{id}/occurrences/2026-09-25/post", Map.of(), rule)).andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/recurring", Map.of("name", "x", "type", "EXPENSE", "accountId", aliceBank.getId(), "amount", "1",
                "frequency", "MONTHLY", "startDate", "2026-09-25"))).andExpect(status().isNotFound());
        mvc.perform(getAs(bob, "/api/v1/recurring/pending")).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(getAs(bob, "/api/v1/cheques/{id}", cheque)).andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/cheques/{id}/status", Map.of("status", "CLEARED", "accountId", bobBank.getId()), cheque))
                .andExpect(status().isNotFound());
        mvc.perform(postAs(bob, "/api/v1/cheques", Map.of("direction", "ISSUED", "accountId", aliceBank.getId(), "amount", "1",
                "dueDate", "2026-10-08"))).andExpect(status().isNotFound());

        mvc.perform(getAs(bob, "/api/v1/forecast?days=30"))
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.startBalance").value("1000000"));
        mvc.perform(getAs(bob, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(0));
        mvc.perform(getAs(alice, "/api/v1/notifications/count")).andExpect(jsonPath("$.unread").value(3));
        long aliceNotification = readJson(mvc.perform(getAs(alice, "/api/v1/notifications")).andReturn().getResponse().getContentAsString())
                .get(0).get("id").asLong();
        mvc.perform(postAs(bob, "/api/v1/notifications/{id}/read", Map.of(), aliceNotification)).andExpect(status().isNotFound());
    }
}
