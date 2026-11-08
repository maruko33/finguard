package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.FinancialAccount;
import com.finguard.transactionapi.finance.domain.ImportBatch;
import com.finguard.transactionapi.finance.domain.ImportStatus;
import com.finguard.transactionapi.finance.dto.ImportPreviewResponse;
import com.finguard.transactionapi.finance.exception.FinancialAccountNotFoundException;
import com.finguard.transactionapi.finance.exception.ImportIdempotencyConflictException;
import com.finguard.transactionapi.finance.exception.InvalidImportRequestException;
import com.finguard.transactionapi.finance.importing.BankType;
import com.finguard.transactionapi.finance.importing.CsvParseException;
import com.finguard.transactionapi.finance.importing.CsvParser;
import com.finguard.transactionapi.finance.importing.ImportIssueCode;
import com.finguard.transactionapi.finance.repository.FinancialAccountRepository;
import com.finguard.transactionapi.finance.repository.ImportBatchRepository;
import com.finguard.transactionapi.finance.repository.ImportCandidateRepository;
import com.finguard.transactionapi.finance.service.ImportBatchQueryService;
import com.finguard.transactionapi.finance.service.ImportPreparationService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportPreparationServiceTest {
    private static final String HEADER = "Transaction Date,Description 1,CAD$,USD$\n";
    private final FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportCandidateRepository candidates = mock(ImportCandidateRepository.class);
    private final ImportBatchQueryService queries = mock(ImportBatchQueryService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ImportPreparationService service = new ImportPreparationService(
            accounts, batches, candidates, queries, transactions, JsonMapper.builder().build());
    private final UUID user = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID uploadKey = UUID.randomUUID();

    @Test
    void rejectsMissingArgumentsBeforeReadingOrAccessingRepositories() {
        TrackingInput input = input(HEADER);
        assertThrows(InvalidImportRequestException.class,
                () -> service.prepareImport(null, accountId, input, BankType.RBC_VISA, uploadKey, "test.csv"));
        assertThrows(InvalidImportRequestException.class,
                () -> service.prepareImport(user, null, input, BankType.RBC_VISA, uploadKey, "test.csv"));
        assertThrows(InvalidImportRequestException.class,
                () -> service.prepareImport(user, accountId, null, BankType.RBC_VISA, uploadKey, "test.csv"));
        assertThrows(InvalidImportRequestException.class,
                () -> service.prepareImport(user, accountId, input, null, uploadKey, "test.csv"));
        assertThrows(InvalidImportRequestException.class,
                () -> service.prepareImport(user, accountId, input, BankType.RBC_VISA, null, "test.csv"));
        assertThrows(InvalidImportRequestException.class,
                () -> service.prepareImport(user, accountId, input, BankType.RBC_VISA, uploadKey, null));
        assertEquals(0, input.consumed);
        assertFalse(input.closed);
        verifyNoInteractions(accounts, batches, candidates, queries, transactions);
    }

    @Test
    void rejectsBlankLongPathAndControlCharacterNamesWithoutReadingInput() {
        TrackingInput input = input(HEADER);
        for (String name : List.of("", "  ", "a".repeat(256), "folder/file.csv", "folder\\file.csv",
                "bad\nname.csv", "bad\rname.csv", "bad\tname.csv", "bad\u0000name.csv", "bad\u0085name.csv")) {
            assertThrows(InvalidImportRequestException.class,
                    () -> service.prepareImport(user, accountId, input, BankType.RBC_VISA, uploadKey, name));
        }
        assertEquals(0, input.consumed);
        assertFalse(input.closed);
        verifyNoInteractions(accounts, batches, candidates, queries, transactions);
    }

    @Test
    void foreignAndMissingAccountUseSameNotFoundBeforeConsumingCsv() {
        UUID missing = UUID.randomUUID();
        when(accounts.findByIdAndUserId(accountId, user)).thenReturn(Optional.empty());
        when(accounts.findByIdAndUserId(missing, user)).thenReturn(Optional.empty());
        TrackingInput first = input(HEADER);
        TrackingInput second = input(HEADER);
        var foreign = assertThrows(FinancialAccountNotFoundException.class,
                () -> service.prepareImport(user, accountId, first, BankType.RBC_VISA, uploadKey, "test.csv"));
        var absent = assertThrows(FinancialAccountNotFoundException.class,
                () -> service.prepareImport(user, missing, second, BankType.RBC_VISA, uploadKey, "test.csv"));
        assertEquals(foreign.getMessage(), absent.getMessage());
        assertEquals(0, first.consumed);
        assertEquals(0, second.consumed);
        assertFalse(first.closed);
        assertFalse(second.closed);
        verifyNoInteractions(batches, candidates, queries, transactions);
    }

    @Test
    void oversizedStreamStopsAtOneProbeByteAndRemainsCallerOwned() {
        allowAccount(true);
        var endless = new EndlessInput();
        CsvParseException failure = assertThrows(CsvParseException.class,
                () -> service.prepareImport(user, accountId, endless, BankType.RBC_VISA, uploadKey, "test.csv"));
        assertEquals(ImportIssueCode.FILE_TOO_LARGE, failure.code());
        assertEquals(CsvParser.DEFAULT_MAX_BYTES + 1, endless.consumed);
        assertFalse(endless.closed);
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        verifyNoInteractions(batches, candidates, queries, transactions);
    }

    @Test
    void readFailureDoesNotExposeUnderlyingInputDiagnosticsOrCloseCallerStream() {
        allowAccount(true);
        final String marker = "SYNTHETIC_PRIVATE_STREAM_MARKER";
        class BrokenInput extends InputStream {
            boolean closed;
            @Override public int read() throws IOException { throw new IOException(marker); }
            @Override public void close() { closed = true; }
        }
        var input = new BrokenInput();
        CsvParseException failure = assertThrows(CsvParseException.class,
                () -> service.prepareImport(user, accountId, input, BankType.RBC_VISA, uploadKey, "test.csv"));
        assertEquals(ImportIssueCode.IO_ERROR, failure.code());
        assertFalse(failure.toString().contains(marker));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(input.closed);
        verifyNoInteractions(batches, candidates, queries, transactions);
    }

    @Test
    void exactRetryReadsOriginalBytesOnceAndBypassesParserEvenForInactiveAccount() throws Exception {
        allowAccount(false);
        // Matching persisted identity is sufficient for retry. These bytes would fail CSV parsing.
        TrackingInput input = input("\"unterminated");
        ImportBatch existing = storedBatch(input.bytes, accountId, BankType.RBC_VISA);
        when(batches.findByUserIdAndUploadKey(user, uploadKey)).thenReturn(Optional.of(existing));
        var expected = preview(existing.getId(), ImportStatus.CONFIRMED);
        when(queries.getImportPreview(user, existing.getId(), PageRequest.of(0, 15))).thenReturn(expected);

        var result = service.prepareImport(user, accountId, input, BankType.RBC_VISA, uploadKey, "renamed.csv");

        assertSame(expected, result);
        assertEquals("original.csv", result.fileName());
        assertEquals(ImportStatus.CONFIRMED, result.status());
        assertEquals(9, result.version());
        assertEquals(input.bytes.length, input.consumed);
        assertFalse(input.closed);
        verify(accounts).findByIdAndUserId(accountId, user);
        verify(batches).findByUserIdAndUploadKey(user, uploadKey);
        verify(queries).getImportPreview(user, existing.getId(), PageRequest.of(0, 15));
        verifyNoMoreInteractions(accounts, batches, queries);
        verifyNoInteractions(candidates, transactions);
    }

    @Test
    void displayNameAcceptsTheDatabaseCodePointLimitAndNeverReplacesOriginalName() throws Exception {
        allowAccount(true);
        byte[] bytes = HEADER.getBytes(StandardCharsets.UTF_8);
        ImportBatch existing = storedBatch(bytes, accountId, BankType.RBC_VISA);
        when(batches.findByUserIdAndUploadKey(user, uploadKey)).thenReturn(Optional.of(existing));
        var expected = preview(existing.getId(), ImportStatus.REVIEWING);
        when(queries.getImportPreview(user, existing.getId(), PageRequest.of(0, 15))).thenReturn(expected);
        String name = "\uD83D\uDCC4".repeat(255);
        assertEquals(255, name.codePointCount(0, name.length()));
        assertSame(expected, service.prepareImport(user, accountId, new ByteArrayInputStream(bytes),
                BankType.RBC_VISA, uploadKey, name));
        assertEquals("original.csv", expected.fileName());
        verifyNoInteractions(candidates, transactions);
    }

    @Test
    void sameKeyRejectsDifferentAccountRawBytesAndUnknownLegacyBank() throws Exception {
        allowAccount(true);
        byte[] bytes = HEADER.getBytes(StandardCharsets.UTF_8);
        ImportBatch otherAccount = storedBatch(bytes, UUID.randomUUID(), BankType.RBC_VISA);
        ImportBatch otherBytes = storedBatch((HEADER + "\n").getBytes(StandardCharsets.UTF_8), accountId, BankType.RBC_VISA);
        ImportBatch unknownBank = storedBatch(bytes, accountId, null);
        for (ImportBatch existing : List.of(otherAccount, otherBytes, unknownBank)) {
            when(batches.findByUserIdAndUploadKey(user, uploadKey)).thenReturn(Optional.of(existing));
            assertThrows(ImportIdempotencyConflictException.class,
                    () -> service.prepareImport(user, accountId, new ByteArrayInputStream(bytes),
                            BankType.RBC_VISA, uploadKey, "test.csv"));
        }
        verifyNoInteractions(candidates, queries, transactions);
    }

    @Test
    void newUploadRejectsInactiveAccountWithoutEnteringWriteTransaction() {
        allowAccount(false);
        when(batches.findByUserIdAndUploadKey(user, uploadKey)).thenReturn(Optional.empty());
        assertThrows(InvalidImportRequestException.class,
                () -> service.prepareImport(user, accountId, input(HEADER + "11/1/2026,Synthetic,-1.00,\n"),
                        BankType.RBC_VISA, uploadKey, "test.csv"));
        verifyNoInteractions(candidates, queries, transactions);
    }

    @Test
    void invalidHeadersAndHeaderOnlyUploadsDoNotEnterWriteTransaction() {
        allowAccount(true);
        when(batches.findByUserIdAndUploadKey(user, uploadKey)).thenReturn(Optional.empty());
        for (String csv : List.of(HEADER, "Wrong,Headers\nvalue,value\n")) {
            TrackingInput input = input(csv);
            assertThrows(InvalidImportRequestException.class,
                    () -> service.prepareImport(user, accountId, input, BankType.RBC_VISA, uploadKey, "test.csv"));
            assertEquals(input.bytes.length, input.consumed);
            assertFalse(input.closed);
        }
        verifyNoInteractions(candidates, queries, transactions);
    }

    @Test
    void accountAndIdempotencyLookupDatabaseFailuresPropagate() {
        var failure = new DataRetrievalFailureException("Synthetic database failure");
        when(accounts.findByIdAndUserId(accountId, user)).thenThrow(failure);
        assertSame(failure, assertThrows(DataRetrievalFailureException.class,
                () -> service.prepareImport(user, accountId, input(HEADER), BankType.RBC_VISA, uploadKey, "test.csv")));
        allowAccount(true);
        when(batches.findByUserIdAndUploadKey(user, uploadKey)).thenThrow(failure);
        assertSame(failure, assertThrows(DataRetrievalFailureException.class,
                () -> service.prepareImport(user, accountId, input(HEADER), BankType.RBC_VISA, uploadKey, "test.csv")));
        verifyNoInteractions(candidates, queries, transactions);
    }

    private void allowAccount(boolean active) {
        FinancialAccount account = mock(FinancialAccount.class);
        when(account.getActive()).thenReturn(active);
        when(account.getId()).thenReturn(accountId);
        when(account.getCurrencyCode()).thenReturn("CAD");
        doReturn(Optional.of(account)).when(accounts).findByIdAndUserId(accountId, user);
    }

    private ImportBatch storedBatch(byte[] bytes, UUID account, BankType bank) throws Exception {
        ImportBatch batch = mock(ImportBatch.class);
        when(batch.getId()).thenReturn(UUID.randomUUID());
        when(batch.getAccountId()).thenReturn(account);
        when(batch.getBankType()).thenReturn(bank);
        when(batch.getFileSha256()).thenReturn(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return batch;
    }

    private ImportPreviewResponse preview(UUID batchId, ImportStatus status) {
        return new ImportPreviewResponse(batchId, accountId, "original.csv", status, 9,
                Page.empty(PageRequest.of(0, 15)));
    }

    private static TrackingInput input(String text) { return new TrackingInput(text.getBytes(StandardCharsets.UTF_8)); }

    private static class TrackingInput extends InputStream {
        final byte[] bytes;
        int consumed;
        boolean closed;
        TrackingInput(byte[] bytes) { this.bytes = bytes; }
        @Override public int read() { return consumed == bytes.length ? -1 : bytes[consumed++] & 0xff; }
        @Override public int read(byte[] target, int offset, int length) {
            if (length == 0) return 0;
            if (consumed == bytes.length) return -1;
            int count = Math.min(length, bytes.length - consumed);
            System.arraycopy(bytes, consumed, target, offset, count);
            consumed += count;
            return count;
        }
        @Override public void close() { closed = true; }
    }

    private static class EndlessInput extends InputStream {
        long consumed;
        boolean closed;
        @Override public int read() { consumed++; return 'x'; }
        @Override public int read(byte[] target, int offset, int length) {
            Arrays.fill(target, offset, offset + length, (byte) 'x');
            consumed += length;
            return length;
        }
        @Override public void close() { closed = true; }
    }
}
