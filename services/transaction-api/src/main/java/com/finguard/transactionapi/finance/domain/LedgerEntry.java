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
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;
    @Column(name = "source_batch_id", nullable = true, updatable = false)
    private UUID sourceBatchId;
    @Column(name = "source_row_number", nullable = true, updatable = false)
    private Integer sourceRowNumber;
    @Column(name = "client_request_key", nullable = true, updatable = false)
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
    @Column(name = "bank_transaction_id", nullable = true, length = 255, updatable = false)
    private String bankTransactionId;
    @Column(name = "created_at", nullable = false, updatable = false)
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

    public static LedgerEntry manual(UUID userId, UUID accountId, UUID categoryId,
            EntryType entryType, Money money, LocalDate transactionDate,
            Instant occurredAt, String description) {
        var entry = new LedgerEntry();
        entry.id = UUID.randomUUID();
        entry.userId = java.util.Objects.requireNonNull(userId);
        entry.accountId = java.util.Objects.requireNonNull(accountId);
        entry.categoryId = categoryId;
        entry.entryType = java.util.Objects.requireNonNull(entryType);
        entry.amount = money.amount();
        entry.currencyCode = money.currencyCode();
        entry.transactionDate = java.util.Objects.requireNonNull(transactionDate);
        entry.occurredAt = occurredAt;
        entry.description = java.util.Objects.requireNonNull(description);
        entry.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        entry.updatedAt = entry.createdAt;
        return entry;
    }

    /** Imported entries retain durable batch/row provenance; current RBC input has no trusted bank ID. */
    public static LedgerEntry imported(UUID userId, UUID accountId, UUID sourceBatchId, int sourceRowNumber,
            UUID categoryId, EntryType entryType, Money money, LocalDate transactionDate,
            Instant occurredAt, String description) {
        if (sourceRowNumber < 1) throw new IllegalArgumentException("Invalid source row number");
        var entry = manual(userId, accountId, categoryId, entryType, money, transactionDate, occurredAt, description);
        entry.sourceBatchId = java.util.Objects.requireNonNull(sourceBatchId);
        entry.sourceRowNumber = sourceRowNumber;
        return entry;
    }

    /** Full replacement of editable fields; Hibernate alone manages the version. */
    public void replaceEditableFields(UUID categoryId, EntryType entryType, Money money,
            LocalDate transactionDate, Instant occurredAt, String description) {
        java.util.Objects.requireNonNull(entryType);
        java.util.Objects.requireNonNull(money);
        java.util.Objects.requireNonNull(transactionDate);
        java.util.Objects.requireNonNull(description);
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.updatedAt = nextUpdatedAt(now);
        this.categoryId = categoryId;
        this.entryType = entryType;
        this.amount = money.amount();
        this.currencyCode = money.currencyCode();
        this.transactionDate = transactionDate;
        this.occurredAt = occurredAt;
        this.description = description;
    }

    /** Retain the ledger row and all financial/source fields; Hibernate manages the version. */
    public void markDeleted() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        this.updatedAt = nextUpdatedAt(now);
        this.deletedAt = now;
    }

    private Instant nextUpdatedAt(Instant now) {
        // Guarantee a dirty update at PostgreSQL precision, even when the clock moves backward.
        return now.isAfter(updatedAt) ? now : updatedAt.plus(1, java.time.temporal.ChronoUnit.MICROS);
    }

    public Long getVersion() { return version; }

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
