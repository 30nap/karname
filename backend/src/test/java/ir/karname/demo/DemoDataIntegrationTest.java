package ir.karname.demo;

import ir.karname.support.AbstractIntegrationTest;
import ir.karname.support.TestUser;
import ir.karname.common.security.Role;
import ir.karname.user.SystemSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoDataIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DemoData demo;

    @Autowired
    SystemSettingsService systemSettings;

    @Test
    void buildsHalfAYearOfConsistentFinances() throws Exception {
        long id = demo.create("demo", "کاربر نمایشی", PASSWORD);
        // the only user of a fresh instance administers it, as with registration
        TestUser user = new TestUser(id, "demo", Role.ADMIN);
        assertThat(userService.get(id).getRole()).isEqualTo(Role.ADMIN);

        JsonNode accounts = get(user, "/api/v1/accounts");
        assertThat(accounts).hasSize(10);
        for (JsonNode account : accounts) {
            assertThat(account.path("priced").asBoolean()).as(account.path("name").asString() + " priced").isTrue();
            if (!account.path("liability").asBoolean()) {
                assertThat(new BigDecimal(account.path("balance").asString())).as(account.path("name").asString() + " balance").isPositive();
            }
        }

        JsonNode dashboard = get(user, "/api/v1/dashboard");
        assertThat(new BigDecimal(dashboard.at("/netWorth/totalToman").asString())).isGreaterThan(new BigDecimal("500000000"));
        assertThat(dashboard.at("/netWorth/unpriced")).isEmpty();
        assertThat(new BigDecimal(dashboard.at("/previousMonth/incomeToman").asString())).isPositive();
        assertThat(new BigDecimal(dashboard.at("/previousMonth/expenseToman").asString())).isPositive();

        // the loan is repaid on schedule: nothing overdue
        JsonNode loan = get(user, "/api/v1/loans").get(0);
        assertThat(loan.path("paidCount").asInt()).isPositive();
        assertThat(loan.path("overdueCount").asInt()).isZero();

        assertThat(get(user, "/api/v1/budgets").path("items")).hasSize(4);
        assertThat(get(user, "/api/v1/goals")).hasSize(2);
        assertThat(get(user, "/api/v1/cheques")).hasSize(2);
        assertThat(get(user, "/api/v1/recurring")).hasSize(3);
        assertThat(get(user, "/api/v1/transactions?uncategorized=true&type=EXPENSE&size=50").path("items")).hasSize(3);
    }

    @Test
    void isCreatedEvenWithRegistrationClosed() {
        createUser("owner");
        systemSettings.setRegistrationOpen(false);
        long id = demo.create("demo", "کاربر نمایشی", PASSWORD);
        assertThat(userService.get(id).getRole()).isEqualTo(Role.USER);
    }

    private JsonNode get(TestUser user, String url) throws Exception {
        return readJson(mvc.perform(getAs(user, url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
