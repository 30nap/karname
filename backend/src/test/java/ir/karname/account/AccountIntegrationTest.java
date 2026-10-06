package ir.karname.account;

import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountIntegrationTest extends FinanceTestSupport {

    @Test
    void createsAccountWithOpeningBalance() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of(
                        "name", "بانک ملت", "type", "BANK", "commodity", "IRT", "bank", "MELLAT",
                        "identifierHints", List.of("۱۲۳۴", "5678"), "openingBalance", "25000000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value("25000000"))
                .andExpect(jsonPath("$.valueToman").value("25000000"))
                .andExpect(jsonPath("$.identifierHints[0]").value("1234"))
                .andExpect(jsonPath("$.liability").value(false));
        mvc.perform(getAs(user, "/api/v1/transactions"))
                .andExpect(jsonPath("$.items[0].type").value("OPENING"))
                .andExpect(jsonPath("$.items[0].description").value("مانده‌ی اول دوره"));
    }

    @Test
    void liabilitiesAreEnteredPositiveAndStoredNegative() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of(
                        "name", "وام مسکن", "type", "LOAN", "commodity", "IRT", "openingBalance", "300000000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value("-300000000"))
                .andExpect(jsonPath("$.liability").value(true));
    }

    @Test
    void valuesForeignHoldingsAtLatestPrice() throws Exception {
        TestUser user = createUser("sina");
        account(user, "دلار نقد", AccountType.CURRENCY, "USD", "500");
        account(user, "سکه", AccountType.GOLD, "COIN_EMAMI", "3");
        mvc.perform(getAs(user, "/api/v1/accounts"))
                .andExpect(jsonPath("$[0].valueToman").doesNotExist())
                .andExpect(jsonPath("$[0].priced").value(false));
        globalPrice("USD", "100000", Instant.parse("2026-10-06T06:00:00Z"));
        globalPrice("COIN_EMAMI", "95000000", Instant.parse("2026-10-06T06:00:00Z"));
        mvc.perform(getAs(user, "/api/v1/accounts"))
                .andExpect(jsonPath("$[0].valueToman").value("50000000"))
                .andExpect(jsonPath("$[1].valueToman").value("285000000"));
    }

    @Test
    void validatesInput() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of("name", "", "type", "BANK", "commodity", "IRT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.invalidName"));
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of("name", "x", "type", "BANK", "commodity", "IRT", "identifierHints", List.of("12ab"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.invalidHints"));
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of("name", "x", "type", "BANK", "commodity", "XXX")))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAccountWithTransactionsNeedsConfirmation() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000000");
        mvc.perform(deleteAs(user, "/api/v1/accounts/{id}", bank.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("account.hasTransactions"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("۱")));
        mvc.perform(deleteAs(user, "/api/v1/accounts/{id}?force=true", bank.getId())).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/transactions")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void archivedAccountsAreHiddenAndRejectNewTransactions() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000");
        mvc.perform(postAs(user, "/api/v1/accounts/{id}/archive", Map.of("archived", true), bank.getId()))
                .andExpect(jsonPath("$.archived").value(true));
        mvc.perform(getAs(user, "/api/v1/accounts")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(getAs(user, "/api/v1/accounts?includeArchived=true")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(postAs(user, "/api/v1/transactions", Map.of("type", "EXPENSE", "date", "2026-10-06", "accountId", bank.getId(), "amount", "10")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("account.archived"));
    }

    @Test
    void cannotTurnAssetIntoLiabilityAfterTransactions() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000");
        mvc.perform(putAs(user, "/api/v1/accounts/{id}", Map.of("name", "ملت", "type", "LOAN"), bank.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("account.typeChangeNotAllowed"));
        mvc.perform(putAs(user, "/api/v1/accounts/{id}", Map.of("name", "ملت جاری", "type", "CASH"), bank.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ملت جاری"));
    }

    @Test
    void reconcileRecordsTheDifference() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000000");
        mvc.perform(postAs(user, "/api/v1/accounts/{id}/reconcile", Map.of("actualBalance", "940000"), bank.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.amount").value("-60000"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("940000"));
        mvc.perform(postAs(user, "/api/v1/accounts/{id}/reconcile", Map.of("actualBalance", "940000"), bank.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("transaction.alreadyReconciled"));
    }
}
