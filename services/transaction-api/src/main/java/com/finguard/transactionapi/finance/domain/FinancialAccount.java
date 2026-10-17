package com.finguard.transactionapi.finance.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "financial_account")
public class FinancialAccount {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "name", nullable = false, length = 120)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 30)
    private AccountType accountType;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, columnDefinition = "char(3)")
    private String currencyCode;
    @Column(name = "active", nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private AppUser owner;

    protected FinancialAccount() {}

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }

    public String getName() { return name; }

    public AccountType getAccountType() { return accountType; }

    public String getCurrencyCode() { return currencyCode; }

    public boolean getActive() { return active; }

    public Instant getCreatedAt() { return createdAt; }
}
