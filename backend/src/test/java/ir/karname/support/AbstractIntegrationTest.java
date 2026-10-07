package ir.karname.support;

import ir.karname.user.User;
import ir.karname.user.UserService;
import ir.karname.user.auth.LoginRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * Base class for tests against the full application and a real PostgreSQL database: either the
 * one in {@code KARNAME_TEST_DB_URL} or a Testcontainers instance started once per JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    public static final String PASSWORD = "secret-pass-123";

    private static final String JDBC_URL;
    private static final String DB_USER;
    private static final String DB_PASSWORD;

    /** Rows owned by users disappear through ON DELETE CASCADE; instance-wide tables are listed here. */
    private static final List<String> CLEANUP = List.of(
            "DELETE FROM spring_session",
            "DELETE FROM users",
            "DELETE FROM prices",
            "DELETE FROM app_settings",
            "DELETE FROM ai_usage",
            "DELETE FROM ai_providers",
            // the Nobitex source seeded by V5 stays; tests add their own
            "DELETE FROM price_sources WHERE name <> 'نوبیتکس'");

    static {
        String external = System.getenv("KARNAME_TEST_DB_URL");
        if (external != null && !external.isBlank()) {
            JDBC_URL = external;
            DB_USER = envOr("KARNAME_TEST_DB_USER", "karname");
            DB_PASSWORD = envOr("KARNAME_TEST_DB_PASSWORD", "karname");
        } else {
            PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
            postgres.start();
            JDBC_URL = postgres.getJdbcUrl();
            DB_USER = postgres.getUsername();
            DB_PASSWORD = postgres.getPassword();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected UserService userService;

    @Autowired
    private LoginRateLimiter rateLimiter;

    @BeforeEach
    void resetState() {
        CLEANUP.forEach(sql -> jdbc.sql(sql).update());
        rateLimiter.clear();
        clock.setInstant(TestClockConfig.DEFAULT_NOW);
    }

    /** Registers a user through the service (the first one becomes ADMIN). */
    protected TestUser createUser(String username) {
        User user = userService.register(username, "کاربر " + username, PASSWORD);
        return new TestUser(user.getId(), user.getUsername(), user.getRole());
    }

    /**
     * Sends a matching XSRF-TOKEN cookie and X-XSRF-TOKEN header, exactly like the SPA does.
     * (spring-security-test's {@code csrf()} is not used: it permanently replaces the filter's
     * token repository with a session-based one, which changes behavior for later tests.)
     */
    public static RequestPostProcessor xsrf() {
        return request -> {
            String token = java.util.UUID.randomUUID().toString();
            jakarta.servlet.http.Cookie[] existing = request.getCookies();
            java.util.List<jakarta.servlet.http.Cookie> cookies = new java.util.ArrayList<>(existing == null ? java.util.List.of() : java.util.List.of(existing));
            cookies.add(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token));
            request.setCookies(cookies.toArray(jakarta.servlet.http.Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    protected static RequestPostProcessor as(TestUser user) {
        var principal = user.principal();
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities()));
    }

    protected MockHttpServletRequestBuilder getAs(TestUser user, String url, Object... vars) {
        return MockMvcRequestBuilders.get(url, vars).with(as(user));
    }

    protected MockHttpServletRequestBuilder postAs(TestUser user, String url, Object body, Object... vars) {
        return withBody(MockMvcRequestBuilders.post(url, vars), body).with(as(user)).with(xsrf());
    }

    protected MockHttpServletRequestBuilder putAs(TestUser user, String url, Object body, Object... vars) {
        return withBody(MockMvcRequestBuilders.put(url, vars), body).with(as(user)).with(xsrf());
    }

    protected MockHttpServletRequestBuilder patchAs(TestUser user, String url, Object body, Object... vars) {
        return withBody(MockMvcRequestBuilders.patch(url, vars), body).with(as(user)).with(xsrf());
    }

    protected MockHttpServletRequestBuilder deleteAs(TestUser user, String url, Object... vars) {
        return MockMvcRequestBuilders.delete(url, vars).with(as(user)).with(xsrf());
    }

    protected MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) {
        if (body == null) {
            return builder;
        }
        String content = body instanceof String s ? s : json.writeValueAsString(body);
        return builder.contentType(MediaType.APPLICATION_JSON).content(content);
    }

    protected JsonNode readJson(String content) {
        return json.readTree(content);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
