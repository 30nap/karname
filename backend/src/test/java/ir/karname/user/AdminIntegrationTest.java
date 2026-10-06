package ir.karname.user;

import ir.karname.support.AbstractIntegrationTest;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminIntegrationTest extends AbstractIntegrationTest {

    @Test
    void onlyAdminsReachAdminApi() throws Exception {
        TestUser admin = createUser("admin");
        TestUser user = createUser("reza");
        mvc.perform(getAs(user, "/api/v1/admin/users")).andExpect(status().isForbidden());
        mvc.perform(getAs(admin, "/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("ADMIN"));
    }

    @Test
    void protectsTheLastAdmin() throws Exception {
        TestUser admin = createUser("admin");
        mvc.perform(patchAs(admin, "/api/v1/admin/users/{id}", Map.of("role", "USER"), admin.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("auth.lastAdmin"));
        mvc.perform(patchAs(admin, "/api/v1/admin/users/{id}", Map.of("enabled", false), admin.id()))
                .andExpect(status().isConflict());
    }

    @Test
    void promotesResetsAndDeletesUsers() throws Exception {
        TestUser admin = createUser("admin");
        TestUser user = createUser("reza");
        mvc.perform(patchAs(admin, "/api/v1/admin/users/{id}", Map.of("role", "ADMIN"), user.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(postAs(admin, "/api/v1/admin/users/{id}/reset-password", Map.of("newPassword", "new-password-1"), user.id()))
                .andExpect(status().isNoContent());
        mvc.perform(deleteAs(admin, "/api/v1/admin/users/{id}", admin.id())).andExpect(status().isConflict());
        mvc.perform(deleteAs(admin, "/api/v1/admin/users/{id}", user.id())).andExpect(status().isNoContent());
        mvc.perform(getAs(admin, "/api/v1/admin/users")).andExpect(jsonPath("$.length()").value(1));
    }
}
