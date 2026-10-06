package ir.karname.user;

import ir.karname.common.security.Role;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** API representations of users and their settings. */
public final class UserViews {

    private UserViews() {
    }

    public record SettingsView(
            DisplayUnit displayUnit,
            DigitStyle digitStyle,
            ThemePreference theme,
            List<String> wealthUnits,
            BigDecimal inflationRate,
            boolean aiEnabled,
            boolean aiShareDescriptions) {

        public static SettingsView of(UserSettings s) {
            return new SettingsView(s.getDisplayUnit(), s.getDigitStyle(), s.getTheme(), s.getWealthUnits(),
                    s.getInflationRate(), s.isAiEnabled(), s.isAiShareDescriptions());
        }
    }

    public record MeView(long id, String username, String displayName, Role role, boolean totpEnabled,
            SettingsView settings) {

        public static MeView of(User user, UserSettings settings) {
            return new MeView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                    user.isTotpEnabled(), SettingsView.of(settings));
        }
    }

    public record AdminUserView(long id, String username, String displayName, Role role, boolean enabled,
            boolean totpEnabled, Instant createdAt, Instant lastLoginAt) {

        public static AdminUserView of(User u) {
            return new AdminUserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), u.isEnabled(),
                    u.isTotpEnabled(), u.getCreatedAt(), u.getLastLoginAt());
        }
    }
}
