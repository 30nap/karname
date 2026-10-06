package ir.karname.ledger;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NetWorthIntegrationTest extends FinanceTestSupport {

    @Test
    void netWorthCombinesAllHoldingsAndDebts() throws Exception {
        TestUser user = createUser("sina");
        bank(user, "ملت", "60000000");
        account(user, "دلار", AccountType.CURRENCY, "USD", "400");
        account(user, "طلا", AccountType.GOLD, "GOLD18", "10");
        account(user, "وام", AccountType.LOAN, "IRT", "50000000");
        Account btc = account(user, "بیت‌کوین", AccountType.CRYPTO, "BTC", "0.01");
        globalPrice("USD", "100000", Instant.parse("2026-10-06T06:00:00Z"));
        globalPrice("GOLD18", "9000000", Instant.parse("2026-10-06T06:00:00Z"));

        // assets: 60M + 40M + 90M = 190M (BTC unpriced); liabilities: 50M
        mvc.perform(getAs(user, "/api/v1/net-worth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetsToman").value("190000000"))
                .andExpect(jsonPath("$.liabilitiesToman").value("50000000"))
                .andExpect(jsonPath("$.totalToman").value("140000000"))
                .andExpect(jsonPath("$.unpriced[0].accountId").value(btc.getId()))
                .andExpect(jsonPath("$.alternatives[0].code").value("USD"))
                .andExpect(jsonPath("$.alternatives[0].value").value("1400"))
                .andExpect(jsonPath("$.alternatives[1].code").value("GOLD18"))
                .andExpect(jsonPath("$.alternatives[1].value").value("15.556"))
                .andExpect(jsonPath("$.allocation[0].assetClass").value("TOMAN"))
                .andExpect(jsonPath("$.allocation[0].share").value("0.3158"))
                .andExpect(jsonPath("$.allocation[1].assetClass").value("FIAT"))
                .andExpect(jsonPath("$.allocation[2].assetClass").value("GOLD"));
    }

    @Test
    void excludedAccountsDoNotCount() throws Exception {
        TestUser user = createUser("sina");
        bank(user, "ملت", "1000000");
        mvc.perform(postAs(user, "/api/v1/accounts", java.util.Map.of("name", "صندوق خانواده", "type", "CASH", "commodity", "IRT",
                "openingBalance", "9000000", "includeInNetWorth", false))).andExpect(status().isCreated());
        mvc.perform(getAs(user, "/api/v1/net-worth")).andExpect(jsonPath("$.totalToman").value("1000000"));
    }

    @Test
    void historyTracksMonthEndsInTomanAndDollars() throws Exception {
        TestUser user = createUser("sina");
        // opening balance is dated six months back (Farvardin 1405)
        Account bank = bank(user, "ملت", "100000000");
        globalPrice("USD", "80000", Instant.parse("2026-06-01T06:00:00Z"));
        globalPrice("USD", "100000", Instant.parse("2026-09-10T06:00:00Z"));
        expense(user, bank, "20000000", java.time.LocalDate.of(2026, 9, 25), null, "خرید لپ‌تاپ");

        mvc.perform(getAs(user, "/api/v1/net-worth/history?months=3"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].month").value("1405-05"))
                .andExpect(jsonPath("$[0].totalToman").value("100000000"))
                .andExpect(jsonPath("$[0].alternatives.USD").value("1250"))
                .andExpect(jsonPath("$[1].month").value("1405-06"))
                .andExpect(jsonPath("$[1].alternatives.USD").value("1000"))
                .andExpect(jsonPath("$[2].month").value("1405-07"))
                .andExpect(jsonPath("$[2].date").value("2026-10-06"))
                .andExpect(jsonPath("$[2].totalToman").value("80000000"))
                .andExpect(jsonPath("$[2].alternatives.USD").value("800"));
    }

    @Test
    void costBasisOfGoldHolding() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "500000000");
        Account coins = account(user, "سکه", AccountType.GOLD, "COIN_EMAMI", null);
        transfer(user, bank, coins, "80000000", "1", java.time.LocalDate.of(2026, 8, 1));
        transfer(user, bank, coins, "90000000", "1", java.time.LocalDate.of(2026, 9, 1));
        transfer(user, coins, bank, "1", "100000000", java.time.LocalDate.of(2026, 9, 20));
        globalPrice("COIN_EMAMI", "110000000", Instant.parse("2026-10-06T06:00:00Z"));

        mvc.perform(getAs(user, "/api/v1/accounts/{id}/cost-basis", coins.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value("1"))
                .andExpect(jsonPath("$.averageCostToman").value("85000000"))
                .andExpect(jsonPath("$.costBasisToman").value("85000000"))
                .andExpect(jsonPath("$.realizedToman").value("15000000"))
                .andExpect(jsonPath("$.marketValueToman").value("110000000"))
                .andExpect(jsonPath("$.unrealizedToman").value("25000000"))
                .andExpect(jsonPath("$.costComplete").value(true));
        mvc.perform(getAs(user, "/api/v1/accounts/{id}/cost-basis", bank.getId())).andExpect(status().isNotFound());
    }

    @Test
    void dashboardSummarizesTheMonth() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "10000000");
        income(user, bank, "40000000", java.time.LocalDate.of(2026, 9, 25), category(user, "حقوق و دستمزد"), "حقوق");
        expense(user, bank, "10000000", java.time.LocalDate.of(2026, 10, 1), category(user, "اجاره"), "اجاره مهر");
        expense(user, bank, "3000000", java.time.LocalDate.of(2026, 9, 1), category(user, "اجاره"), "اجاره شهریور");

        mvc.perform(getAs(user, "/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netWorth.totalToman").value("37000000"))
                .andExpect(jsonPath("$.currentMonth.month").value("1405-07"))
                .andExpect(jsonPath("$.currentMonth.incomeToman").value("40000000"))
                .andExpect(jsonPath("$.currentMonth.expenseToman").value("10000000"))
                .andExpect(jsonPath("$.currentMonth.savingsRate").value("0.75"))
                .andExpect(jsonPath("$.previousMonth.month").value("1405-06"))
                .andExpect(jsonPath("$.previousMonth.expenseToman").value("3000000"))
                .andExpect(jsonPath("$.recentTransactions.length()").value(3))
                .andExpect(jsonPath("$.recentTransactions[0].description").value("اجاره مهر"));
    }
}
