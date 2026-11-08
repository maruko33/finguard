package com.finguard.transactionapi.finance.service;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.ImportCandidate;
import com.finguard.transactionapi.finance.domain.Money;
import com.finguard.transactionapi.finance.exception.InvalidImportRequestException;
import com.finguard.transactionapi.finance.importing.ImportSourceDirection;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Shared read-only evidence calculation. Callers own locks, bounds and any metadata write-back. */
final class ImportDuplicateDetector {
    private static final String RULE = "finguard/within-batch-duplicate/v1";
    private static final String CROSS_SOURCE_RULE = "finguard/cross-source-duplicate/v1";
    private static final int MAX_EXTERNAL_SOURCES = 20_000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final ImportCandidateRepository candidates;

    ImportDuplicateDetector(ImportCandidateRepository candidates) { this.candidates = candidates; }

    record CandidateFacts(UUID id, EntryType entryType, BigDecimal amount, String currencyCode,
                          LocalDate transactionDate, String description, ImportSourceDirection sourceDirection) {
        static CandidateFacts from(ImportCandidate row) {
            return new CandidateFacts(row.getId(), row.getEntryType(), row.getAmount(), row.getCurrencyCode(),
                    row.getTransactionDate(), row.getDescription(), row.getSourceDirection());
        }
    }

