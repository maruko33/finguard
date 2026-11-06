package com.finguard.transactionapi.finance.service;

import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportCandidate;
import com.finguard.transactionapi.finance.dto.ImportPreviewResponse;
import com.finguard.transactionapi.finance.exception.FinancialAccountNotFoundException;
import com.finguard.transactionapi.finance.exception.ImportIdempotencyConflictException;
import com.finguard.transactionapi.finance.exception.InvalidImportRequestException;
import com.finguard.transactionapi.finance.importing.BankType;
import com.finguard.transactionapi.finance.importing.CsvImportProcessor;
import com.finguard.transactionapi.finance.importing.CsvImportResult;
import com.finguard.transactionapi.finance.importing.CsvParseException;
import com.finguard.transactionapi.finance.importing.CsvParser;
import com.finguard.transactionapi.finance.importing.ImportIssueCode;
import com.finguard.transactionapi.finance.importing.ImportRowIssue;
import com.finguard.transactionapi.finance.repository.FinancialAccountRepository;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ImportPreparationService {
    private final FinancialAccountRepository accounts;
    private final ImportBatchRepository batches;
    private final ImportCandidateRepository candidates;
    private final ImportBatchQueryService queries;
    private final JsonMapper jsonMapper;
    private final CsvImportProcessor processor = new CsvImportProcessor();
    private final TransactionTemplate writeTransaction;

    @PersistenceContext
    private EntityManager entityManager;

    public ImportPreparationService(FinancialAccountRepository accounts, ImportBatchRepository batches,
                                    ImportCandidateRepository candidates, ImportBatchQueryService queries,
                                    PlatformTransactionManager transactionManager, JsonMapper jsonMapper) {
        this.accounts = accounts;
        this.batches = batches;
        this.candidates = candidates;
        this.queries = queries;
        this.jsonMapper = jsonMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Trusted internal entry point. The caller owns and closes csv, including on failure.
     * The source stream is consumed once, with bounded buffering; neither bytes nor raw rows are stored.
     * Through the Spring proxy, an ambient transaction is suspended during this operation.
     * A new upload commits independently, so a later caller rollback does not undo it.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportPreviewResponse prepareImport(UUID currentUserId, UUID accountId, InputStream csv,
                                               BankType bankType, UUID uploadKey, String fileName) {
        validateRequest(currentUserId, accountId, csv, bankType, uploadKey, fileName);
        var account = accounts.findByIdAndUserId(accountId, currentUserId)
                .orElseThrow(FinancialAccountNotFoundException::new);
        UploadBytes upload = readUpload(csv);
        var existing = batches.findByUserIdAndUploadKey(currentUserId, uploadKey);
        if (existing.isPresent()) {
            verifyIdentity(existing.orElseThrow(), accountId, bankType, upload.sha256());
            return preview(currentUserId, existing.orElseThrow().getId());
        }
        if (!account.getActive()) throw new InvalidImportRequestException("Inactive account");
        CsvImportResult parsed = processor.process(new ByteArrayInputStream(upload.bytes()), bankType);
        if (!parsed.headerIssues().isEmpty()) {
            throw new InvalidImportRequestException("CSV headers do not match the selected bank profile");
        }
        if (parsed.rows().isEmpty()) {
            throw new InvalidImportRequestException("CSV must contain at least one data record");
        }
        UUID batchId = Objects.requireNonNull(writeTransaction.execute(status ->
                persist(currentUserId, accountId, bankType, uploadKey, fileName, upload.sha256(), parsed)));
        // TransactionTemplate has completed commit here; flush alone is not treated as success.
        return preview(currentUserId, batchId);
    }

    private UUID persist(UUID userId, UUID accountId, BankType bankType, UUID uploadKey,
                         String fileName, String sha256, CsvImportResult parsed) {
        // A shared row lock allows concurrent uploads but blocks account updates until this commit.
        var account = accounts.findOwnedForImport(accountId, userId)
                .orElseThrow(FinancialAccountNotFoundException::new);
        var existing = batches.findByUserIdAndUploadKey(userId, uploadKey);
        if (existing.isPresent()) {
            verifyIdentity(existing.orElseThrow(), accountId, bankType, sha256);
            return existing.orElseThrow().getId();
        }
        if (!account.getActive()) throw new InvalidImportRequestException("Inactive account");
        UUID proposedId = UUID.randomUUID();
        List<?> inserted = entityManager.createNativeQuery("""
                INSERT INTO {h-schema}import_batch
                    (id, user_id, account_id, upload_key, file_name, file_sha256, bank_type, status)
                VALUES (:id, :userId, :accountId, :uploadKey, :fileName, :sha256, :bankType, 'REVIEWING')
                ON CONFLICT ON CONSTRAINT uq_batch_upload_retry DO NOTHING
                RETURNING id
                """, UUID.class)
                .setParameter("id", proposedId).setParameter("userId", userId)
                .setParameter("accountId", accountId).setParameter("uploadKey", uploadKey)
                .setParameter("fileName", fileName).setParameter("sha256", sha256)
                .setParameter("bankType", bankType.name()).getResultList();
        if (inserted.isEmpty()) {
            // READ COMMITTED gives this statement a fresh snapshot after a competing insert commits.
            var winner = batches.findByUserIdAndUploadKey(userId, uploadKey)
                    .orElseThrow(() -> new IllegalStateException("Competing import batch was not visible"));
            verifyIdentity(winner, accountId, bankType, sha256);
            return winner.getId();
        }
        UUID batchId = (UUID) inserted.getFirst();
        var stored = new ArrayList<ImportCandidate>(parsed.rows().size());
        for (var row : parsed.rows()) {
            var issues = new ArrayList<>(row.issues());
            if (row.currencyCode() != null && !row.currencyCode().equals(account.getCurrencyCode())) {
                issues.add(new ImportRowIssue(row.sourceRowNumber(), ImportIssueCode.ACCOUNT_CURRENCY_MISMATCH));
            }
            String errors = jsonMapper.writeValueAsString(issues.stream().map(issue -> Map.of(
                    "sourceRowNumber", issue.sourceRowNumber(), "code", issue.code().name(),
                    "message", issue.message())).toList());
            stored.add(new ImportCandidate(UUID.randomUUID(), batchId, userId,
                    Math.toIntExact(row.sourceRowNumber()), row.entryType(), row.amount(), row.currencyCode(),
                    row.transactionDate(), row.occurredAt(), row.description(), row.sourceDirection(), errors));
        }
        candidates.saveAll(stored);
        candidates.flush();
        return batchId;
    }

    private ImportPreviewResponse preview(UUID userId, UUID batchId) {
        return queries.getImportPreview(userId, batchId, PageRequest.of(0, 15));
    }

    private static void verifyIdentity(ImportBatch batch, UUID accountId, BankType bankType, String sha256) {
        if (!accountId.equals(batch.getAccountId()) || batch.getBankType() == null
                || batch.getBankType() != bankType || !sha256.equals(batch.getFileSha256())) {
            throw new ImportIdempotencyConflictException();
        }
    }

    private static void validateRequest(UUID userId, UUID accountId, InputStream csv, BankType bankType,
                                        UUID uploadKey, String fileName) {
        if (userId == null || accountId == null || csv == null || bankType == null
                || uploadKey == null || fileName == null) {
            throw new InvalidImportRequestException("User, account, CSV, bank type, upload key and file name are required");
        }
        if (bankType != BankType.RBC_VISA) throw new InvalidImportRequestException("Unsupported bank profile");
        if (fileName.isBlank() || fileName.codePointCount(0, fileName.length()) > 255
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0
                || fileName.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidImportRequestException("Invalid display file name");
        }
    }

    private static UploadBytes readUpload(InputStream input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        try {
            while (true) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, CsvParser.DEFAULT_MAX_BYTES - total + 1));
                if (read == -1) break;
                if (read == 0) {
                    int value = input.read();
                    if (value == -1) break;
                    buffer[0] = (byte) value;
                    read = 1;
                }
                total += read;
                if (total > CsvParser.DEFAULT_MAX_BYTES) throw new CsvParseException(ImportIssueCode.FILE_TOO_LARGE);
                digest.update(buffer, 0, read);
                bytes.write(buffer, 0, read);
            }
        } catch (IOException | UncheckedIOException failure) {
            throw new CsvParseException(ImportIssueCode.IO_ERROR);
        }
        return new UploadBytes(bytes.toByteArray(), HexFormat.of().formatHex(digest.digest()));
    }

    private record UploadBytes(byte[] bytes, String sha256) {}
}
