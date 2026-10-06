package ir.karname.transaction;

import ir.karname.account.Account;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

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
}
