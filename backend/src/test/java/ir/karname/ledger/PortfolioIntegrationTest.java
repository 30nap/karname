package ir.karname.ledger;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityKind;
import ir.karname.commodity.CommodityService.CustomCommodityRequest;
import ir.karname.commodity.Price;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import ir.karname.transaction.TransactionService.TransactionRequest;
import ir.karname.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PortfolioIntegrationTest extends FinanceTestSupport {

    @Test
    void sharesHeldAccountByAccountAddUpToAPortfolioWithCommissionInTheCostAndBonusSharesFree() throws Exception {
        TestUser user = createUser("investor");
        Account broker = bank(user, "کارگزاری", "50000000");
        Commodity foolad = commodityService.createCustom(user.id(), new CustomCommodityRequest("فولاد", "سهم", CommodityKind.SECURITY, 0));
        Commodity ayar = commodityService.createCustom(user.id(), new CustomCommodityRequest("عیار", "واحد", CommodityKind.SECURITY, 0));
        Account shares = account(user, "سهام فولاد", AccountType.INVESTMENT, foolad.getCode(), null);
        Account fund = account(user, "صندوق عیار", AccountType.INVESTMENT, ayar.getCode(), null);

        // 1,000 shares for 2,500,000 Toman and a 10,000 Toman commission: the commission is part of the cost
        transactionService.create(user.id(), new TransactionRequest(TransactionType.TRANSFER, TODAY.minusDays(20), broker.getId(),
                new BigDecimal("2500000"), shares.getId(), new BigDecimal("1000"), new BigDecimal("10000"), null, null, null, List.of()));
        // a 100% capital increase: 1,000 bonus shares recorded by reconciling the balance, at no cost
        transactionService.reconcile(user.id(), shares.getId(), new BigDecimal("2000"), TODAY.minusDays(5));
        transfer(user, broker, fund, "6000000", "100", TODAY.minusDays(10));
        price(user, foolad, "1300", TODAY.minusDays(1));
        price(user, ayar, "60000", TODAY.minusDays(1));

        mvc.perform(getAs(user, "/api/v1/portfolio/securities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdings.length()").value(2))
                // the fund is worth more, so it comes first
                .andExpect(jsonPath("$.holdings[0].accountName").value("صندوق عیار"))
                .andExpect(jsonPath("$.holdings[0].unrealizedToman").value("0"))
                .andExpect(jsonPath("$.holdings[1].quantity").value("2000"))
                .andExpect(jsonPath("$.holdings[1].costBasisToman").value("2510000"))
                .andExpect(jsonPath("$.holdings[1].averageCostToman").value("1255"))
                .andExpect(jsonPath("$.holdings[1].marketValueToman").value("2600000"))
                .andExpect(jsonPath("$.holdings[1].unrealizedToman").value("90000"))
                .andExpect(jsonPath("$.costBasisToman").value("8510000"))
                .andExpect(jsonPath("$.marketValueToman").value("8600000"))
                .andExpect(jsonPath("$.unrealizedToman").value("90000"))
                .andExpect(jsonPath("$.unpriced").value(0))
                .andExpect(jsonPath("$.costComplete").value(true));

        // other people's holdings are not part of it
        mvc.perform(getAs(createUser("other"), "/api/v1/portfolio/securities")).andExpect(jsonPath("$.holdings.length()").value(0));
    }

    private void price(TestUser user, Commodity commodity, String toman, java.time.LocalDate day) {
        priceRepository.save(new Price(commodity.getId(), user.id(), new BigDecimal(toman),
                day.atTime(12, 0).atZone(clock.getZone()).toInstant(), Price.SOURCE_MANUAL, null));
    }
}
