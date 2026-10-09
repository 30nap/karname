package ir.karname.ai.provider;

import ir.karname.support.AbstractIntegrationTest;
import ir.karname.support.StubServer;
import ir.karname.support.StubServer.Reply;
import ir.karname.support.TestUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiProviderAdminIntegrationTest extends AbstractIntegrationTest {

    private StubServer server;
    private TestUser admin;
    private TestUser member;

    @BeforeEach
    void setUp() {
        admin = createUser("admin");
        member = createUser("member");
        server = StubServer.start(r -> switch (r.path()) {
            case "/v1/models" -> Reply.json(200, "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}");
            case "/v1/chat/completions" -> Reply.sse("""
                    data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"ping","arguments":"{\\"word\\":\\"karname\\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """);
            default -> null;
        });
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private Map<String, Object> provider(String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "درگاه آزمایشی");
        body.put("preset", "CUSTOM");
        body.put("baseUrl", server.url() + "/v1");
        body.put("apiKey", key);
        body.put("headers", List.of(Map.of("name", "X-Org", "value", "org-secret")));
        body.put("queryParams", Map.of("api-version", "2026-01-01"));
        body.put("defaultModel", "model-a");
        return body;
    }

    private long create(Map<String, Object> body) throws Exception {
        String json = mvc.perform(postAs(admin, "/api/v1/admin/ai/providers", body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("sk-original").doesNotContain("org-secret");
        return readJson(json).get("id").asLong();
    }

    @Test
    void onlyAdministratorsManageProviders() throws Exception {
        mvc.perform(getAs(member, "/api/v1/admin/ai/providers")).andExpect(status().isForbidden());
        mvc.perform(postAs(member, "/api/v1/admin/ai/providers", provider("sk-x"))).andExpect(status().isForbidden());
    }

    @Test
    void secretsAreEncryptedNeverReturnedAndKeptWhenNotReentered() throws Exception {
        long id = create(provider("sk-original"));
        String stored = jdbc.sql("SELECT api_key_enc || coalesce(headers_enc, '') FROM ai_providers WHERE id = ?").param(id)
                .query(String.class).single();
        assertThat(stored).doesNotContain("sk-original").doesNotContain("org-secret");

        mvc.perform(getAs(admin, "/api/v1/admin/ai/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$[0].hasApiKey").value(true))
                .andExpect(jsonPath("$[0].headers[0].name").value("X-Org"))
                .andExpect(jsonPath("$[0].headers[0].hasValue").value(true))
                .andExpect(jsonPath("$[0].queryParams.api-version").value("2026-01-01"));

        // saved again without the key or header value: both are kept
        Map<String, Object> edit = provider(null);
        edit.put("name", "درگاه");
        edit.put("headers", List.of(Map.of("name", "X-Org", "value", "")));
        mvc.perform(putAs(admin, "/api/v1/admin/ai/providers/{id}", edit, id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasApiKey").value(true));

        Map<String, Object> test = Map.of("provider", edit, "model", "model-a");
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers/test?id={id}", test, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.toolCalling").value(true));
        StubServer.Recorded call = server.requests("POST", "/v1/chat/completions").getLast();
        assertThat(call.header("Authorization")).isEqualTo("Bearer sk-original");
        assertThat(call.header("X-Org")).isEqualTo("org-secret");
        assertThat(call.query()).isEqualTo("api-version=2026-01-01");

        // stored secrets are not lent to another address, neither for a try nor on save
        Map<String, Object> elsewhere = provider(null);
        elsewhere.put("baseUrl", "https://collector.example/v1");
        elsewhere.put("headers", List.of(Map.of("name", "X-Org", "value", "")));
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers/test?id={id}", Map.of("provider", elsewhere, "model", "model-a"), id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("aiProvider.keyForNewUrl"));
        mvc.perform(putAs(admin, "/api/v1/admin/ai/providers/{id}", elsewhere, id))
                .andExpect(jsonPath("$.code").value("aiProvider.keyForNewUrl"));
        elsewhere.put("apiKey", "sk-for-the-new-address");
        mvc.perform(putAs(admin, "/api/v1/admin/ai/providers/{id}", elsewhere, id))
                .andExpect(jsonPath("$.code").value("aiProvider.keyForNewUrl"));
        elsewhere.put("headers", List.of(Map.of("name", "X-Org", "value", "org-new")));
        mvc.perform(putAs(admin, "/api/v1/admin/ai/providers/{id}", elsewhere, id)).andExpect(status().isOk());

        Map<String, Object> cleared = provider(null);
        cleared.put("clearApiKey", true);
        cleared.put("headers", List.of(Map.of("name", "X-Org", "value", "org-secret")));
        mvc.perform(putAs(admin, "/api/v1/admin/ai/providers/{id}", cleared, id)).andExpect(jsonPath("$.hasApiKey").value(false));
    }

    @Test
    void theEnvironmentKeyOnlyGoesToAnthropicItself() throws Exception {
        Map<String, Object> gateway = new HashMap<>(Map.of("name", "درگاه", "preset", "ANTHROPIC", "baseUrl", "https://gateway.example",
                "useEnvKey", true));
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers", gateway))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("aiProvider.envKeyOfficialOnly"));
        gateway.put("baseUrl", "https://api.anthropic.com/");
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers", gateway)).andExpect(status().isCreated());
    }

    @Test
    void listsModelsAndReportsFailuresWithTheProvidersMessage() throws Exception {
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers/models", provider("sk-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("model-a"))
                .andExpect(jsonPath("$[1]").value("model-b"));

        server.respond(r -> Reply.json(401, "{\"error\":{\"message\":\"bad key\"}}"));
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers/test", Map.of("provider", provider("sk-1"), "model", "model-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bad key")));
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers/models", provider("sk-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("aiProvider.modelsFailed"));
    }

    @Test
    void validatesSettings() throws Exception {
        Map<String, Object> badUrl = provider("k");
        badUrl.put("baseUrl", "ftp://example.com");
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers", badUrl)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("aiProvider.invalidUrl"));
        Map<String, Object> badHeader = provider("k");
        badHeader.put("headers", List.of(Map.of("name", "Bad Header", "value", "x")));
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers", badHeader)).andExpect(jsonPath("$.code").value("aiProvider.invalidHeaders"));
        Map<String, Object> envOnOpenAi = provider(null);
        envOnOpenAi.put("useEnvKey", true);
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers", envOnOpenAi)).andExpect(jsonPath("$.code").value("aiProvider.envKeyAnthropicOnly"));
        create(provider("k"));
        mvc.perform(postAs(admin, "/api/v1/admin/ai/providers", provider("k"))).andExpect(status().isConflict());
    }

    @Test
    void routesTasksToProvidersAndForgetsThemWithTheProvider() throws Exception {
        long id = create(provider("sk-1"));
        mvc.perform(getAs(admin, "/api/v1/admin/ai/routes"))
                .andExpect(jsonPath("$[0].task").value("CHAT"))
                .andExpect(jsonPath("$[0].providerId").doesNotExist())
                .andExpect(jsonPath("$[2].defaultEffort").value("HIGH"));

        String saved = mvc.perform(putAs(admin, "/api/v1/admin/ai/routes", List.of(
                        Map.of("task", "CHAT", "providerId", id, "model", "model-b", "effort", "LOW"),
                        Map.of("task", "REPORT", "providerId", id))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode routes = readJson(saved);
        assertThat(routes.get(0).get("model").asString()).isEqualTo("model-b");
        assertThat(routes.get(0).get("effort").asString()).isEqualTo("LOW");
        assertThat(routes.get(0).get("providerName").asString()).isEqualTo("درگاه آزمایشی");
        // no model given: the provider's default
        assertThat(routes.get(2).get("model").asString()).isEqualTo("model-a");
        mvc.perform(getAs(admin, "/api/v1/admin/ai/providers")).andExpect(jsonPath("$[0].usedBy[0]").value("CHAT"));

        mvc.perform(deleteAs(admin, "/api/v1/admin/ai/providers/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(getAs(admin, "/api/v1/admin/ai/routes")).andExpect(jsonPath("$[0].providerId").doesNotExist());
    }

    @Test
    void dailyLimitIsConfigurable() throws Exception {
        mvc.perform(getAs(admin, "/api/v1/admin/ai/settings")).andExpect(jsonPath("$.dailyLimit").value(100));
        mvc.perform(putAs(admin, "/api/v1/admin/ai/settings", Map.of("dailyLimit", 5))).andExpect(jsonPath("$.dailyLimit").value(5));
        mvc.perform(putAs(admin, "/api/v1/admin/ai/settings", Map.of("dailyLimit", 0))).andExpect(status().isBadRequest());
    }

    @Test
    void offersTheOfflineModelOnlyWhereEnabled() throws Exception {
        mvc.perform(getAs(admin, "/api/v1/admin/ai/presets"))
                .andExpect(jsonPath("$.presets[0].id").value("ANTHROPIC"))
                .andExpect(jsonPath("$.presets[0].defaultModel").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.presets[?(@.id == 'FAKE')]").exists())
                .andExpect(jsonPath("$.envKeyAvailable").value(false));
    }
}
