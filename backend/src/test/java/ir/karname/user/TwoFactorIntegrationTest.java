package ir.karname.user;

import ir.karname.support.AbstractIntegrationTest;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ir.karname.support.AbstractIntegrationTest.xsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TwoFactorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TotpService totp;

    private MvcResult login(String code) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("username", "sina", "password", PASSWORD));
        if (code != null) {
            body.put("totpCode", code);
        }
        return mvc.perform(post("/api/v1/auth/login").with(xsrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    @Test
    void enableAndUseTotpWithRecoveryCodes() throws Exception {
        TestUser user = createUser("sina");
        JsonNode setup = readJson(mvc.perform(postAs(user, "/api/v1/me/totp/setup", null))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String secret = setup.get("secret").asString();
        assertThat(setup.get("otpauthUri").asString()).startsWith("otpauth://totp/");

        mvc.perform(postAs(user, "/api/v1/me/totp/enable", Map.of("code", "000000"))).andExpect(status().isBadRequest());
        JsonNode enabled = readJson(mvc.perform(postAs(user, "/api/v1/me/totp/enable", Map.of("code", totp.currentCode(secret))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(enabled.get("recoveryCodes")).hasSize(8);
        String recovery = enabled.get("recoveryCodes").get(0).asString();

        MvcResult missing = login(null);
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(readJson(missing.getResponse().getContentAsString()).get("code").asString()).isEqualTo("auth.totpRequired");
        assertThat(login("123456").getResponse().getStatus()).isEqualTo(401);
        assertThat(login(totp.currentCode(secret)).getResponse().getStatus()).isEqualTo(200);

        // Persian digits are accepted too
        String persian = ir.karname.common.persian.PersianText.toPersianDigits(totp.currentCode(secret));
        assertThat(login(persian).getResponse().getStatus()).isEqualTo(200);

        assertThat(login(recovery).getResponse().getStatus()).isEqualTo(200);
        assertThat(login(recovery).getResponse().getStatus()).isEqualTo(401); // single use

        mvc.perform(postAs(user, "/api/v1/me/totp/disable", Map.of("password", PASSWORD))).andExpect(status().isNoContent());
        assertThat(login(null).getResponse().getStatus()).isEqualTo(200);
    }
}
