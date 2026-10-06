package ir.karname.cheque;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06). */
class ChequeIntegrationTest extends FinanceTestSupport {

    private long create(TestUser user, Map<String, Object> body) throws Exception {
        String json = mvc.perform(postAs(user, "/api/v1/cheques", body)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return readJson(json).get("id").asLong();
    }

    @Test
    void issuedChequeLifecycle() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "50000000");
        Map<String, Object> body = new HashMap<>();
        body.put("direction", "ISSUED");
        body.put("sayadId", "۱۲۳۴۵۶۷۸۹۰۱۲۳۴۵۶");
        body.put("serial", "123456");
        body.put("bank", "MELLAT");
        body.put("accountId", bank.getId());
        body.put("categoryId", category(user, "اجاره"));
        body.put("counterparty", "آقای رضایی");
        body.put("amount", "18000000");
        body.put("issueDate", "2026-10-01");
        body.put("dueDate", "2026-10-25");
        String json = mvc.perform(postAs(user, "/api/v1/cheques", body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sayadId").value("1234567890123456"))
                .andExpect(jsonPath("$.daysToDue").value(19))
                .andExpect(jsonPath("$.overdue").value(false))
                .andReturn().getResponse().getContentAsString();
        long id = readJson(json).get("id").asLong();

        String cleared = mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "CLEARED", "date", "2026-10-25"), id))
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.settledOn").value("2026-10-25"))
                .andReturn().getResponse().getContentAsString();
        long txId = readJson(cleared).get("transactionId").asLong();
        mvc.perform(getAs(user, "/api/v1/transactions/{id}", txId))
                .andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.amount").value("18000000"))
                .andExpect(jsonPath("$.category.name").value("اجاره"))
                .andExpect(jsonPath("$.description").value("چک صادره به آقای رضایی"))
                .andExpect(jsonPath("$.source").value("CHEQUE"));

        mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "PENDING"), id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.transactionId").doesNotExist());
        mvc.perform(getAs(user, "/api/v1/transactions/{id}", txId)).andExpect(status().isNotFound());

        mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "BOUNCED"), id)).andExpect(jsonPath("$.status").value("BOUNCED"));
        mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "CANCELLED"), id)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "CLEARED"), id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cheque.cancelled"));
    }

    @Test
    void receivedChequeSettlesAReceivable() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", null);
        Account receivable = account(user, "طلب از علی", AccountType.RECEIVABLE, "IRT", "5000000");
        Map<String, Object> body = new HashMap<>();
        body.put("direction", "RECEIVED");
        body.put("counterAccountId", receivable.getId());
        body.put("counterparty", "علی");
        body.put("amount", "5000000");
        body.put("dueDate", "2026-10-01");
        String json = mvc.perform(postAs(user, "/api/v1/cheques", body))
                .andExpect(jsonPath("$.overdue").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = readJson(json).get("id").asLong();

        mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "CLEARED"), id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("cheque.accountRequired"));
        mvc.perform(postAs(user, "/api/v1/cheques/{id}/status", Map.of("status", "CLEARED", "accountId", bank.getId()), id))
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.accountId").value(bank.getId()));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", receivable.getId())).andExpect(jsonPath("$.balance").value("0"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("5000000"));

        mvc.perform(deleteAs(user, "/api/v1/cheques/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", receivable.getId())).andExpect(jsonPath("$.balance").value("5000000"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("0"));
    }

    @Test
    void validatesCheques() throws Exception {
        TestUser user = createUser("sina");
        Account dollars = account(user, "دلار", AccountType.CURRENCY, "USD", "10");
        Map<String, Object> body = new HashMap<>();
        body.put("direction", "ISSUED");
        body.put("amount", "1000000");
        body.put("dueDate", "2026-10-25");
        body.put("sayadId", "123");
        mvc.perform(postAs(user, "/api/v1/cheques", body)).andExpect(jsonPath("$.code").value("cheque.invalidSayad"));
        body.remove("sayadId");
        body.put("accountId", dollars.getId());
        mvc.perform(postAs(user, "/api/v1/cheques", body)).andExpect(jsonPath("$.code").value("cheque.accountToman"));
        body.remove("accountId");
        body.put("amount", "0");
        mvc.perform(postAs(user, "/api/v1/cheques", body)).andExpect(jsonPath("$.code").value("cheque.invalidAmount"));
        body.put("amount", "1000000");
        body.remove("dueDate");
        mvc.perform(postAs(user, "/api/v1/cheques", body)).andExpect(jsonPath("$.code").value("cheque.dueDateRequired"));
        body.put("dueDate", "2026-10-25");
        body.remove("direction");
        mvc.perform(postAs(user, "/api/v1/cheques", body)).andExpect(jsonPath("$.code").value("cheque.directionRequired"));
        body.put("direction", "ISSUED");
        create(user, body);
        mvc.perform(getAs(user, "/api/v1/cheques")).andExpect(jsonPath("$.length()").value(1));
    }
}
