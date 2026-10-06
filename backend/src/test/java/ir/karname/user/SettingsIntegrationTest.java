package ir.karname.user;

import ir.karname.support.AbstractIntegrationTest;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettingsIntegrationTest extends AbstractIntegrationTest {

    @Test
    void readsAndUpdatesSettings() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(getAs(user, "/api/v1/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayUnit").value("TOMAN"))
                .andExpect(jsonPath("$.wealthUnits[0]").value("USD"))
                .andExpect(jsonPath("$.aiEnabled").value(true));

        Map<String, Object> update = Map.of(
                "displayUnit", "RIAL", "digitStyle", "LATIN", "theme", "DARK",
                "wealthUnits", List.of("eur", "COIN_EMAMI"), "inflationRate", "38.5",
                "aiEnabled", false, "aiShareDescriptions", false);
        mvc.perform(putAs(user, "/api/v1/settings", update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayUnit").value("RIAL"))
                .andExpect(jsonPath("$.wealthUnits[0]").value("EUR"))
                .andExpect(jsonPath("$.inflationRate").value("38.5"));

        mvc.perform(getAs(user, "/api/v1/settings")).andExpect(jsonPath("$.theme").value("DARK"));
    }

    @Test
    void validationErrorsArePersian() throws Exception {
        TestUser user = createUser("sina");
        mvc.perform(putAs(user, "/api/v1/settings", Map.of("displayUnit", "TOMAN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.validation"))
                .andExpect(jsonPath("$.detail").value("بعضی از مقادیر واردشده معتبر نیستند."))
                .andExpect(jsonPath("$.errors[?(@.field == 'digitStyle')].message").value("الزامی است."));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("لطفاً وارد حساب کاربری شوید."));
    }
}
