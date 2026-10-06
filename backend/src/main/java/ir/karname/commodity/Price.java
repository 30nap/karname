package ir.karname.commodity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/** Price of one unit of a commodity, in Toman, at a moment. */
@Entity
@Table(name = "prices")
public class Price {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_TRANSACTION = "TRANSACTION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "commodity_id", nullable = false)
    private Long commodityId;

    /** null = instance-wide price (automatic source or admin); otherwise the user's own. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "price_toman", nullable = false, precision = 24, scale = 8)
    private BigDecimal priceToman;

    @Column(name = "priced_at", nullable = false)
    private Instant pricedAt;

    @Column(nullable = false, length = 40)
    private String source;

    @Column(name = "transaction_id")
    private Long transactionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Price() {
    }

    public Price(Long commodityId, Long userId, BigDecimal priceToman, Instant pricedAt, String source, Long transactionId) {
        this.commodityId = commodityId;
        this.userId = userId;
        this.priceToman = priceToman;
        this.pricedAt = pricedAt;
        this.source = source;
        this.transactionId = transactionId;
    }

    public Long getId() {
        return id;
    }

    public Long getCommodityId() {
        return commodityId;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getPriceToman() {
        return priceToman;
    }

    public Instant getPricedAt() {
        return pricedAt;
    }

    public String getSource() {
        return source;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
