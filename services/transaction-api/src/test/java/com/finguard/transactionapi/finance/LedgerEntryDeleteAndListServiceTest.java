package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.domain.FinancialAccount;
import com.finguard.transactionapi.finance.domain.LedgerEntry;
import com.finguard.transactionapi.finance.domain.Money;
import com.finguard.transactionapi.finance.dto.FinanceEntryResponse;
import com.finguard.transactionapi.finance.dto.ListFinanceEntriesFilter;
import com.finguard.transactionapi.finance.exception.FinanceEntryConflictException;
import com.finguard.transactionapi.finance.exception.FinanceEntryNotFoundException;
import com.finguard.transactionapi.finance.exception.FinancialAccountNotFoundException;
import com.finguard.transactionapi.finance.exception.InvalidFinanceEntryException;
import com.finguard.transactionapi.finance.repository.CategoryRepository;
import com.finguard.transactionapi.finance.repository.FinancialAccountRepository;
import com.finguard.transactionapi.finance.repository.LedgerEntryRepository;
import com.finguard.transactionapi.finance.service.LedgerEntryService;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerEntryDeleteAndListServiceTest {
    final LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
    final FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
    final CategoryRepository categories = mock(CategoryRepository.class);
    final LedgerEntryService service = new LedgerEntryService(entries, accounts, categories);
    final UUID owner = UUID.randomUUID();
    final UUID accountId = UUID.randomUUID();
    final LedgerEntry entry = LedgerEntry.manual(owner, accountId, UUID.randomUUID(),
            EntryType.EXPENSE, new Money(new BigDecimal("12.34"), "CAD"),
            LocalDate.of(2026, 10, 1), Instant.parse("2026-10-02T01:02:03Z"), "Original entry");
    final Sort supportedSort = Sort.by(Sort.Order.desc("transactionDate"), Sort.Order.desc("id"));
    final ListFinanceEntriesFilter filter = new ListFinanceEntriesFilter(accountId, null, null, null);

    void ownedEntry() {
        ReflectionTestUtils.setField(entry, "version", 3L);
        when(entries.findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner))
                .thenReturn(Optional.of(entry));
    }

    FinancialAccount ownedAccount() {
        var account = mock(FinancialAccount.class);
        when(accounts.findByIdAndUserId(accountId, owner)).thenReturn(Optional.of(account));
        return account;
    }

    @Test void deleteWithNullIdentityIsNotFoundWithoutRepositoryAccess() {
        assertThrows(FinanceEntryNotFoundException.class,
                () -> service.deleteEntry(null, entry.getId(), 3L));
        assertThrows(FinanceEntryNotFoundException.class,
                () -> service.deleteEntry(owner, null, 3L));
        verifyNoInteractions(entries, accounts, categories);
    }

    @Test void deleteHidesInaccessibleEntryBeforeVersionValidation() {
        for (Long expectedVersion : new Long[] {null, -1L, 3L}) {
            assertThrows(FinanceEntryNotFoundException.class,
                    () -> service.deleteEntry(owner, entry.getId(), expectedVersion));
        }
        verify(entries, times(3)).findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner);
        verifyNoMoreInteractions(entries);
        verifyNoInteractions(accounts, categories);
        assertNull(entry.getDeletedAt());
    }

    @Test void deleteRejectsNullAndNegativeExpectedVersionWithoutMutation() {
        ownedEntry();
        var before = FinanceEntryResponse.from(entry);
        for (Long expectedVersion : new Long[] {null, -1L}) {
            assertThrows(InvalidFinanceEntryException.class,
                    () -> service.deleteEntry(owner, entry.getId(), expectedVersion));
            assertEquals(before, FinanceEntryResponse.from(entry));
            assertNull(entry.getDeletedAt());
        }
        verify(entries, times(2)).findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner);
        verifyNoMoreInteractions(entries);
        verifyNoInteractions(accounts, categories);
    }

    @Test void deleteRejectsStaleExpectedVersionWithoutMutation() {
        ownedEntry();
        var before = FinanceEntryResponse.from(entry);
        assertThrows(FinanceEntryConflictException.class,
                () -> service.deleteEntry(owner, entry.getId(), 2L));
        assertEquals(before, FinanceEntryResponse.from(entry));
        assertNull(entry.getDeletedAt());
        verify(entries).findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner);
        verifyNoMoreInteractions(entries);
        verifyNoInteractions(accounts, categories);
    }

    @Test void deleteMarksServerTimestampPreservesMetadataAndFlushesWithoutAccountRevalidation() {
        ownedEntry();
        UUID sourceBatchId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        ReflectionTestUtils.setField(entry, "sourceBatchId", sourceBatchId);
        ReflectionTestUtils.setField(entry, "sourceRowNumber", 7);
        ReflectionTestUtils.setField(entry, "clientRequestKey", requestKey);
        ReflectionTestUtils.setField(entry, "bankTransactionId", "bank-reference");
        var before = FinanceEntryResponse.from(entry);
        var earliestDeletion = Instant.now().truncatedTo(ChronoUnit.MICROS);

        service.deleteEntry(owner, entry.getId(), 3L);

        assertNotNull(entry.getDeletedAt());
        assertFalse(entry.getDeletedAt().isBefore(earliestDeletion));
        assertFalse(entry.getDeletedAt().isAfter(Instant.now()));
        assertTrue(entry.getUpdatedAt().isAfter(before.updatedAt()));
        assertEquals(before.id(), entry.getId());
        assertEquals(owner, entry.getUserId());
        assertEquals(before.accountId(), entry.getAccountId());
        assertEquals(before.categoryId(), entry.getCategoryId());
        assertEquals(before.entryType(), entry.getEntryType());
        assertEquals(before.amount(), entry.getAmount());
        assertEquals(before.currencyCode(), entry.getCurrencyCode());
        assertEquals(before.transactionDate(), entry.getTransactionDate());
        assertEquals(before.occurredAt(), entry.getOccurredAt());
        assertEquals(before.description(), entry.getDescription());
        assertEquals(before.createdAt(), entry.getCreatedAt());
        assertEquals(sourceBatchId, entry.getSourceBatchId());
        assertEquals(7, entry.getSourceRowNumber());
        assertEquals(requestKey, entry.getClientRequestKey());
        assertEquals("bank-reference", entry.getBankTransactionId());
        // Hibernate owns version advancement; the PostgreSQL tests verify it after the SQL UPDATE.
        assertEquals(before.version(), entry.getVersion());
        verify(entries).findByIdAndUserIdAndDeletedAtIsNull(entry.getId(), owner);
        verify(entries).flush();
        verifyNoMoreInteractions(entries);
        verifyNoInteractions(accounts, categories);
    }

    @Test void deleteDoesNotMoveUpdatedAtBackwardWhenStoredTimestampIsAheadOfServerClock() {
        ownedEntry();
        Instant futureTimestamp = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        ReflectionTestUtils.setField(entry, "updatedAt", futureTimestamp);

        service.deleteEntry(owner, entry.getId(), 3L);

        assertTrue(entry.getUpdatedAt().isAfter(futureTimestamp));
        assertNotNull(entry.getDeletedAt());
        assertFalse(entry.getDeletedAt().isAfter(Instant.now()));
        verify(entries).flush();
    }

    @Test void deleteTranslatesSpringOptimisticLockFailureAndPreservesCause() {
        ownedEntry();
        var failure = new OptimisticLockingFailureException("Concurrent write");
        doThrow(failure).when(entries).flush();
        var conflict = assertThrows(FinanceEntryConflictException.class,
                () -> service.deleteEntry(owner, entry.getId(), 3L));
        assertSame(failure, conflict.getCause());
        verifyNoInteractions(accounts, categories);
    }

    @Test void deleteTranslatesJpaOptimisticLockFailureAndPreservesCause() {
        ownedEntry();
        var failure = new OptimisticLockException("Concurrent write");
        doThrow(failure).when(entries).flush();
        var conflict = assertThrows(FinanceEntryConflictException.class,
                () -> service.deleteEntry(owner, entry.getId(), 3L));
        assertSame(failure, conflict.getCause());
        verifyNoInteractions(accounts, categories);
    }

    @Test void listRejectsMissingRequiredInputsWithoutRepositoryAccess() {
        var page = PageRequest.of(0, 20);
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.listEntries(null, filter, page));
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.listEntries(owner, null, page));
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.listEntries(owner, new ListFinanceEntriesFilter(null, null, null, null), page));
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.listEntries(owner, filter, null));
        verifyNoInteractions(entries, accounts, categories);
    }

    @Test void listRejectsInvalidPaginationWithoutRepositoryAccess() {
        var negativePage = invalidPageable(-1, 20, -20);
        var zeroSize = invalidPageable(0, 0, 0);
        var negativeSize = invalidPageable(0, -1, 0);
        for (Pageable page : List.of(Pageable.unpaged(), negativePage, zeroSize, negativeSize,
                PageRequest.of(0, 101), PageRequest.of(Integer.MAX_VALUE, 100))) {
            assertThrows(InvalidFinanceEntryException.class,
                    () -> service.listEntries(owner, filter, page));
        }
        verifyNoInteractions(entries, accounts, categories);
    }

    @Test void listRejectsInvertedDateRangeWithoutRepositoryAccess() {
        var invalid = new ListFinanceEntriesFilter(accountId,
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), null);
        assertThrows(InvalidFinanceEntryException.class,
                () -> service.listEntries(owner, invalid, PageRequest.of(0, 20)));
        verifyNoInteractions(entries, accounts, categories);
    }

    @Test void listRejectsUnsupportedSortWithoutRepositoryAccess() {
        for (Sort sort : List.of(Sort.by("amount"), Sort.by(Sort.Order.desc("transactionDate")),
                Sort.by(Sort.Order.asc("transactionDate"), Sort.Order.desc("id")),
                Sort.by(Sort.Order.desc("id"), Sort.Order.desc("transactionDate")),
                supportedSort.and(Sort.by("description")),
                Sort.by(Sort.Order.desc("transactionDate").nullsFirst(), Sort.Order.desc("id")),
                Sort.by(Sort.Order.desc("transactionDate"), Sort.Order.desc("id").ignoreCase()))) {
            assertThrows(InvalidFinanceEntryException.class,
                    () -> service.listEntries(owner, filter, PageRequest.of(0, 20, sort)));
        }
        verifyNoInteractions(entries, accounts, categories);
    }

    @Test void listHidesMissingOrForeignAccountBeforeQueryingEntries() {
        var missingAccount = UUID.randomUUID();
        for (UUID inaccessibleAccount : List.of(accountId, missingAccount)) {
            assertThrows(FinancialAccountNotFoundException.class,
                    () -> service.listEntries(owner,
                            new ListFinanceEntriesFilter(inaccessibleAccount, null, null, null),
                            PageRequest.of(0, 20)));
            verify(accounts).findByIdAndUserId(inaccessibleAccount, owner);
        }
        verifyNoMoreInteractions(accounts);
        verifyNoInteractions(entries, categories);
    }

    @Test void listNormalizesUnsortedPageAndPreservesResponseVersionAndPaginationMetadata() {
        var account = ownedAccount();
        ReflectionTestUtils.setField(entry, "version", 8L);
        var normalized = PageRequest.of(1, 2, supportedSort);
        when(entries.findAll(anySpecification(), eq(normalized)))
                .thenReturn(new PageImpl<>(List.of(entry), normalized, 5));

        var result = service.listEntries(owner, filter, PageRequest.of(1, 2));

        assertEquals(List.of(FinanceEntryResponse.from(entry)), result.getContent());
        assertEquals(8L, result.getContent().getFirst().version());
        assertEquals(5, result.getTotalElements());
        assertEquals(3, result.getTotalPages());
        assertEquals(1, result.getNumber());
        assertEquals(2, result.getSize());
        assertEquals(supportedSort, result.getSort());
        var ordered = inOrder(accounts, entries);
        ordered.verify(accounts).findByIdAndUserId(accountId, owner);
        ordered.verify(entries).findAll(anySpecification(), eq(normalized));
        ordered.verifyNoMoreInteractions();
        verifyNoInteractions(account, categories);
    }

    @Test void listAcceptsSupportedSortMaximumPageSizeAndHistoricalInactiveAccount() {
        var account = ownedAccount();
        when(account.getActive()).thenReturn(false);
        var page = PageRequest.of(0, 100, supportedSort);
        when(entries.findAll(anySpecification(), eq(page))).thenReturn(Page.empty(page));

        var result = service.listEntries(owner, filter, page);

        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalElements());
        assertEquals(page, result.getPageable());
        verify(accounts).findByIdAndUserId(accountId, owner);
        verify(entries).findAll(anySpecification(), eq(page));
        verifyNoMoreInteractions(accounts, entries);
        verifyNoInteractions(account, categories);
    }

    private Pageable invalidPageable(int number, int size, long offset) {
        var pageable = mock(Pageable.class);
        when(pageable.isPaged()).thenReturn(true);
        when(pageable.getPageNumber()).thenReturn(number);
        when(pageable.getPageSize()).thenReturn(size);
        when(pageable.getOffset()).thenReturn(offset);
        when(pageable.getSort()).thenReturn(Sort.unsorted());
        return pageable;
    }

    private Specification<LedgerEntry> anySpecification() {
        return any();
    }
}