    Map<UUID, String> findSignatures(UUID userId, UUID batchId, UUID accountId, List<CandidateFacts> rows) {
        var groups = new HashMap<MatchKey, List<UUID>>();
        for (var row : rows) {
            MatchKey key = matchKey(accountId, row);
            if (key != null) groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row.id());
        }
        // Finish every read, completeness check and signature before touching managed metadata.
        Map<MatchKey, Set<Evidence>> external = externalEvidence(accountId, batchId, userId, groups.keySet());
        var signatures = new HashMap<UUID, String>();
        for (var group : groups.entrySet()) {
            var ids = group.getValue().stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
            var evidence = new HashSet<>(external.getOrDefault(group.getKey(), Set.of()));
            // Ledger comparison omits direction; never invent a Ledger sourceDirection or Credit type.
            if (group.getKey().type() != null) {
                evidence.addAll(external.getOrDefault(group.getKey().withoutDirection(), Set.of()));
            }
            String signature;
            if (evidence.isEmpty()) {
                // Retain the existing v1 bytes/ack when no exact external match exists.
                if (ids.size() < 2 || group.getKey().direction() == null) continue;
                signature = signature(batchId, group.getKey(), ids);
            } else {
                if (ids.size() >= 2 && group.getKey().direction() != null) {
                    for (UUID id : ids) evidence.add(new Evidence(Source.INTRA_BATCH, id, batchId, group.getKey()));
                }
                signature = combinedSignature(batchId, group.getKey(), evidence);
            }
            for (UUID id : ids) signatures.put(id, signature);
        }
        return signatures;
    }

    private Map<MatchKey, Set<Evidence>> externalEvidence(UUID accountId, UUID batchId, UUID userId, Set<MatchKey> keys) {
        var result = new HashMap<MatchKey, Set<Evidence>>();
        if (keys.isEmpty()) return result;
        var dates = keys.stream().map(MatchKey::date).distinct().sorted().toList();
        var currencies = keys.stream().map(MatchKey::currency).distinct().sorted().toList();
        var amounts = keys.stream().map(MatchKey::amount).distinct().sorted().toList();
        var directions = keys.stream().map(MatchKey::direction).filter(java.util.Objects::nonNull)
                .map(Enum::name).distinct().sorted().toList();
        var types = keys.stream().map(MatchKey::type).filter(java.util.Objects::nonNull)
                .map(Enum::name).distinct().sorted().toList();
        boolean includeUnknownType = keys.stream().anyMatch(k -> k.type() == null);
        boolean includeLedger = !types.isEmpty();
        // Non-empty binding avoids dialect-dependent IN () behavior; the Ledger branch remains disabled.
        var typeFilter = includeLedger ? types : List.of("__NONE__");
        var directionFilter = directions.isEmpty() ? List.of("__NONE__") : directions;
        var sources = candidates.findExternalDuplicateSources(userId, batchId, accountId,
                dates, currencies, amounts, directionFilter, typeFilter, includeUnknownType, includeLedger,
                MAX_EXTERNAL_SOURCES + 1);
        if (sources.size() > MAX_EXTERNAL_SOURCES) {
            throw new InvalidImportRequestException("Duplicate scan exceeds the supported external source limit");
        }
        var ledgerKeys = new HashSet<MatchKey>();
        for (var key : keys) if (key.type() != null) ledgerKeys.add(key.withoutDirection());
        for (var row : sources) {
            Source source = Source.valueOf(row.getSourceKind());
            boolean ledger = source == Source.LEDGER;
            var direction = row.getSourceDirection() == null ? null : ImportSourceDirection.valueOf(row.getSourceDirection());
            var type = row.getEntryType() == null ? null : EntryType.valueOf(row.getEntryType());
            MatchKey key = matchKey(accountId, row.getTransactionDate(), row.getAmount(),
                    row.getCurrencyCode(), ledger ? null : direction, type, row.getDescription(), ledger);
            if (key == null || !(ledger ? ledgerKeys : keys).contains(key)) continue;
            var evidence = new Evidence(source, row.getSourceId(), row.getBatchId(), key);
            result.computeIfAbsent(key, ignored -> new HashSet<>()).add(evidence);
        }
        return result;
    }

    private static MatchKey matchKey(UUID accountId, CandidateFacts row) {
        // A legacy row with an explicit type can still match Ledger without a recorded bank direction.
        // Such a key is never eligible for either Candidate-to-Candidate source.
        return matchKey(accountId, row.transactionDate(), row.amount(), row.currencyCode(),
                row.sourceDirection(), row.entryType(), row.description(), row.sourceDirection() == null);
    }

    private static MatchKey matchKey(UUID accountId, LocalDate date, BigDecimal amount, String currency,
                                     ImportSourceDirection direction, EntryType type, String description,
                                     boolean ledger) {
        if (date == null || amount == null || currency == null || description == null
                || (ledger ? type == null : direction == null)) return null;
        String normalized = WHITESPACE.matcher(description).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        Money money;
        try {
            money = new Money(amount, currency);
            Currency.getInstance(money.currencyCode());
        } catch (ArithmeticException | IllegalArgumentException invalidStoredFact) {
            return null;
        }
        // Exact comparison normalization only. Neither source nor target transaction facts are assigned to.
        return new MatchKey(accountId, date, money.currencyCode(), direction, type, money.amount(), normalized);
    }

    private static String signature(UUID batchId, MatchKey key, List<UUID> ids) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(bytes)) {
                // Fixed order and UTF-8 byte lengths prevent delimiter ambiguity, including nullable type.
                writeString(data, RULE);
                writeString(data, batchId.toString());
                writeString(data, key.accountId().toString());
                writeString(data, key.date().toString());
                writeString(data, key.currency());
                writeString(data, key.direction().name());
                writeString(data, key.type() == null ? null : key.type().name());
                writeString(data, key.amount().toPlainString());
                writeString(data, key.description());
                data.writeInt(ids.size());
                for (UUID id : ids) writeString(data, id.toString());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Duplicate evidence signature could not be generated");
        }
    }

    private static String combinedSignature(UUID batchId, MatchKey key, Set<Evidence> evidence) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var data = new DataOutputStream(bytes)) {
                writeString(data, CROSS_SOURCE_RULE);
                writeString(data, batchId.toString());
                writeKey(data, key);
                var sorted = evidence.stream().sorted(Comparator.comparing((Evidence e) -> e.source().name())
                        .thenComparing(e -> e.batchId() == null ? "" : e.batchId().toString())
                        .thenComparing(e -> e.id().toString())).toList();
                data.writeInt(sorted.size());
                for (var item : sorted) {
                    writeString(data, item.source().name());
                    writeString(data, item.batchId() == null ? null : item.batchId().toString());
                    writeString(data, item.id().toString());
                    writeKey(data, item.key());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Duplicate evidence signature could not be generated", failure);
        }
    }

    private static void writeKey(DataOutputStream data, MatchKey key) throws IOException {
        writeString(data, key.accountId().toString());
        writeString(data, key.date().toString());
        writeString(data, key.currency());
        writeString(data, key.direction() == null ? null : key.direction().name());
        writeString(data, key.type() == null ? null : key.type().name());
        writeString(data, key.amount().toPlainString());
        writeString(data, key.description());
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        if (value == null) {
            data.writeInt(-1);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            data.writeInt(bytes.length);
            data.write(bytes);
        }
    }

    private record MatchKey(UUID accountId, LocalDate date, String currency, ImportSourceDirection direction,
                            EntryType type, BigDecimal amount, String description) {
        MatchKey withoutDirection() {
            return new MatchKey(accountId, date, currency, null, type, amount, description);
        }
    }

    private enum Source { INTRA_BATCH, OTHER_REVIEWING_BATCH, LEDGER }
    private record Evidence(Source source, UUID id, UUID batchId, MatchKey key) {}
}
