package ir.karname.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "display_unit", nullable = false, length = 8)
    private DisplayUnit displayUnit = DisplayUnit.TOMAN;

    @Enumerated(EnumType.STRING)
    @Column(name = "digit_style", nullable = false, length = 8)
    private DigitStyle digitStyle = DigitStyle.PERSIAN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ThemePreference theme = ThemePreference.SYSTEM;

    /** Commodity codes used as alternative measures of wealth, e.g. {@code USD,GOLD18}. */
    @Column(name = "wealth_units", nullable = false, length = 200)
    private String wealthUnits = "USD,GOLD18";

    /** Assumed annual inflation in percent, used for real-value projections; optional. */
    @Column(name = "inflation_rate", precision = 6, scale = 2)
    private BigDecimal inflationRate;

    @Column(name = "ai_enabled", nullable = false)
    private boolean aiEnabled = true;

    /** Whether transaction descriptions may be sent to the AI provider. */
    @Column(name = "ai_share_descriptions", nullable = false)
    private boolean aiShareDescriptions = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected UserSettings() {
    }

    public UserSettings(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    public DisplayUnit getDisplayUnit() {
        return displayUnit;
    }

    public void setDisplayUnit(DisplayUnit displayUnit) {
        this.displayUnit = displayUnit;
    }

    public DigitStyle getDigitStyle() {
        return digitStyle;
    }

    public void setDigitStyle(DigitStyle digitStyle) {
        this.digitStyle = digitStyle;
    }

    public ThemePreference getTheme() {
        return theme;
    }

    public void setTheme(ThemePreference theme) {
        this.theme = theme;
    }

    public List<String> getWealthUnits() {
        return wealthUnits.isBlank() ? List.of() : Arrays.stream(wealthUnits.split(",")).map(String::trim).toList();
    }

    public void setWealthUnits(List<String> codes) {
        this.wealthUnits = String.join(",", codes);
    }

    public BigDecimal getInflationRate() {
        return inflationRate;
    }

    public void setInflationRate(BigDecimal inflationRate) {
        this.inflationRate = inflationRate;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }

    public boolean isAiShareDescriptions() {
        return aiShareDescriptions;
    }

    public void setAiShareDescriptions(boolean aiShareDescriptions) {
        this.aiShareDescriptions = aiShareDescriptions;
    }
}
