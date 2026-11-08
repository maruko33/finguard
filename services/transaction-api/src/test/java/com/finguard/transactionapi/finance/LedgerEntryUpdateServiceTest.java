package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.Category;
import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.FinancialAccount;
import com.finguard.transactionapi.finance.domain.LedgerEntry;
import com.finguard.transactionapi.finance.domain.Money;
import com.finguard.transactionapi.finance.dto.FinanceEntryResponse;
import com.finguard.transactionapi.finance.dto.UpdateFinanceEntryRequest;
import com.finguard.transactionapi.finance.exception.FinanceEntryConflictException;
import com.finguard.transactionapi.finance.exception.FinanceEntryNotFoundException;
import com.finguard.transactionapi.finance.exception.InvalidFinanceEntryException;
import com.finguard.transactionapi.finance.repository.CategoryRepository;
import com.finguard.transactionapi.finance.repository.FinancialAccountRepository;
import com.finguard.transactionapi.finance.repository.LedgerEntryRepository;
import com.finguard.transactionapi.finance.service.LedgerEntryService;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerEntryUpdateServiceTest {
    final LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
    final FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
    final CategoryRepository categories = mock(CategoryRepository.class);
    final LedgerEntryService service = new LedgerEntryService(entries, accounts, categories);
    final UUID owner = UUID.randomUUID();
    final UUID accountId = UUID.randomUUID();
    final LedgerEntry entry = LedgerEntry.manual(owner, accountId, UUID.randomUUID(),
            EntryType.EXPENSE, new Money(new BigDecimal("12.34"), "CAD"),
            LocalDate.of(2026, 10, 1), Instant.parse("2026-10-02T01:02:03Z"), "Original entry");

    void ownedEntry() {
        ReflectionTestUtils.setField(entry, "version", 0L);
        when(entries.findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner))
                .thenReturn(Optional.of(entry));
    }

    FinancialAccount activeAccount() {
        var account = mock(FinancialAccount.class);
        when(account.getActive()).thenReturn(true);
        when(account.getCurrencyCode()).thenReturn("CAD");
        when(accounts.findByIdAndUserId(accountId, owner)).thenReturn(Optional.of(account));
        return account;
    }

    UpdateFinanceEntryRequest replacement() {
        return request(null, EntryType.INCOME, new BigDecimal("98.76"), "CAD",
                LocalDate.of(2026, 10, 3), null, "Replacement entry", 0L);
    }

    UpdateFinanceEntryRequest request(UUID categoryId, EntryType type, BigDecimal amount,
            String currency, LocalDate date, Instant occurredAt, String description, Long version) {
        return new UpdateFinanceEntryRequest(categoryId, type, amount, currency,
                date, occurredAt, description, version);
    }

    @Test void nullIdentityReturnsNotFoundWithoutRepositoryAccess() {
        assertThrows(FinanceEntryNotFoundException.class,
                () -> service.updateEntry(null, entry.getId(), replacement()));
        assertThrows(FinanceEntryNotFoundException.class,
                () -> service.updateEntry(owner, null, replacement()));
        verifyNoInteractions(entries, accounts, categories);
    }

    @Test void inaccessibleEntryIsHiddenBeforeRequestValidation() {
        assertThrows(FinanceEntryNotFoundException.class,
                () -> service.updateEntry(owner, entry.getId(), null));
        var invalid = request(null, null, null, null, null, null, null, -1L);
        assertThrows(FinanceEntryNotFoundException.class,
                () -> service.updateEntry(owner, entry.getId(), invalid));
        verify(entries, times(2)).findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner);
        verifyNoMoreInteractions(entries);
        verifyNoInteractions(accounts, categories);
    }

    @Test void rejectsMissingRequiredFieldsAndInvalidExpectedVersionsBeforeMutation() {
        ownedEntry();
        var before = FinanceEntryResponse.from(entry);
        var valid = replacement();
        var invalidRequests = new UpdateFinanceEntryRequest[] {
                null,
                request(null, null, valid.amount(), "CAD", valid.transactionDate(), null, "", 0L),
                request(null, EntryType.INCOME, null, "CAD", valid.transactionDate(), null, "", 0L),
                request(null, EntryType.INCOME, valid.amount(), null, valid.transactionDate(), null, "", 0L),
                request(null, EntryType.INCOME, valid.amount(), "CAD", null, null, "", 0L),
                request(null, EntryType.INCOME, valid.amount(), "CAD", valid.transactionDate(), null, null, 0L),
                request(null, EntryType.INCOME, valid.amount(), "CAD", valid.transactionDate(), null, "x".repeat(501), 0L),
                request(null, EntryType.INCOME, valid.amount(), "CAD", valid.transactionDate(), null, "", null),
                request(null, EntryType.INCOME, valid.amount(), "CAD", valid.transactionDate(), null, "", -1L)
        };
        for (var invalid : invalidRequests) {
            assertThrows(InvalidFinanceEntryException.class,
                    () -> service.updateEntry(owner, entry.getId(), invalid));
            assertEquals(before, FinanceEntryResponse.from(entry));
        }
        verify(entries, never()).flush();
        verifyNoInteractions(accounts, categories);
    }

    @Test void staleExpectedVersionIsRejectedBeforeValidationOrMutation() {
        ownedEntry();
        ReflectionTestUtils.setField(entry, "version", 2L);
        var before = FinanceEntryResponse.from(entry);
        assertThrows(FinanceEntryConflictException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement()));
        assertEquals(before, FinanceEntryResponse.from(entry));
        verify(entries, never()).flush();
        verifyNoInteractions(accounts, categories);
    }

    @Test void invalidMoneyAndCurrencyDoNotMutateTheManagedEntry() {
        ownedEntry();
        var before = FinanceEntryResponse.from(entry);
        for (String amount : List.of("0", "-1", "1.001", "100000000000000000")) {
            var invalid = request(null, EntryType.INCOME, new BigDecimal(amount), "CAD",
                    LocalDate.of(2026, 10, 3), null, "Replacement", 0L);
            assertThrows(InvalidFinanceEntryException.class,
                    () -> service.updateEntry(owner, entry.getId(), invalid));
            assertEquals(before, FinanceEntryResponse.from(entry));
        }
        for (String currency : List.of("cad", "CA", "ZZZ")) {
            var invalid = request(null, EntryType.INCOME, BigDecimal.ONE, currency,
                    LocalDate.of(2026, 10, 3), null, "Replacement", 0L);
            assertThrows(InvalidFinanceEntryException.class,
                    () -> service.updateEntry(owner, entry.getId(), invalid));
            assertEquals(before, FinanceEntryResponse.from(entry));
        }
        verify(entries, never()).flush();
        verifyNoInteractions(accounts, categories);
    }

    @Test void revalidatesTheExistingAccountBeforeMutation() {
        ownedEntry();
        var before = FinanceEntryResponse.from(entry);
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement()));
        var account = activeAccount();
        when(account.getActive()).thenReturn(false);
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement()));
        when(account.getActive()).thenReturn(true);
        when(account.getCurrencyCode()).thenReturn("USD");
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement()));
        assertEquals(before, FinanceEntryResponse.from(entry));
        verify(accounts, times(3)).findByIdAndUserId(accountId, owner);
        verify(entries, never()).flush();
        verifyNoInteractions(categories);
    }

    @Test void categoryMustBelongToOwnerBeActiveAndMatchReplacementType() {
        ownedEntry();
        activeAccount();
        var before = FinanceEntryResponse.from(entry);
        UUID categoryId = UUID.randomUUID();
        var replacement = request(categoryId, EntryType.INCOME, BigDecimal.ONE, "CAD",
                LocalDate.of(2026, 10, 3), null, "Replacement", 0L);
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement));
        var category = mock(Category.class);
        when(categories.findByIdAndUserId(categoryId, owner)).thenReturn(Optional.of(category));
        when(category.getEntryType()).thenReturn(EntryType.INCOME);
        when(category.getActive()).thenReturn(false);
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement));
        when(category.getActive()).thenReturn(true);
        when(category.getEntryType()).thenReturn(EntryType.EXPENSE);
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement));
        assertEquals(before, FinanceEntryResponse.from(entry));
        verify(categories, times(3)).findByIdAndUserId(categoryId, owner);
        verify(entries, never()).flush();
    }

    @Test void fullReplacementClearsOptionalsAndReturnsVersionAfterFlush() {
        ownedEntry();
        activeAccount();
        var sourceBatch = UUID.randomUUID();
        var requestKey = UUID.randomUUID();
        var createdAt = entry.getCreatedAt();
        ReflectionTestUtils.setField(entry, "sourceBatchId", sourceBatch);
        ReflectionTestUtils.setField(entry, "sourceRowNumber", 7);
        ReflectionTestUtils.setField(entry, "clientRequestKey", requestKey);
        ReflectionTestUtils.setField(entry, "bankTransactionId", "bank-reference");
        // Only verifies response timing; PostgreSQL integration tests prove actual version increments.
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(entry, "version", 1L);
            return null;
        }).when(entries).flush();

        var result = service.updateEntry(owner, entry.getId(), replacement());

        assertEquals(EntryType.INCOME, result.entryType());
        assertEquals(new BigDecimal("98.76"), result.amount());
        assertEquals("CAD", result.currencyCode());
        assertEquals(LocalDate.of(2026, 10, 3), result.transactionDate());
        assertEquals("Replacement entry", result.description());
        assertNull(result.categoryId());
        assertNull(result.occurredAt());
        assertEquals(1L, result.version());
        assertEquals(entry.getId(), result.id());
        assertEquals(accountId, result.accountId());
        assertEquals(owner, entry.getUserId());
        assertEquals(createdAt, result.createdAt());
        assertFalse(result.updatedAt().isBefore(createdAt));
        assertEquals(sourceBatch, entry.getSourceBatchId());
        assertEquals(7, entry.getSourceRowNumber());
        assertEquals(requestKey, entry.getClientRequestKey());
        assertEquals("bank-reference", entry.getBankTransactionId());
        assertNull(entry.getDeletedAt());
        verify(entries).findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner);
        verify(entries).flush();
        verifyNoMoreInteractions(entries);
        verifyNoInteractions(categories);
    }

    @Test void translatesSpringOptimisticLockFailureAndPreservesCause() {
        ownedEntry();
        activeAccount();
        var failure = new OptimisticLockingFailureException("Concurrent write");
        doThrow(failure).when(entries).flush();
        var conflict = assertThrows(FinanceEntryConflictException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement()));
        assertSame(failure, conflict.getCause());
    }

    @Test void translatesJpaOptimisticLockFailureAndPreservesCause() {
        ownedEntry();
        activeAccount();
        var failure = new OptimisticLockException("Concurrent write");
        doThrow(failure).when(entries).flush();
        var conflict = assertThrows(FinanceEntryConflictException.class,
                () -> service.updateEntry(owner, entry.getId(), replacement()));
        assertSame(failure, conflict.getCause());
    }
}
