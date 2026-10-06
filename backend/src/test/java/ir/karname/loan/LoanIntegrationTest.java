package ir.karname.loan;

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
class LoanIntegrationTest extends FinanceTestSupport {

    private static Map<String, Object> loan(String principal, String rate, int months, String firstDue) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "وام خرید خودرو");
        body.put("bank", "MELLAT");
        body.put("principal", principal);
        body.put("annualRate", rate);
        body.put("termMonths", months);
        body.put("firstDueDate", firstDue);
        return body;
    }

    private long create(TestUser user, Map<String, Object> body) throws Exception {
        String json = mvc.perform(postAs(user, "/api/v1/loans", body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return readJson(json).get("id").asLong();
    }

    private Map<String, Object> newLoan(Account bank) {
        Map<String, Object> body = loan("100000000", "18", 36, "2026-10-22");
        body.put("start", "NEW");
        body.put("depositAccountId", bank.getId());
        body.put("receivedOn", "2026-09-25");
        body.put("paymentAccountId", bank.getId());
        return body;
    }

    @Test
    void newLoanIsDisbursedAndScheduled() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        mvc.perform(postAs(user, "/api/v1/loans", newLoan(bank)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("وام خرید خودرو"))
                .andExpect(jsonPath("$.installments.length()").value(36))
                .andExpect(jsonPath("$.installments[0].amount").value("3615240"))
                .andExpect(jsonPath("$.installments[0].interest").value("1500000"))
                .andExpect(jsonPath("$.installments[0].dueDate").value("2026-10-22"))
                .andExpect(jsonPath("$.installments[0].status").value("UPCOMING"))
                .andExpect(jsonPath("$.installments[1].dueDate").value("2026-11-21"))
                .andExpect(jsonPath("$.totalInterest").value("30148620"))
                .andExpect(jsonPath("$.outstanding").value("100000000"))
                .andExpect(jsonPath("$.next.number").value(1));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("110000000"));
        mvc.perform(getAs(user, "/api/v1/loans")).andExpect(jsonPath("$[0].installments").doesNotExist());
    }

    @Test
    void payingSplitsPrincipalAndInterestAndCanBeUndone() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        long id = create(user, newLoan(bank));

        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/1/payment", Map.of(), id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.installments[0].paidOn").value("2026-10-06"))
                .andExpect(jsonPath("$.installments[0].paidAmount").value("3615240"))
                .andExpect(jsonPath("$.paidCount").value(1))
                .andExpect(jsonPath("$.outstanding").value("97884760"))
                .andExpect(jsonPath("$.next.number").value(2));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("106384760"));
        mvc.perform(getAs(user, "/api/v1/transactions?categoryId={c}", category(user, "سود و کارمزد وام")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].amount").value("1500000"))
                .andExpect(jsonPath("$.items[0].source").value("LOAN"));
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/1/payment", Map.of(), id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("loan.alreadyPaid"));

        mvc.perform(deleteAs(user, "/api/v1/loans/{id}/installments/1/payment", id))
                .andExpect(jsonPath("$.installments[0].status").value("UPCOMING"))
                .andExpect(jsonPath("$.outstanding").value("100000000"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("110000000"));
        mvc.perform(deleteAs(user, "/api/v1/loans/{id}/installments/1/payment", id)).andExpect(jsonPath("$.code").value("loan.notPaid"));

        // a late payment with a penalty: the penalty is added to the interest expense
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/2/payment", Map.of("penalty", "50000", "date", "2026-10-05"), id))
                .andExpect(jsonPath("$.installments[1].paidAmount").value("3665240"))
                .andExpect(jsonPath("$.installments[1].paidOn").value("2026-10-05"));
    }

    @Test
    void existingLoanStartsFromItsOutstandingPrincipal() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "50000000");
        Map<String, Object> body = loan("100000000", "18", 36, "2025-10-23");
        body.put("start", "EXISTING");
        body.put("paidBefore", 12);
        long id = create(user, body);

        mvc.perform(getAs(user, "/api/v1/loans/{id}", id))
                .andExpect(jsonPath("$.outstanding").value("72414708"))
                .andExpect(jsonPath("$.installments[11].status").value("PAID_BEFORE"))
                .andExpect(jsonPath("$.installments[11].balanceAfter").value("72414708"))
                .andExpect(jsonPath("$.paidCount").value(12))
                .andExpect(jsonPath("$.next.number").value(13));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("50000000"));
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/5/payment", Map.of("accountId", bank.getId()), id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("loan.paidBefore"));
    }

    @Test
    void flagsOverdueAndSoonDueInstallments() throws Exception {
        TestUser user = createUser("sina");
        Map<String, Object> body = loan("12000000", "0", 12, "2026-09-12"); // 21 Shahrivar
        body.put("start", "EXISTING");
        long id = create(user, body);
        mvc.perform(getAs(user, "/api/v1/loans/{id}", id))
                .andExpect(jsonPath("$.installments[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.installments[1].dueDate").value("2026-10-13"))
                .andExpect(jsonPath("$.installments[1].status").value("DUE_SOON"))
                .andExpect(jsonPath("$.installments[2].status").value("UPCOMING"))
                .andExpect(jsonPath("$.overdueCount").value(1))
                .andExpect(jsonPath("$.overdueAmount").value("1000000"))
                .andExpect(jsonPath("$.totalInterest").value("0"));
    }

    @Test
    void previewsAndValidatesTerms() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        Account dollars = account(user, "دلار", AccountType.CURRENCY, "USD", "10");
        mvc.perform(getAs(user, "/api/v1/loans/preview?principal=100000000&annualRate=18&termMonths=36&firstDueDate=2026-10-22"))
                .andExpect(jsonPath("$.firstInstallment").value("3615240"))
                .andExpect(jsonPath("$.totalInterest").value("30148620"));

        Map<String, Object> badRate = newLoan(bank);
        badRate.put("annualRate", "150");
        mvc.perform(postAs(user, "/api/v1/loans", badRate)).andExpect(jsonPath("$.code").value("loan.invalidRate"));
        Map<String, Object> smallInstallment = newLoan(bank);
        smallInstallment.put("installmentAmount", "1000000");
        mvc.perform(postAs(user, "/api/v1/loans", smallInstallment)).andExpect(jsonPath("$.code").value("loan.installmentTooSmall"));
        Map<String, Object> intoDollars = newLoan(bank);
        intoDollars.put("depositAccountId", dollars.getId());
        mvc.perform(postAs(user, "/api/v1/loans", intoDollars)).andExpect(jsonPath("$.code").value("loan.depositAccountToman"));
        Map<String, Object> noDeposit = newLoan(bank);
        noDeposit.remove("depositAccountId");
        mvc.perform(postAs(user, "/api/v1/loans", noDeposit)).andExpect(jsonPath("$.code").value("loan.depositAccountRequired"));
        Map<String, Object> fractional = newLoan(bank);
        fractional.put("principal", "100.5");
        mvc.perform(postAs(user, "/api/v1/loans", fractional)).andExpect(jsonPath("$.code").value("loan.invalidPrincipal"));
    }

    @Test
    void deletingWithTheAccountRemovesEverything() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        String json = mvc.perform(postAs(user, "/api/v1/loans", newLoan(bank))).andReturn().getResponse().getContentAsString();
        long id = readJson(json).get("id").asLong();
        long accountId = readJson(json).get("accountId").asLong();
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/1/payment", Map.of("penalty", "50000"), id)).andExpect(status().isOk());
        mvc.perform(deleteAs(user, "/api/v1/loans/{id}?withAccount=true", id)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/loans/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", accountId)).andExpect(status().isNotFound());
        // the disbursement, the payment and its interest expense all left with the loan
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("10000000"));
        mvc.perform(getAs(user, "/api/v1/transactions?source=LOAN")).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void deletingOnlyTheTermsKeepsTheLedger() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        String json = mvc.perform(postAs(user, "/api/v1/loans", newLoan(bank))).andReturn().getResponse().getContentAsString();
        long id = readJson(json).get("id").asLong();
        long accountId = readJson(json).get("accountId").asLong();
        mvc.perform(postAs(user, "/api/v1/loans/{id}/installments/1/payment", Map.of(), id)).andExpect(status().isOk());
        mvc.perform(deleteAs(user, "/api/v1/loans/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/loans/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", accountId)).andExpect(jsonPath("$.balance").value("-97884760"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("106384760"));
    }
}
