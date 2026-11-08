package com.finguard.transactionapi.finance.repository;

import com.finguard.transactionapi.finance.domain.LedgerEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, JpaSpecificationExecutor<LedgerEntry> {
    Optional<LedgerEntry> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);
}
