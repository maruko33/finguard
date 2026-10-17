package com.finguard.transactionapi.finance.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "import_batch")
public class ImportBatch {
    @Id
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "account_id", nullable = false)
    private UUID accountId;
    @Column(name = "upload_key", nullable = false)
    private UUID uploadKey;
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "file_sha256", nullable = false, columnDefinition = "char(64)")
    private String fileSha256;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ImportStatus status;
    @Version
    @Column(name = "version", nullable = false)
    private long version;
    @Column(name = "confirmed_count", nullable = false)
    private int confirmedCount;
    @Column(name = "excluded_count", nullable = false)
    private int excludedCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "confirmed_at", nullable = true)
    private Instant confirmedAt;
    @Column(name = "closed_at", nullable = true)
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", insertable = false, updatable = false)
    private FinancialAccount account;

    protected ImportBatch() {}

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }

    public UUID getAccountId() { return accountId; }

    public UUID getUploadKey() { return uploadKey; }

    public String getFileName() { return fileName; }

    public String getFileSha256() { return fileSha256; }

    public ImportStatus getStatus() { return status; }

    public long getVersion() { return version; }

    public int getConfirmedCount() { return confirmedCount; }

    public int getExcludedCount() { return excludedCount; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getConfirmedAt() { return confirmedAt; }

    public Instant getClosedAt() { return closedAt; }
}
