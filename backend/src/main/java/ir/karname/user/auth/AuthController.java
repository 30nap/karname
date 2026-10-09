package ir.karname.user.auth;

import ir.karname.common.persian.PersianText;
import ir.karname.common.security.KarnamePrincipal;
import ir.karname.common.web.ApiException;
import ir.karname.user.SystemSettingsService;
import ir.karname.user.TwoFactorService;
import ir.karname.user.User;
import ir.karname.user.UserRepository;
import ir.karname.user.UserService;
import ir.karname.user.UserViews.MeView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ir.karname.common.config.KarnameProperties;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final UserRepository users;
    private final SystemSettingsService systemSettings;
    private final TwoFactorService twoFactor;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfAuthenticationStrategy csrfAuthenticationStrategy;
    private final KarnameProperties properties;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    /** Checked when the username does not exist, so response time does not reveal valid usernames. */
    private final String dummyHash;

    public AuthController(UserService userService, UserRepository users, SystemSettingsService systemSettings,
            TwoFactorService twoFactor, PasswordEncoder passwordEncoder, LoginRateLimiter rateLimiter,
            SecurityContextRepository securityContextRepository, CsrfAuthenticationStrategy csrfAuthenticationStrategy,
            KarnameProperties properties) {
        this.userService = userService;
        this.users = users;
        this.systemSettings = systemSettings;
        this.twoFactor = twoFactor;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.securityContextRepository = securityContextRepository;
        this.csrfAuthenticationStrategy = csrfAuthenticationStrategy;
        this.properties = properties;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password,
            @Size(max = 32) String totpCode, boolean rememberMe) {
    }

    public record RegisterRequest(@NotBlank @Size(max = 32) String username, @Size(max = 100) String displayName,
            @NotBlank @Size(max = 128) String password, boolean rememberMe) {
    }

    public record AuthStatus(boolean authenticated, boolean registrationOpen, boolean hasUsers, MeView user) {
    }

    /** Bootstrap call for the SPA; also makes sure the XSRF-TOKEN cookie is set. */
    @GetMapping("/status")
    public AuthStatus status(@AuthenticationPrincipal KarnamePrincipal principal) {
        boolean hasUsers = users.count() > 0;
        boolean registrationOpen = !hasUsers || systemSettings.isRegistrationOpen();
        if (principal == null) {
            return new AuthStatus(false, registrationOpen, hasUsers, null);
        }
        User user = userService.get(principal.id());
        return new AuthStatus(true, registrationOpen, hasUsers, MeView.of(user, userService.settings(user.getId())));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public MeView register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        rateLimiter.checkRegistration(httpRequest.getRemoteAddr());
        User user = userService.register(request.username(), request.displayName(), request.password());
        signIn(user, request.rememberMe(), httpRequest, httpResponse);
        return MeView.of(user, userService.settings(user.getId()));
    }

    @PostMapping("/login")
    public MeView login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String username = UserService.normalizeUsername(request.username());
        String ip = httpRequest.getRemoteAddr();
        rateLimiter.checkLogin(username, ip);

        User user = users.findByUsername(username).orElse(null);
        boolean passwordOk = passwordEncoder.matches(request.password(), user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordOk || !user.isEnabled()) {
            rateLimiter.recordFailure(username, ip);
            // one answer for all three: a distinct one for disabled accounts would confirm the password
            throw ApiException.unauthorized("auth.invalidCredentials");
        }
        if (user.isTotpEnabled()) {
            String code = PersianText.normalizeDigits(request.totpCode());
            if (code == null || code.isBlank()) {
                throw ApiException.unauthorized("auth.totpRequired");
            }
            if (!twoFactor.verifyLogin(user.getId(), code)) {
                rateLimiter.recordFailure(username, ip);
                throw ApiException.unauthorized("auth.invalidTotp");
            }
        }
        rateLimiter.reset(username, ip);
        userService.recordLogin(user.getId());
        signIn(user, request.rememberMe(), httpRequest, httpResponse);
        return MeView.of(user, userService.settings(user.getId()));
    }

    private void signIn(User user, boolean rememberMe, HttpServletRequest request, HttpServletResponse response) {
        KarnamePrincipal principal = new KarnamePrincipal(user.getId(), user.getUsername(), user.getRole());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities());

        // Session fixation protection: a new session id after authentication.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession(true);
        if (rememberMe) {
            session.setMaxInactiveInterval((int) properties.security().rememberMeDuration().toSeconds());
            request.setAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR, Boolean.TRUE);
        }

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // New CSRF token for the authenticated session.
        csrfAuthenticationStrategy.onAuthentication(authentication, request, response);
    }
}
