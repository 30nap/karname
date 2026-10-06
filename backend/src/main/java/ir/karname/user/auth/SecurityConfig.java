package ir.karname.user.auth;

import ir.karname.common.config.KarnameProperties;
import ir.karname.common.web.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(KarnameProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(properties.security().cookieSecure()));
        return repository;
    }

    /** Rotates the CSRF token at login and immediately sends the new cookie. */
    @Bean
    CsrfAuthenticationStrategy csrfAuthenticationStrategy(CookieCsrfTokenRepository csrfTokenRepository) {
        CsrfAuthenticationStrategy strategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        strategy.setRequestHandler(new SpaCsrfTokenRequestHandler());
        return strategy;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }

    public static final String SESSION_COOKIE = "KARNAME_SESSION";

    /**
     * Session cookie settings, applied for both embedded and mock servlet environments (Boot only
     * maps {@code server.servlet.session.cookie.*} when an embedded server is running).
     */
    @Bean
    DefaultCookieSerializerCustomizer sessionCookie(KarnameProperties properties) {
        return serializer -> {
            serializer.setCookieName(SESSION_COOKIE);
            serializer.setCookiePath("/");
            serializer.setUseHttpOnlyCookie(true);
            serializer.setSameSite("Lax");
            serializer.setUseSecureCookie(properties.security().cookieSecure());
            // Setting this request attribute at login makes the cookie persistent ("remember me").
            serializer.setRememberMeRequestAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR);
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository, Messages messages, JsonMapper jsonMapper) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                writeProblem(response, jsonMapper, HttpStatus.UNAUTHORIZED, "error.unauthorized", messages))
                        .accessDeniedHandler((request, response, ex) ->
                                writeProblem(response, jsonMapper, HttpStatus.FORBIDDEN, "error.forbidden", messages)))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, JsonMapper jsonMapper, HttpStatus status, String code,
            Messages messages) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messages.get(code));
        problem.setProperty("code", code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
