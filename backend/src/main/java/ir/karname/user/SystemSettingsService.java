package ir.karname.user;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Instance-wide settings stored as key/value pairs (e.g. whether registration is open). */
@Service
public class SystemSettingsService {

    static final String REGISTRATION_OPEN = "registration.open";

    private final JdbcClient jdbc;

    public SystemSettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return jdbc.sql("SELECT setting_value FROM app_settings WHERE setting_key = ?")
                .param(key)
                .query(String.class)
                .optional();
    }

    @Transactional
    public void set(String key, String value) {
        jdbc.sql("""
                INSERT INTO app_settings (setting_key, setting_value, updated_at) VALUES (?, ?, now())
                ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, updated_at = now()
                """)
                .param(key)
                .param(value)
                .update();
    }

    public boolean isRegistrationOpen() {
        return get(REGISTRATION_OPEN).map(Boolean::parseBoolean).orElse(true);
    }

    public void setRegistrationOpen(boolean open) {
        set(REGISTRATION_OPEN, Boolean.toString(open));
    }
}
