package com.finguard.transactionapi.finance.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id
    private UUID id;
    @Column(name = "auth_subject", nullable = false, length = 255)
    private String authSubject;
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "preferred_currency_code", nullable = false, columnDefinition = "char(3)")
    private String preferredCurrencyCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppUser() {}

    public UUID getId() { return id; }

    public String getAuthSubject() { return authSubject; }

    public String getDisplayName() { return displayName; }

    public String getPreferredCurrencyCode() { return preferredCurrencyCode; }

    public Instant getCreatedAt() { return createdAt; }
}
