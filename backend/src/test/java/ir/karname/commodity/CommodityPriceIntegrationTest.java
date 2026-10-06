package ir.karname.commodity;

import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommodityPriceIntegrationTest extends FinanceTestSupport {

    @Test
    void listsBuiltInCommoditiesWithLatestPrices() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(getAs(user, "/api/v1/commodities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("IRT"))
                .andExpect(jsonPath("$[?(@.code == 'COIN_EMAMI')].nameFa").value("سکه امامی"))
                .andExpect(jsonPath("$[?(@.code == 'USD')].latestPrice").value(org.hamcrest.Matchers.contains((Object) null)));

        globalPrice("USD", "100000", Instant.parse("2026-10-06T06:00:00Z"));
        mvc.perform(getAs(user, "/api/v1/commodities"))
                .andExpect(jsonPath("$[?(@.code == 'USD')].latestPrice.priceToman").value("100000"))
                .andExpect(jsonPath("$[?(@.code == 'USD')].latestPrice.stale").value(false))
                .andExpect(jsonPath("$[?(@.code == 'USD')].latestPrice.personal").value(false));
    }

    @Test
    void newestPriceWinsBetweenPersonalAndGlobal() throws Exception {
        TestUser user = createUser("sina");
        globalPrice("USD", "100000", Instant.parse("2026-10-05T06:00:00Z"));
        mvc.perform(postAs(user, "/api/v1/prices", Map.of("commodity", "usd", "priceToman", "105000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personal").value(true));
        mvc.perform(getAs(user, "/api/v1/commodities"))
                .andExpect(jsonPath("$[?(@.code == 'USD')].latestPrice.priceToman").value("105000"));

        // a newer automatic price overrides the older personal one
        clock.advance(java.time.Duration.ofHours(1));
        globalPrice("USD", "107000", clock.instant());
        mvc.perform(getAs(user, "/api/v1/commodities"))
                .andExpect(jsonPath("$[?(@.code == 'USD')].latestPrice.priceToman").value("107000"));

        // other users never see personal prices
        TestUser other = createUser("reza");
        mvc.perform(getAs(other, "/api/v1/prices?commodity=USD"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(getAs(user, "/api/v1/prices?commodity=USD"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].priceToman").value("107000"));
    }

    @Test
    void flagsStalePrices() throws Exception {
        TestUser user = createUser("sina");
        globalPrice("GOLD18", "9000000", Instant.parse("2026-10-01T06:00:00Z"));
        mvc.perform(getAs(user, "/api/v1/commodities"))
                .andExpect(jsonPath("$[?(@.code == 'GOLD18')].latestPrice.stale").value(true));
    }

    @Test
    void onlyAdminsRecordGlobalPrices() throws Exception {
        TestUser admin = createUser("admin");
        TestUser user = createUser("reza");
        mvc.perform(postAs(user, "/api/v1/prices", Map.of("commodity", "USD", "priceToman", "100000", "global", true)))
                .andExpect(status().isForbidden());
        mvc.perform(postAs(admin, "/api/v1/prices", Map.of("commodity", "USD", "priceToman", "100000", "global", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personal").value(false));
        mvc.perform(getAs(user, "/api/v1/commodities"))
                .andExpect(jsonPath("$[?(@.code == 'USD')].latestPrice.priceToman").value("100000"));
    }

    @Test
    void rejectsInvalidPrices() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(postAs(user, "/api/v1/prices", Map.of("commodity", "IRT", "priceToman", "1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("price.tomanFixed"));
        mvc.perform(postAs(user, "/api/v1/prices", Map.of("commodity", "USD", "priceToman", "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("price.invalid"));
        mvc.perform(postAs(user, "/api/v1/prices", Map.of("commodity", "NOPE", "priceToman", "5")))
                .andExpect(status().isNotFound());
    }

    @Test
    void customCommodityLifecycle() throws Exception {
        TestUser user = createUser("sina");
        String code = readJson(mvc.perform(postAs(user, "/api/v1/commodities",
                        Map.of("nameFa", "واحد صندوق طلا", "unitFa", "واحد", "kind", "SECURITY", "scale", 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custom").value(true))
                .andReturn().getResponse().getContentAsString()).get("code").asString();

        mvc.perform(postAs(user, "/api/v1/prices", Map.of("commodity", code, "priceToman", "25000"))).andExpect(status().isCreated());
        mvc.perform(postAs(user, "/api/v1/accounts", Map.of("name", "صندوق عیار", "type", "INVESTMENT", "commodity", code,
                        "openingBalance", "1000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valueToman").value("25000000"));
        mvc.perform(deleteAs(user, "/api/v1/commodities/{code}", code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("commodity.inUse"));
        mvc.perform(deleteAs(user, "/api/v1/commodities/{code}", "USD")).andExpect(status().isForbidden());

        TestUser other = createUser("reza");
        mvc.perform(getAs(other, "/api/v1/prices?commodity={code}", code)).andExpect(status().isNotFound());
    }
}
