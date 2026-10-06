package ir.karname.user;

import ir.karname.support.AbstractIntegrationTest;
import ir.karname.support.TestUser;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ir.karname.support.AbstractIntegrationTest.xsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends AbstractIntegrationTest {

    private MvcResult register(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "displayName", "سینا", "password", password))))
                .andReturn();
    }

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
    }

    @Test
    void statusBeforeAnyUserAllowsRegistration() throws Exception {
        mvc.perform(get("/api/v1/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.hasUsers").value(false))
                .andExpect(jsonPath("$.registrationOpen").value(true));
    }

    @Test
    void firstUserBecomesAdminAndIsSignedIn() throws Exception {
        MvcResult result = register("Sina", PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(readJson(result.getResponse().getContentAsString()).get("role").asString()).isEqualTo("ADMIN");
        assertThat(readJson(result.getResponse().getContentAsString()).get("username").asString()).isEqualTo("sina");
        Cookie session = result.getResponse().getCookie("KARNAME_SESSION");
        assertThat(session).isNotNull();

        mvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("سینا"))
                .andExpect(jsonPath("$.settings.displayUnit").value("TOMAN"))
                .andExpect(jsonPath("$.settings.digitStyle").value("PERSIAN"));

        MvcResult second = register("reza", PASSWORD);
        assertThat(readJson(second.getResponse().getContentAsString()).get("role").asString()).isEqualTo("USER");
    }

    @Test
    void rejectsInvalidRegistrations() throws Exception {
        createUser("sina");
        assertThat(register("sina", PASSWORD).getResponse().getStatus()).isEqualTo(409);
        MvcResult weak = register("newuser", "short");
        assertThat(weak.getResponse().getStatus()).isEqualTo(400);
        assertThat(readJson(weak.getResponse().getContentAsString()).get("code").asString()).isEqualTo("auth.weakPassword");
        assertThat(register("سینا", PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(register("ab", PASSWORD).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void mutatingRequestsRequireCsrfToken() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("error.forbidden"));
    }

    @Test
    void statusSetsReadableCsrfCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/status")).andReturn();
        Cookie xsrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse();
    }

    @Test
    void loginLogoutCycle() throws Exception {
        createUser("sina");
        MvcResult bad = login("sina", "wrong-password");
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        assertThat(readJson(bad.getResponse().getContentAsString()).get("detail").asString())
                .isEqualTo("نام کاربری یا رمز عبور اشتباه است.");
        assertThat(login("nobody", PASSWORD).getResponse().getStatus()).isEqualTo(401);

        MvcResult ok = login(" SINA ", PASSWORD);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        Cookie session = ok.getResponse().getCookie("KARNAME_SESSION");
        mvc.perform(get("/api/v1/me").cookie(session)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").cookie(session).with(xsrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("error.unauthorized"));
    }

    @Test
    void locksOutAfterRepeatedFailures() throws Exception {
        createUser("sina");
        for (int i = 0; i < 5; i++) {
            assertThat(login("sina", "wrong-password").getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult locked = login("sina", PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(readJson(locked.getResponse().getContentAsString()).get("code").asString()).isEqualTo("auth.tooManyAttempts");
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        TestUser admin = createUser("admin");
        TestUser user = createUser("reza");
        mvc.perform(patchAs(admin, "/api/v1/admin/users/{id}", Map.of("enabled", false), user.id())).andExpect(status().isOk());
        MvcResult result = login("reza", PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(readJson(result.getResponse().getContentAsString()).get("code").asString()).isEqualTo("auth.disabled");
    }

    @Test
    void closedRegistrationRejectsNewUsers() throws Exception {
        TestUser admin = createUser("admin");
        mvc.perform(putAs(admin, "/api/v1/admin/system", Map.of("registrationOpen", false))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/status")).andExpect(jsonPath("$.registrationOpen").value(false));
        MvcResult result = register("reza", PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(readJson(result.getResponse().getContentAsString()).get("code").asString()).isEqualTo("auth.registrationClosed");
    }

    @Test
    void rememberMeMakesSessionCookiePersistent() throws Exception {
        createUser("sina");
        MvcResult plain = login("sina", PASSWORD);
        assertThat(plain.getResponse().getCookie("KARNAME_SESSION").getMaxAge()).isEqualTo(-1);

        MvcResult remembered = mvc.perform(post("/api/v1/auth/login").with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "sina", "password", PASSWORD, "rememberMe", true))))
                .andReturn();
        assertThat(remembered.getResponse().getCookie("KARNAME_SESSION").getMaxAge()).isGreaterThan(0);
        // The rotated CSRF token is sent with the login response itself.
        assertThat(remembered.getResponse().getHeaders("Set-Cookie"))
                .anyMatch(c -> c.startsWith("XSRF-TOKEN=") && !c.startsWith("XSRF-TOKEN=;"));
    }

    @Test
    void passwordChangeSignsOutOtherSessions() throws Exception {
        createUser("sina");
        Cookie first = login("sina", PASSWORD).getResponse().getCookie("KARNAME_SESSION");
        Cookie second = login("sina", PASSWORD).getResponse().getCookie("KARNAME_SESSION");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/me/password")
                        .cookie(second).with(xsrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("currentPassword", PASSWORD, "newPassword", "another-pass-456"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/me").cookie(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").cookie(second)).andExpect(status().isOk());
        assertThat(login("sina", "another-pass-456").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void deletingAccountRemovesUser() throws Exception {
        createUser("admin");
        TestUser reza = createUser("reza");
        mvc.perform(withBody(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me"),
                        Map.of("password", PASSWORD)).with(as(reza)).with(xsrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE username = 'reza'").query(Long.class).single()).isZero();
    }
}
