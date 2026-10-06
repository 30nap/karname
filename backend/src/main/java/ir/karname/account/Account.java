package ir.karname.account;

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
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(name = "commodity_id", nullable = false)
    private Long commodityId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Bank bank;

    @Column(name = "identifier_hints", length = 60)
    private String identifierHints;

    @Column(length = 100)
    private String counterparty;

    @Column(length = 32)
    private String icon;

    @Column(name = "include_in_net_worth", nullable = false)
    private boolean includeInNetWorth = true;

    @Column(nullable = false)
    private boolean archived;

    private String notes;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Account() {
    }

    public Account(Long userId, String name, AccountType type, Long commodityId) {
        this.userId = userId;
        this.name = name;
        this.type = type;
        this.commodityId = commodityId;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public Long getCommodityId() {
        return commodityId;
    }

    public Bank getBank() {
        return bank;
    }

    public void setBank(Bank bank) {
        this.bank = bank;
    }

    public List<String> getIdentifierHints() {
        return identifierHints == null || identifierHints.isBlank() ? List.of() : Arrays.asList(identifierHints.split(","));
    }

    public void setIdentifierHints(List<String> hints) {
        this.identifierHints = hints == null || hints.isEmpty() ? null : String.join(",", hints);
    }

    public String getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(String counterparty) {
        this.counterparty = counterparty;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public boolean isIncludeInNetWorth() {
        return includeInNetWorth;
    }

    public void setIncludeInNetWorth(boolean includeInNetWorth) {
        this.includeInNetWorth = includeInNetWorth;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
