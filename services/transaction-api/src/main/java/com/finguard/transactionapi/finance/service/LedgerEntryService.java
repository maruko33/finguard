package com.finguard.transactionapi.finance.service;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.*;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.repository.*;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerEntryService {
    private static final Sort LIST_ORDER = Sort.by(Sort.Order.desc("transactionDate"), Sort.Order.desc("id"));
    private final LedgerEntryRepository entryRepository;
    private final FinancialAccountRepository accountRepository;
    private final CategoryRepository categoryRepository;

    public LedgerEntryService(LedgerEntryRepository entryRepository,
            FinancialAccountRepository accountRepository, CategoryRepository categoryRepository) {
        this.entryRepository = entryRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
    }

    @Transactional
    public FinanceEntryResponse createEntry(UUID currentUserId, CreateFinanceEntryRequest request) {
        if (currentUserId == null || request == null || request.accountId() == null) {
            throw new InvalidFinanceEntryException("Required entry fields are missing or invalid");
        }
        Money money = validateFields(request.entryType(), request.amount(), request.currencyCode(),
                request.transactionDate(), request.description());
        validateAccountAndCategory(currentUserId, request.accountId(), request.categoryId(), request.entryType(), money);
        var entry = LedgerEntry.manual(currentUserId, request.accountId(), request.categoryId(),
                request.entryType(), money, request.transactionDate(), request.occurredAt(), request.description());
        return FinanceEntryResponse.from(entryRepository.save(entry));
    }

    @Transactional
    public FinanceEntryResponse updateEntry(UUID currentUserId, UUID entryId, UpdateFinanceEntryRequest request) {
        var entry = findOwnedEntry(currentUserId, entryId);
        validateExpectedVersion(entry, request == null ? null : request.expectedVersion());
        Money money = validateFields(request.entryType(), request.amount(), request.currencyCode(),
                request.transactionDate(), request.description());
        validateAccountAndCategory(currentUserId, entry.getAccountId(), request.categoryId(), request.entryType(), money);
        entry.replaceEditableFields(request.categoryId(), request.entryType(), money,
                request.transactionDate(), request.occurredAt(), request.description());
        flushEntryChanges();
        return FinanceEntryResponse.from(entry);
    }

    @Transactional
    public void deleteEntry(UUID currentUserId, UUID entryId, Long expectedVersion) {
        var entry = findOwnedEntry(currentUserId, entryId);
        validateExpectedVersion(entry, expectedVersion);
        entry.markDeleted();
        flushEntryChanges();
    }

    @Transactional(readOnly = true)
    public Page<FinanceEntryResponse> listEntries(UUID currentUserId,
            ListFinanceEntriesFilter filter, Pageable pageable) {
        var page = validateListRequest(currentUserId, filter, pageable);
        accountRepository.findByIdAndUserId(filter.accountId(), currentUserId)
                .orElseThrow(FinancialAccountNotFoundException::new);
        Specification<LedgerEntry> matchingEntries = (entry, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(builder.equal(entry.get("userId"), currentUserId));
            predicates.add(builder.equal(entry.get("accountId"), filter.accountId()));
            predicates.add(builder.isNull(entry.get("deletedAt")));
            if (filter.fromTransactionDate() != null) {
                predicates.add(builder.greaterThanOrEqualTo(entry.<LocalDate>get("transactionDate"),
                        filter.fromTransactionDate()));
            }
            if (filter.toTransactionDate() != null) {
                predicates.add(builder.lessThanOrEqualTo(entry.<LocalDate>get("transactionDate"),
                        filter.toTransactionDate()));
            }
            if (filter.entryType() != null) {
                predicates.add(builder.equal(entry.get("entryType"), filter.entryType()));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return entryRepository.findAll(matchingEntries, page).map(FinanceEntryResponse::from);
    }

    @Transactional(readOnly = true)
    public FinanceEntryResponse findById(UUID currentUserId, UUID entryId) {
        return FinanceEntryResponse.from(findOwnedEntry(currentUserId, entryId));
    }

    private PageRequest validateListRequest(UUID currentUserId, ListFinanceEntriesFilter filter, Pageable pageable) {
        if (currentUserId == null || filter == null || filter.accountId() == null
                || pageable == null || pageable.isUnpaged()) {
            throw new InvalidFinanceEntryException("User, account, filter and paged request are required");
        }
        int pageNumber = pageable.getPageNumber();
        int pageSize = pageable.getPageSize();
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100
                || (long) pageNumber * pageSize > Integer.MAX_VALUE) {
            throw new InvalidFinanceEntryException("Invalid page number or size; maximum page size is 100");
        }
        if (filter.fromTransactionDate() != null && filter.toTransactionDate() != null
                && filter.fromTransactionDate().isAfter(filter.toTransactionDate())) {
            throw new InvalidFinanceEntryException("Transaction date range is invalid");
        }
        Sort sort = pageable.getSort();
        if (sort == null || (sort.isSorted() && !LIST_ORDER.equals(sort))) {
            throw new InvalidFinanceEntryException("Supported order is transactionDate DESC, id DESC");
        }
        return PageRequest.of(pageNumber, pageSize, LIST_ORDER);
    }

    private void validateExpectedVersion(LedgerEntry entry, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw new InvalidFinanceEntryException("A nonnegative expected version is required");
        }
        if (!expectedVersion.equals(entry.getVersion())) {
            throw new FinanceEntryConflictException();
        }
    }

    private void flushEntryChanges() {
        try {
            // Execute the versioned UPDATE inside the service transaction, before returning.
            entryRepository.flush();
        } catch (OptimisticLockingFailureException | OptimisticLockException ex) {
            throw new FinanceEntryConflictException(ex);
        }
    }

    private LedgerEntry findOwnedEntry(UUID currentUserId, UUID entryId) {
        if (currentUserId == null || entryId == null) throw new FinanceEntryNotFoundException();
        return entryRepository.findByIdAndUserIdAndDeletedAtIsNull(entryId, currentUserId)
                .orElseThrow(FinanceEntryNotFoundException::new);
    }

    private Money validateFields(EntryType entryType, BigDecimal amount, String currencyCode,
            LocalDate transactionDate, String description) {
        if (entryType == null || transactionDate == null || amount == null
                || description == null || description.length() > 500) {
            throw new InvalidFinanceEntryException("Required entry fields are missing or invalid");
        }
        try {
            Money money = new Money(amount, currencyCode);
            Currency.getInstance(money.currencyCode());
            return money;
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new InvalidFinanceEntryException("Invalid amount or currency");
        }
    }

    private void validateAccountAndCategory(UUID currentUserId, UUID accountId, UUID categoryId,
            EntryType entryType, Money money) {
        var account = accountRepository.findByIdAndUserId(accountId, currentUserId)
                .orElseThrow(() -> new InvalidFinanceEntryException("Invalid account"));
        if (!account.getActive()) throw new InvalidFinanceEntryException("Inactive account");
        if (!account.getCurrencyCode().equals(money.currencyCode())) {
            throw new InvalidFinanceEntryException("Currency must match account currency");
        }
        if (categoryId != null) {
            var category = categoryRepository.findByIdAndUserId(categoryId, currentUserId)
                    .orElseThrow(() -> new InvalidFinanceEntryException("Invalid category"));
            if (!category.getActive() || category.getEntryType() != entryType) {
                throw new InvalidFinanceEntryException("Inactive category or incompatible entry type");
            }
        }
    }
}
