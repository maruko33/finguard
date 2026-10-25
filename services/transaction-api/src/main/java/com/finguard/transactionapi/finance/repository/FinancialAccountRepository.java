package com.finguard.transactionapi.finance.repository;

import com.finguard.transactionapi.finance.domain.FinancialAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {
    Optional<FinancialAccount> findByIdAndUserId(UUID id, UUID userId);
}
