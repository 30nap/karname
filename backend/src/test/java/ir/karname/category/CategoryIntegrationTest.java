package ir.karname.category;

import ir.karname.account.Account;
import ir.karname.support.FinanceTestSupport;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryIntegrationTest extends FinanceTestSupport {

    @Test
    void newUsersGetPersianDefaults() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(getAs(user, "/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.systemKey == 'bank_fees')].name").value("کارمزد بانکی"))
                .andExpect(jsonPath("$[?(@.systemKey == 'salary')].kind").value("INCOME"))
                .andExpect(jsonPath("$[?(@.name == 'یارانه')].kind").value("INCOME"))
                .andExpect(jsonPath("$[?(@.name == 'تاکسی اینترنتی')].parentId").isNotEmpty());
    }

    @Test
    void createsUpdatesAndNestsCategories() throws Exception {
        TestUser user = createUser("sina");
        long parent = readJson(mvc.perform(postAs(user, "/api/v1/categories", Map.of("name", "حیوان خانگی", "kind", "EXPENSE", "icon", "paw-print")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        long child = readJson(mvc.perform(postAs(user, "/api/v1/categories", Map.of("name", "غذای گربه", "kind", "EXPENSE", "parentId", parent)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(postAs(user, "/api/v1/categories", Map.of("name", "سطح سوم", "kind", "EXPENSE", "parentId", child)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("category.tooDeep"));
        mvc.perform(postAs(user, "/api/v1/categories", Map.of("name", "حیوان خانگی", "kind", "EXPENSE")))
                .andExpect(status().isConflict());
        mvc.perform(postAs(user, "/api/v1/categories", Map.of("name", "x", "kind", "INCOME", "parentId", parent)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("category.kindMismatch"));
    }

    @Test
    void deletingReassignsTransactions() throws Exception {
        TestUser user = createUser("sina");
        Account bank = bank(user, "ملت", "1000000");
        long cafe = category(user, "کافه");
        long restaurant = category(user, "رستوران");
        expense(user, bank, "50000", TODAY, cafe, "قهوه");
        mvc.perform(deleteAs(user, "/api/v1/categories/{id}?reassignTo={to}", cafe, restaurant)).andExpect(status().isNoContent());
        mvc.perform(getAs(user, "/api/v1/transactions?categoryId={id}", restaurant)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(deleteAs(user, "/api/v1/categories/{id}", category(user, "کارمزد بانکی")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("category.systemCategory"));
    }
}
