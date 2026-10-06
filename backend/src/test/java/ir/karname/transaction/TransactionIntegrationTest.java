package ir.karname.transaction;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransactionIntegrationTest extends FinanceTestSupport {

    private static Map<String, Object> tx(String type, long accountId, String amount, String date) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("accountId", accountId);
        body.put("amount", amount);
        body.put("date", date);
        return body;
    }

    @Test
    void expensesAndIncomeChangeBalances() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        long food = category(user, "رستوران");
        Map<String, Object> lunch = tx("EXPENSE", bank.getId(), "180000", "2026-10-05");
        lunch.put("categoryId", food);
        lunch.put("description", "ناهار با بچه‌ها");
        lunch.put("tags", List.of("کار", "#دوستان"));
        mvc.perform(postAs(user, "/api/v1/transactions", lunch))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category.name").value("رستوران"))
                .andExpect(jsonPath("$.category.parentName").value("رستوران و کافه"))
                .andExpect(jsonPath("$.category.icon").value("utensils"))
                .andExpect(jsonPath("$.tags[1]").value("دوستان"))
                .andExpect(jsonPath("$.account.name").value("ملت"));
        Map<String, Object> salary = tx("INCOME", bank.getId(), "40000000", "2026-10-01");
        salary.put("categoryId", category(user, "حقوق و دستمزد"));
        mvc.perform(postAs(user, "/api/v1/transactions", salary)).andExpect(status().isCreated());
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", bank.getId())).andExpect(jsonPath("$.balance").value("49820000"));
    }

    @Test
    void enforcesLedgerRules() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000");
        Map<String, Object> wrongKind = tx("EXPENSE", bank.getId(), "10", "2026-10-05");
        wrongKind.put("categoryId", category(user, "حقوق و دستمزد"));
        mvc.perform(postAs(user, "/api/v1/transactions", wrongKind))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("category.kindMismatch"));
        mvc.perform(postAs(user, "/api/v1/transactions", tx("EXPENSE", bank.getId(), "0", "2026-10-05")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("transaction.invalidAmount"));
        mvc.perform(postAs(user, "/api/v1/transactions", tx("OPENING", bank.getId(), "5", "2026-10-05")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("transaction.invalidType"));
        mvc.perform(postAs(user, "/api/v1/transactions", tx("EXPENSE", bank.getId(), "5", "2028-01-01")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("transaction.dateOutOfRange"));
        Map<String, Object> toSelf = tx("TRANSFER", bank.getId(), "5", "2026-10-05");
        toSelf.put("toAccountId", bank.getId());
        mvc.perform(postAs(user, "/api/v1/transactions", toSelf))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("transaction.sameAccount"));
    }

    @Test
    void transfersMoveMoneyAndChargeFees() throws Exception {
        TestUser user = createUser("sina");
        Account mellat = bank(user, "ملت", "5000000");
        Account saman = bank(user, "سامان", null);
        Map<String, Object> body = tx("TRANSFER", mellat.getId(), "1000000", "2026-10-06");
        body.put("toAccountId", saman.getId());
        body.put("fee", "500");
        mvc.perform(postAs(user, "/api/v1/transactions", body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toAmount").value("1000000"))
                .andExpect(jsonPath("$.fee").value("500"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", mellat.getId())).andExpect(jsonPath("$.balance").value("3999500"));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}", saman.getId())).andExpect(jsonPath("$.balance").value("1000000"));
        // the fee counts as an expense in totals
        mvc.perform(getAs(user, "/api/v1/transactions?from=2026-10-06&to=2026-10-06"))
                .andExpect(jsonPath("$.expenseToman").value("500"))
                .andExpect(jsonPath("$.incomeToman").value("0"));

        body.put("toAmount", "999");
        mvc.perform(postAs(user, "/api/v1/transactions", body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("transaction.transferAmountMismatch"));
    }

    @Test
    void buyingGoldRecordsImpliedPrice() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "200000000");
        Account coins = account(user, "سکه‌ها", AccountType.GOLD, "COIN_EMAMI", null);
        Map<String, Object> buy = tx("TRANSFER", bank.getId(), "95000000", "2026-10-06");
        buy.put("toAccountId", coins.getId());
        mvc.perform(postAs(user, "/api/v1/transactions", buy))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("transaction.toAmountRequired"));
        buy.put("toAmount", "1");
        long id = readJson(mvc.perform(postAs(user, "/api/v1/transactions", buy))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(getAs(user, "/api/v1/accounts/{id}", coins.getId()))
                .andExpect(jsonPath("$.balance").value("1"))
                .andExpect(jsonPath("$.valueToman").value("95000000"));
        mvc.perform(getAs(user, "/api/v1/prices?commodity=COIN_EMAMI"))
                .andExpect(jsonPath("$[0].source").value("TRANSACTION"))
                .andExpect(jsonPath("$[0].personal").value(true));

        // editing the purchase updates the implied price; deleting removes it
        buy.put("amount", "96000000");
        mvc.perform(putAs(user, "/api/v1/transactions/{id}", buy, id)).andExpect(status().isOk());
        mvc.perform(getAs(user, "/api/v1/prices?commodity=COIN_EMAMI"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].priceToman").value("96000000"));
        mvc.perform(deleteAs(user, "/api/v1/transactions/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/prices?commodity=COIN_EMAMI")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void searchFiltersAndTotals() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "50000000");
        Account cash = account(user, "کیف پول", AccountType.CASH, "IRT", "2000000");
        long taxi = category(user, "تاکسی اینترنتی");
        long fuel = category(user, "بنزین");
        expense(user, bank, "95000", TODAY.minusDays(1), taxi, "اسنپ به دفتر");
        expense(user, cash, "300000", TODAY.minusDays(2), fuel, "بنزین");
        // Arabic Yeh/Kaf as some keyboards type it
        expense(user, bank, "120000", TODAY.minusDays(40), taxi, "تپسي كرج");
        income(user, bank, "40000000", TODAY.minusDays(3), category(user, "حقوق و دستمزد"), "حقوق مهر");

        // date range = this Jalali month (Mehr 1405 started 2026-09-23)
        mvc.perform(getAs(user, "/api/v1/transactions?from=2026-09-23&to=2026-10-22&type=INCOME&type=EXPENSE"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].description").value("اسنپ به دفتر"))
                .andExpect(jsonPath("$.incomeToman").value("40000000"))
                .andExpect(jsonPath("$.expenseToman").value("395000"));
        // parent category includes its children
        mvc.perform(getAs(user, "/api/v1/transactions?categoryId={id}", category(user, "حمل‌ونقل")))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(getAs(user, "/api/v1/transactions?accountId={id}&type=EXPENSE", cash.getId()))
                .andExpect(jsonPath("$.total").value(1));
        // Persian text search matches the Arabic-letter description
        mvc.perform(getAs(user, "/api/v1/transactions?q=تپسی کرج")).andExpect(jsonPath("$.total").value(1));
        // numeric search matches amounts, with Persian digits
        mvc.perform(getAs(user, "/api/v1/transactions?q=۹۵۰۰۰")).andExpect(jsonPath("$.total").value(1));
        mvc.perform(getAs(user, "/api/v1/transactions?page=0&size=2"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(6));
    }

    @Test
    void learnsMerchantCategories() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000000");
        long taxi = category(user, "تاکسی اینترنتی");
        expense(user, bank, "95000", TODAY, taxi, "اسنپ");
        mvc.perform(getAs(user, "/api/v1/categories/suggest?description=اسنپ ۲۳۴"))
                .andExpect(jsonPath("$.categoryId").value(taxi));
        mvc.perform(getAs(user, "/api/v1/categories/suggest?description=سفر با اسنپ به فرودگاه"))
                .andExpect(jsonPath("$.categoryId").value(taxi));
        mvc.perform(getAs(user, "/api/v1/categories/suggest?description=نانوایی"))
                .andExpect(jsonPath("$.categoryId").doesNotExist());
    }

    @Test
    void expenseInForeignCurrencyIsValuedAtThatDaysPrice() throws Exception {
        TestUser user = createUser("sina");
        Account usd = account(user, "دلار", AccountType.CURRENCY, "USD", "1000");
        globalPrice("USD", "90000", Instant.parse("2026-09-01T06:00:00Z"));
        globalPrice("USD", "100000", Instant.parse("2026-10-05T06:00:00Z"));
        expense(user, usd, "10", TODAY.minusDays(20), null, "خرید اشتراک");
        expense(user, usd, "5", TODAY, null, "VPN");
        mvc.perform(getAs(user, "/api/v1/transactions?type=EXPENSE"))
                .andExpect(jsonPath("$.expenseToman").value("1400000")); // 10×90,000 + 5×100,000
    }

    @Test
    void openingBalanceCanBeEdited() throws Exception {
        TestUser user = createUser("sina");
        Account loan = account(user, "وام", AccountType.LOAN, "IRT", "100000000");
        long openingId = jdbc.sql("SELECT id FROM transactions WHERE account_id = ?").param(loan.getId()).query(Long.class).single();
        mvc.perform(putAs(user, "/api/v1/transactions/{id}", Map.of("type", "OPENING", "amount", "80000000", "date", "2026-04-01"), openingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("-80000000"));
        assertThat(accountBalance(user, loan)).isEqualByComparingTo("-80000000");
    }

    private BigDecimal accountBalance(TestUser user, Account account) throws Exception {
        return new BigDecimal(readJson(mvc.perform(getAs(user, "/api/v1/accounts/{id}", account.getId()))
                .andReturn().getResponse().getContentAsString()).get("balance").asString());
    }
}
