package com.finguard.transactionapi.finance.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "import_candidate")
public class ImportCandidate {
    @Id
    private UUID id;
    @Column(name = "batch_id", nullable = false)
    private UUID batchId;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "row_number", nullable = false)
    private int rowNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = true, length = 10)
    private EntryType entryType;
    @Column(name = "amount", nullable = true, precision = 19, scale = 2)
    private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = true, columnDefinition = "char(3)")
    private String currencyCode;
    @Column(name = "transaction_date", nullable = true)
    private LocalDate transactionDate;
    @Column(name = "occurred_at", nullable = true)
    private Instant occurredAt;
    @Column(name = "description", nullable = true, length = 500)
    private String description;
    @Column(name = "category_id", nullable = true)
    private UUID categoryId;
    @Column(name = "bank_transaction_id", nullable = true, length = 255)
    private String bankTransactionId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors", nullable = false)
    private String validationErrors;
    @Column(name = "selected_for_import", nullable = false)
    private boolean selectedForImport;
    @Column(name = "duplicate_warning", nullable = false)
    private boolean duplicateWarning;
    @Column(name = "duplicate_acknowledged", nullable = false)
    private boolean duplicateAcknowledged;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "duplicate_match_signature", nullable = true, columnDefinition = "char(64)")
    private String duplicateMatchSignature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", insertable = false, updatable = false)
    private ImportBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private Category category;

    protected ImportCandidate() {}

    public UUID getId() { return id; }

    public UUID getBatchId() { return batchId; }

    public UUID getUserId() { return userId; }

    public int getRowNumber() { return rowNumber; }

    public EntryType getEntryType() { return entryType; }

    public BigDecimal getAmount() { return amount; }

    public String getCurrencyCode() { return currencyCode; }

    public LocalDate getTransactionDate() { return transactionDate; }

    public Instant getOccurredAt() { return occurredAt; }

    public String getDescription() { return description; }

    public UUID getCategoryId() { return categoryId; }

    public String getBankTransactionId() { return bankTransactionId; }

    public String getValidationErrors() { return validationErrors; }

    public boolean getSelectedForImport() { return selectedForImport; }

    public boolean getDuplicateWarning() { return duplicateWarning; }

    public boolean getDuplicateAcknowledged() { return duplicateAcknowledged; }

    public String getDuplicateMatchSignature() { return duplicateMatchSignature; }
}
