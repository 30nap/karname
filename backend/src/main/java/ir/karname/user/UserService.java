package ir.karname.user;

import ir.karname.common.persian.PersianText;
import ir.karname.common.security.Role;
import ir.karname.common.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class UserService {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9_.-]{2,31}$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final long REGISTRATION_LOCK_ID = 7_420_001L;

    private final UserRepository users;
    private final UserSettingsRepository settings;
    private final SystemSettingsService systemSettings;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final Clock clock;

    public UserService(UserRepository users, UserSettingsRepository settings, SystemSettingsService systemSettings,
            PasswordEncoder passwordEncoder, ApplicationEventPublisher events, JdbcClient jdbc, Clock clock) {
        this.users = users;
        this.settings = settings;
        this.systemSettings = systemSettings;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : PersianText.normalizeDigits(username).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Creates a user. The first user of the instance becomes ADMIN; afterwards registration
     * must be open (admins can close it).
     */
    @Transactional
    public User register(String rawUsername, String displayName, String password) {
        return create(rawUsername, displayName, password, true);
    }

    /**
     * Creates a user for a start-up task (the demo account) under the same rules, except that it
     * works while registration is closed.
     */
    @Transactional
    public User provision(String rawUsername, String displayName, String password) {
        return create(rawUsername, displayName, password, false);
    }

    private User create(String rawUsername, String displayName, String password, boolean needsOpenRegistration) {
        // Serialize registrations so two simultaneous "first users" cannot both become admin.
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(REGISTRATION_LOCK_ID).query((rs, rowNum) -> 1).list();

        boolean firstUser = users.count() == 0;
        if (needsOpenRegistration && !firstUser && !systemSettings.isRegistrationOpen()) {
            throw ApiException.forbidden("auth.registrationClosed");
        }
        String username = normalizeUsername(rawUsername);
        if (!USERNAME.matcher(username).matches()) {
            throw ApiException.badRequest("auth.invalidUsername");
        }
        validatePassword(password, username);
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("auth.usernameTaken");
        }
        String name = PersianText.clean(displayName);
        User user = new User(username, name == null ? username : name, passwordEncoder.encode(password),
                firstUser ? Role.ADMIN : Role.USER);
        users.save(user);
        settings.save(new UserSettings(user.getId()));
        events.publishEvent(new UserRegisteredEvent(user.getId()));
        return user;
    }

    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        User user = get(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("auth.wrongPassword");
        }
        validatePassword(newPassword, user.getUsername());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    @Transactional(readOnly = true)
    public void verifyPassword(long userId, String password) {
        if (password == null || !passwordEncoder.matches(password, get(userId).getPasswordHash())) {
            throw ApiException.badRequest("auth.wrongPassword");
        }
    }

    @Transactional
    public void resetPassword(long userId, String newPassword) {
        User user = get(userId);
        validatePassword(newPassword, user.getUsername());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    @Transactional
    public void recordLogin(long userId) {
        get(userId).setLastLoginAt(clock.instant());
    }

    @Transactional
    public User updateProfile(long userId, String displayName) {
        User user = get(userId);
        String name = PersianText.clean(displayName);
        if (name == null || name.length() > 100) {
            throw ApiException.badRequest("auth.invalidDisplayName");
        }
        user.setDisplayName(name);
        return user;
    }

    /** Deletes the user and, through ON DELETE CASCADE, every row they own. */
    @Transactional
    public void delete(long userId) {
        User user = get(userId);
        if (user.getRole() == Role.ADMIN && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1 && users.count() > 1) {
            throw ApiException.conflict("auth.lastAdmin");
        }
        users.delete(user);
    }

    @Transactional
    public User updateByAdmin(long actingAdminId, long userId, Boolean enabled, Role role) {
        User user = get(userId);
        boolean demotesOrDisablesAdmin = user.getRole() == Role.ADMIN
                && ((enabled != null && !enabled) || (role != null && role != Role.ADMIN));
        if (demotesOrDisablesAdmin && users.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw ApiException.conflict("auth.lastAdmin");
        }
        if (userId == actingAdminId && enabled != null && !enabled) {
            throw ApiException.conflict("auth.cannotDisableSelf");
        }
        if (enabled != null) {
            user.setEnabled(enabled);
        }
        if (role != null) {
            user.setRole(role);
        }
        return user;
    }

    @Transactional(readOnly = true)
    public User get(long userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("user.notFound"));
    }

    @Transactional
    public UserSettings settings(long userId) {
        return settings.findById(userId).orElseGet(() -> settings.save(new UserSettings(userId)));
    }

    private static void validatePassword(String password, String username) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > 128) {
            throw ApiException.badRequest("auth.weakPassword");
        }
        if (password.equalsIgnoreCase(username)) {
            throw ApiException.badRequest("auth.weakPassword");
        }
    }
}
