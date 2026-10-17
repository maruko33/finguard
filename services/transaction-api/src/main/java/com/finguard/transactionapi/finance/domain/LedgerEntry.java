package com.finguard.transactionapi.finance.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "source_batch_id", nullable = true)
    private UUID sourceBatchId;
    @Column(name = "source_row_number", nullable = true)
    private Integer sourceRowNumber;
    @Column(name = "client_request_key", nullable = true)
    private UUID clientRequestKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10)
    private EntryType entryType;
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, columnDefinition = "char(3)")
    private String currencyCode;
    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;
    @Column(name = "occurred_at", nullable = true)
    private Instant occurredAt;
    @Column(name = "description", nullable = false, length = 500)
    private String description;
    @Column(name = "category_id", nullable = true)
    private UUID categoryId;
    @Column(name = "bank_transaction_id", nullable = true, length = 255)
    private String bankTransactionId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "deleted_at", nullable = true)
    private Instant deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private FinancialAccount account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_batch_id", insertable = false, updatable = false)
    private ImportBatch sourceBatch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private Category category;

    protected LedgerEntry() {}

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }

    public UUID getAccountId() { return accountId; }

    public UUID getSourceBatchId() { return sourceBatchId; }

    public Integer getSourceRowNumber() { return sourceRowNumber; }

    public UUID getClientRequestKey() { return clientRequestKey; }

    public EntryType getEntryType() { return entryType; }

    public BigDecimal getAmount() { return amount; }

    public String getCurrencyCode() { return currencyCode; }

    public LocalDate getTransactionDate() { return transactionDate; }

    public Instant getOccurredAt() { return occurredAt; }

    public String getDescription() { return description; }

    public UUID getCategoryId() { return categoryId; }

    public String getBankTransactionId() { return bankTransactionId; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }

    public Money getMoney() { return new Money(amount, currencyCode); }
}
