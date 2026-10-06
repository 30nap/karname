package ir.karname.budget;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** A category's budget from a Jalali month on (recurring) or for that month only. */
@Entity
@Table(name = "budgets")
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** Jalali month, "YYYY-MM". */
    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "amount_toman", nullable = false, precision = 24, scale = 8)
    private BigDecimal amountToman;

    @Column(nullable = false)
    private boolean recurring = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Budget() {
    }

    public Budget(Long userId, Long categoryId, String month, BigDecimal amountToman, boolean recurring) {
        this.userId = userId;
        this.categoryId = categoryId;
        this.month = month;
        this.amountToman = amountToman;
        this.recurring = recurring;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getMonth() {
        return month;
    }

    public BigDecimal getAmountToman() {
        return amountToman;
    }

    public void setAmountToman(BigDecimal amountToman) {
        this.amountToman = amountToman;
    }

    public boolean isRecurring() {
        return recurring;
    }

    public void setRecurring(boolean recurring) {
        this.recurring = recurring;
    }
}
