package com.finguard.transactionapi.finance.domain;

import jakarta.persistence.*;
import com.finguard.transactionapi.finance.importing.ImportSourceDirection;
import java.util.UUID;
import java.util.Objects;
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
    // NULL denotes an unknown source direction, including unrecorded historical values.
    @Enumerated(EnumType.STRING)
    @Column(name = "source_direction", length = 10)
    private ImportSourceDirection sourceDirection;
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

    /** Persists the parser's reliable fields without classifying or selecting a staging row. */
    public ImportCandidate(UUID id, UUID batchId, UUID userId, int rowNumber, EntryType entryType,
                           BigDecimal amount, String currencyCode, LocalDate transactionDate,
                           Instant occurredAt, String description, ImportSourceDirection sourceDirection,
                           String validationErrors) {
        this.id = id;
        this.batchId = batchId;
        this.userId = userId;
        this.rowNumber = rowNumber;
        this.entryType = entryType;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.transactionDate = transactionDate;
        this.occurredAt = occurredAt;
        this.description = description;
        this.sourceDirection = sourceDirection;
        this.validationErrors = validationErrors;
    }

    public UUID getId() { return id; }

    public UUID getBatchId() { return batchId; }

    public UUID getUserId() { return userId; }

    public int getRowNumber() { return rowNumber; }

    public EntryType getEntryType() { return entryType; }

    public ImportSourceDirection getSourceDirection() { return sourceDirection; }

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

    /** Apply only a fully validated review decision; transaction/source facts and evidence stay unchanged. */
    public boolean replaceReviewDecision(UUID categoryId, boolean selectedForImport, boolean duplicateAcknowledged) {
        if (Objects.equals(this.categoryId, categoryId) && this.selectedForImport == selectedForImport
                && this.duplicateAcknowledged == duplicateAcknowledged) return false;
        this.categoryId = categoryId;
        this.selectedForImport = selectedForImport;
        this.duplicateAcknowledged = duplicateAcknowledged;
        return true;
    }

    /** Refresh only duplicate evidence; a prior human acknowledgement belongs to the same signature. */
    public boolean refreshDuplicateWarning(String signature) {
        if (signature != null && !signature.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid duplicate evidence signature");
        }
        boolean warning = signature != null;
        boolean acknowledged = warning && duplicateWarning && duplicateAcknowledged
                && Objects.equals(signature, duplicateMatchSignature);
        if (duplicateWarning == warning && duplicateAcknowledged == acknowledged
                && Objects.equals(duplicateMatchSignature, signature)) return false;
        duplicateWarning = warning;
        duplicateAcknowledged = acknowledged;
        duplicateMatchSignature = signature;
        return true;
    }
}
