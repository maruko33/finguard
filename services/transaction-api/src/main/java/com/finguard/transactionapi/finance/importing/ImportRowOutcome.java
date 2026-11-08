package com.finguard.transactionapi.finance.importing;

import com.finguard.transactionapi.finance.domain.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One displayable outcome. Invalid/unreliable fields are null; never stores raw CSV cells. */
public record ImportRowOutcome(
        long sourceRowNumber,
        ImportRowStatus status,
        LocalDate transactionDate,
        Instant occurredAt,
        String description,
        BigDecimal amount,
        String currencyCode,
        EntryType entryType,
        ImportSourceDirection sourceDirection,
        String bankTransactionId,
        List<ImportRowIssue> issues) {
    public ImportRowOutcome {
        if (sourceRowNumber < 2) throw new IllegalArgumentException("Data records start at 2");
        Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues);
        for (ImportRowIssue issue : issues) {
            if (issue.sourceRowNumber() != sourceRowNumber) {
                throw new IllegalArgumentException("Issue must refer to its outcome's source record");
            }
        }
        if (status == ImportRowStatus.ACCEPTED && (!issues.isEmpty() || entryType == null)) {
            throw new IllegalArgumentException("Accepted outcome requires a type and no issues");
        }
        if (status == ImportRowStatus.REVIEW_REQUIRED
                && (entryType != null || sourceDirection != ImportSourceDirection.CREDIT
                || issues.isEmpty() || issues.stream().anyMatch(i -> i.code() != ImportIssueCode.REVIEW_REQUIRED))) {
            throw new IllegalArgumentException("Review credit must remain unclassified with a review issue");
        }
        if (status == ImportRowStatus.REJECTED
                && (issues.isEmpty() || issues.stream().anyMatch(i -> i.code() == ImportIssueCode.REVIEW_REQUIRED))) {
            throw new IllegalArgumentException("Rejected outcome requires validation issues");
        }
        if (status != ImportRowStatus.REJECTED
                && (transactionDate == null || description == null || amount == null
                || currencyCode == null || sourceDirection == null)) {
            throw new IllegalArgumentException("Accepted and review outcomes require complete transaction facts");
        }
    }

    /** Only accepted outcomes may expose the existing complete candidate contract. */
    public Optional<NormalizedImportRow> candidate() {
        if (status != ImportRowStatus.ACCEPTED) return Optional.empty();
        return Optional.of(new NormalizedImportRow(sourceRowNumber, transactionDate, occurredAt,
                description, amount, currencyCode, entryType, bankTransactionId));
    }

    static ImportRowOutcome structuralRejection(long sourceRowNumber, ImportIssueCode code) {
        return new ImportRowOutcome(sourceRowNumber, ImportRowStatus.REJECTED,
                null, null, null, null, null, null, null, null,
                List.of(new ImportRowIssue(sourceRowNumber, code)));
    }

    /** Keep other facts if complete-candidate validation rejects a field. */
    ImportRowOutcome reject(List<ImportRowIssue> validationIssues) {
        var codes = validationIssues.stream().map(ImportRowIssue::code).toList();
        return new ImportRowOutcome(sourceRowNumber, ImportRowStatus.REJECTED,
                codes.contains(ImportIssueCode.INVALID_DATE) ? null : transactionDate,
                occurredAt,
                codes.contains(ImportIssueCode.INVALID_DESCRIPTION) ? null : description,
                codes.contains(ImportIssueCode.INVALID_AMOUNT) ? null : amount,
                codes.contains(ImportIssueCode.INVALID_CURRENCY) ? null : currencyCode,
                codes.contains(ImportIssueCode.INVALID_ENTRY_TYPE) ? null : entryType,
                sourceDirection, bankTransactionId, validationIssues);
    }

    /** Transaction facts belong in the explicit preview, never in diagnostic strings. */
    @Override
    public String toString() {
        return "ImportRowOutcome[sourceRowNumber=" + sourceRowNumber + ", status=" + status
                + ", issues=" + issues + "]";
    }
}
