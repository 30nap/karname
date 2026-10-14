package ir.karname.commodity;

import ir.karname.common.web.ApiException;
import ir.karname.io.BackupService;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StockPriceIntegrationTest extends FinanceTestSupport {

    @Autowired
    private CommodityRepository commodities;

    @Autowired
    private BackupService backups;

    private Commodity stock(TestUser user) {
        Commodity stock = commodityService.createCustom(user.id(),
                new CommodityService.CustomCommodityRequest("فولاد", "سهم", CommodityKind.SECURITY, 0));
        stock.setStockInstrumentId("46348559193224090");
        stock.setStockSymbol("فولاد");
        return commodities.saveAndFlush(stock);
    }

    @Test
    void closedMarketDoesNotBecomeFreshOrOverwriteANewerManualPrice() {
        TestUser user = createUser("stockowner");
        Commodity stock = stock(user);
        Instant tradingTime = clock.instant().minus(Duration.ofDays(1));
        assertThat(priceService.recordStockQuote(stock, new BigDecimal("3535.8"), tradingTime)).isTrue();
        priceService.recordManual(user.id(), stock, new BigDecimal("3600"), clock.instant(), false);

        clock.advance(Duration.ofDays(10));
        assertThat(priceService.recordStockQuote(stock, new BigDecimal("3535.8"), tradingTime)).isFalse();
        assertThat(priceService.recordStockQuote(stock, new BigDecimal("3400"), tradingTime.minusSeconds(1))).isFalse();
        assertThat(priceService.history(user.id(), stock, 20)).hasSize(2);
        PricePoint latest = priceService.latestPrices(user.id(), List.of(stock.getId()), clock.instant()).get(stock.getId());
        assertThat(latest.priceToman()).isEqualByComparingTo("3600");
        assertThat(latest.source()).isEqualTo("MANUAL");
        assertThat(commodityService.isStale(stock, latest.pricedAt())).isTrue();

        Instant newTradingTime = clock.instant().minusSeconds(60);
        assertThat(priceService.recordStockQuote(stock, new BigDecimal("3535.8"), newTradingTime)).isTrue();
        latest = priceService.latestPrices(user.id(), List.of(stock.getId()), clock.instant()).get(stock.getId());
        assertThat(latest.priceToman()).isEqualByComparingTo("3535.8");
        assertThat(latest.pricedAt()).isEqualTo(newTradingTime);
        assertThat(latest.personal()).isTrue();
    }

    @Test
    void invalidQuotesAndChangingTheKindOfALinkedSecurityAreRejected() throws Exception {
        TestUser user = createUser("stockowner");
        Commodity stock = stock(user);
        assertThatThrownBy(() -> priceService.recordStockQuote(stock, BigDecimal.ZERO, clock.instant()))
                .isInstanceOf(ApiException.class).hasMessage("price.invalid");
        assertThatThrownBy(() -> priceService.recordStockQuote(stock, BigDecimal.TEN, clock.instant().plusSeconds(1)))
                .isInstanceOf(ApiException.class).hasMessage("price.future");
        mvc.perform(putAs(user, "/api/v1/commodities/{code}", Map.of("nameFa", "ملک", "unitFa", "واحد", "kind", "PROPERTY"), stock.getCode()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("stock.kindLocked"));
        assertThat(priceService.history(user.id(), stock, 20)).isEmpty();
    }

    @Test
    void backupRetainsTheSymbolAndPrivateQuoteWhenMovedToAnotherUser() throws Exception {
        TestUser owner = createUser("stockowner");
        TestUser other = createUser("stockother");
        Commodity stock = stock(owner);
        Instant tradingTime = clock.instant().minusSeconds(120);
        priceService.recordStockQuote(stock, new BigDecimal("1250.5"), tradingTime);

        assertThat(backups.export(other.id()).tables().get("prices")).isEmpty();
        mvc.perform(getAs(other, "/api/v1/prices?commodity={code}", stock.getCode())).andExpect(status().isNotFound());
        backups.restore(other.id(), backups.export(owner.id()));

        Commodity restored = commodityService.require(other.id(), stock.getCode());
        assertThat(restored.getId()).isNotEqualTo(stock.getId());
        assertThat(restored.getUserId()).isEqualTo(other.id());
        assertThat(restored.getStockInstrumentId()).isEqualTo(stock.getStockInstrumentId());
        assertThat(restored.getStockSymbol()).isEqualTo("فولاد");
        assertThat(priceService.history(other.id(), restored, 20)).singleElement().satisfies(price -> {
            assertThat(price.priceToman()).isEqualByComparingTo("1250.5");
            assertThat(price.pricedAt()).isEqualTo(tradingTime);
            assertThat(price.source()).isEqualTo("TSETMC");
            assertThat(price.personal()).isTrue();
        });
    }
}
