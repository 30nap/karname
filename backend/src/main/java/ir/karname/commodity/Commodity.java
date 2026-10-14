package ir.karname.commodity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** A unit an account can hold: Toman, a currency, grams of gold, coins, crypto, or a custom unit. */
@Entity
@Table(name = "commodities")
public class Commodity {

    public static final String TOMAN = "IRT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String code;

    /** Owner of a custom commodity; null for built-in ones. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name_fa", nullable = false, length = 100)
    private String nameFa;

    @Column(name = "unit_fa", nullable = false, length = 30)
    private String unitFa;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CommodityKind kind;

    @Column(nullable = false)
    private short scale;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    /** Optional TSETMC instrument bound to this user's security; never a numeric JavaScript value. */
    @Column(name = "stock_instrument_id", length = 20)
    private String stockInstrumentId;

    @Column(name = "stock_symbol", length = 50)
    private String stockSymbol;

    @Column(name = "stock_last_attempt_at")
    private Instant stockLastAttemptAt;

    @Column(name = "stock_last_success_at")
    private Instant stockLastSuccessAt;

    @Column(name = "stock_last_error", length = 500)
    private String stockLastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Commodity() {
    }

    public Commodity(String code, Long userId, String nameFa, String unitFa, CommodityKind kind, int scale) {
        this.code = code;
        this.userId = userId;
        this.nameFa = nameFa;
        this.unitFa = unitFa;
        this.kind = kind;
        this.scale = (short) scale;
        this.sortOrder = 100;
    }

    public boolean isToman() {
        return TOMAN.equals(code);
    }

    public boolean isCustom() {
        return userId != null;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Long getUserId() {
        return userId;
    }

    public String getNameFa() {
        return nameFa;
    }

    public void setNameFa(String nameFa) {
        this.nameFa = nameFa;
    }

    public String getUnitFa() {
        return unitFa;
    }

    public void setUnitFa(String unitFa) {
        this.unitFa = unitFa;
    }

    public CommodityKind getKind() {
        return kind;
    }

    public void setKind(CommodityKind kind) {
        this.kind = kind;
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = (short) scale;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getStockInstrumentId() {
        return stockInstrumentId;
    }

    public void setStockInstrumentId(String stockInstrumentId) {
        this.stockInstrumentId = stockInstrumentId;
    }

    public String getStockSymbol() {
        return stockSymbol;
    }

    public void setStockSymbol(String stockSymbol) {
        this.stockSymbol = stockSymbol;
    }

    public Instant getStockLastAttemptAt() {
        return stockLastAttemptAt;
    }

    public void setStockLastAttemptAt(Instant stockLastAttemptAt) {
        this.stockLastAttemptAt = stockLastAttemptAt;
    }

    public Instant getStockLastSuccessAt() {
        return stockLastSuccessAt;
    }

    public void setStockLastSuccessAt(Instant stockLastSuccessAt) {
        this.stockLastSuccessAt = stockLastSuccessAt;
    }

    public String getStockLastError() {
        return stockLastError;
    }

    public void setStockLastError(String stockLastError) {
        this.stockLastError = stockLastError;
    }

    public long getVersion() {
        return version;
    }
}
