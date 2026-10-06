package ir.karname.goal;

import ir.karname.account.Account;
import ir.karname.account.AccountType;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Today is 14 Mehr 1405 (2026-10-06); opening balances are dated 2026-04-06. */
class GoalIntegrationTest extends FinanceTestSupport {

    private static Map<String, Object> goal(String name, String target, String commodity, List<Long> accounts) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("targetAmount", target);
        body.put("commodity", commodity);
        body.put("accountIds", accounts);
        return body;
    }

    @Test
    void projectsAForeignCurrencyGoalFromLinkedAccounts() throws Exception {
        TestUser user = createUser("sina");
        globalPrice("EUR", "110000", Instant.parse("2026-03-01T06:00:00Z"));
        globalPrice("EUR", "120000", Instant.parse("2026-09-01T06:00:00Z"));
        Account euros = account(user, "یورو", AccountType.CURRENCY, "EUR", "1000");
        Account bank = bank(user, "ملت", "60000000");
        for (LocalDate day : List.of(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 8, 5), LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 5))) {
            income(user, bank, "12000000", day, null, "پس‌انداز");
        }
        Map<String, Object> body = goal("صندوق مهاجرت", "15000", "EUR", List.of(euros.getId(), bank.getId()));
        body.put("targetDate", "2027-09-22"); // end of Shahrivar 1406

        // now: 1,000 € + 132M Toman / 120,000 = 2,100 €; the month-ends grow about 92 € a month
        mvc.perform(postAs(user, "/api/v1/goals", body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentAmount").value("2100"))
                .andExpect(jsonPath("$.currentToman").value("252000000"))
                .andExpect(jsonPath("$.progress").value("0.14"))
                .andExpect(jsonPath("$.remaining").value("12900"))
                .andExpect(jsonPath("$.monthlyChange").value("91.88"))
                .andExpect(jsonPath("$.monthsToGoal").value(141))
                .andExpect(jsonPath("$.etaMonth").value("1417-04"))
                .andExpect(jsonPath("$.monthsLeft").value(11))
                .andExpect(jsonPath("$.requiredPerMonth").value("1172.73"))
                .andExpect(jsonPath("$.requiredPerMonthToman").value("140727600"))
                .andExpect(jsonPath("$.onTrack").value(false))
                .andExpect(jsonPath("$.achieved").value(false))
                .andExpect(jsonPath("$.missingPrices").value(false));
    }

    @Test
    void achievedGoal() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "132000000");
        Map<String, Object> body = goal("خرید لپ‌تاپ", "100000000", "IRT", List.of(bank.getId()));
        body.put("targetDate", "2026-12-21");
        mvc.perform(postAs(user, "/api/v1/goals", body))
                .andExpect(jsonPath("$.achieved").value(true))
                .andExpect(jsonPath("$.progress").value("1.32"))
                .andExpect(jsonPath("$.remaining").value("0"))
                .andExpect(jsonPath("$.monthsToGoal").value(0))
                .andExpect(jsonPath("$.etaMonth").doesNotExist())
                .andExpect(jsonPath("$.requiredPerMonth").doesNotExist())
                .andExpect(jsonPath("$.onTrack").value(true));
    }

    @Test
    void manualGoalWithoutAccounts() throws Exception {
        TestUser user = createUser("sina");
        Map<String, Object> body = goal("خرید خودرو", "1000000000", null, List.of());
        body.put("manualAmount", "250000000");
        mvc.perform(postAs(user, "/api/v1/goals", body))
                .andExpect(jsonPath("$.commodity").value("IRT"))
                .andExpect(jsonPath("$.currentAmount").value("250000000"))
                .andExpect(jsonPath("$.currentToman").value("250000000"))
                .andExpect(jsonPath("$.progress").value("0.25"))
                .andExpect(jsonPath("$.remaining").value("750000000"))
                .andExpect(jsonPath("$.monthlyChange").doesNotExist())
                .andExpect(jsonPath("$.onTrack").doesNotExist());
    }

    @Test
    void goalInAnUnpricedUnitReportsMissingPrices() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "60000000");
        mvc.perform(postAs(user, "/api/v1/goals", goal("پوند", "1000", "GBP", List.of(bank.getId()))))
                .andExpect(jsonPath("$.missingPrices").value(true))
                .andExpect(jsonPath("$.currentAmount").doesNotExist())
                .andExpect(jsonPath("$.currentToman").value("60000000"))
                .andExpect(jsonPath("$.progress").doesNotExist());
    }

    @Test
    void rejectsInvalidGoals() throws Exception {
        TestUser user = createUser("sina");
        Account loan = account(user, "وام", AccountType.LOAN, "IRT", "1000000");
        mvc.perform(postAs(user, "/api/v1/goals", goal("x", "1000", "IRT", List.of(loan.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("goal.liabilityAccount"));
        mvc.perform(postAs(user, "/api/v1/goals", goal("x", "0", "IRT", List.of())))
                .andExpect(jsonPath("$.code").value("goal.invalidTarget"));
        mvc.perform(postAs(user, "/api/v1/goals", goal("  ", "10", "IRT", List.of())))
                .andExpect(jsonPath("$.code").value("goal.invalidName"));
        mvc.perform(postAs(user, "/api/v1/goals", goal("x", "10", "NOPE", List.of()))).andExpect(status().isNotFound());
        Map<String, Object> body = goal("x", "10", "IRT", List.of());
        body.put("targetDate", "1900-01-01");
        mvc.perform(postAs(user, "/api/v1/goals", body)).andExpect(jsonPath("$.code").value("goal.invalidDate"));
    }

    @Test
    void updatesArchivesAndDeletesGoals() throws Exception {
        TestUser user = createUser("sina");
        String created = mvc.perform(postAs(user, "/api/v1/goals", goal("سفر", "50000000", "IRT", List.of())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = readJson(created).get("id").asLong();

        Map<String, Object> update = goal("سفر استانبول", "60000000", "IRT", List.of());
        update.put("archived", true);
        mvc.perform(putAs(user, "/api/v1/goals/{id}", update, id))
                .andExpect(jsonPath("$.name").value("سفر استانبول"))
                .andExpect(jsonPath("$.archived").value(true));
        mvc.perform(getAs(user, "/api/v1/goals")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(getAs(user, "/api/v1/goals?includeArchived=true")).andExpect(jsonPath("$[0].targetAmount").value("60000000"));

        mvc.perform(deleteAs(user, "/api/v1/goals/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/goals/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void customCommodityUsedByAGoalCannotBeDeleted() throws Exception {
        TestUser user = createUser("sina");
        String unit = mvc.perform(postAs(user, "/api/v1/commodities", Map.of("nameFa", "واحد صندوق", "unitFa", "واحد", "kind", "SECURITY", "scale", 0)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String code = readJson(unit).get("code").asString();
        mvc.perform(postAs(user, "/api/v1/goals", goal("صندوق", "100", code, List.of()))).andExpect(status().isCreated());
        mvc.perform(deleteAs(user, "/api/v1/commodities/{code}", code))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("commodity.inUse"));
    }
}
