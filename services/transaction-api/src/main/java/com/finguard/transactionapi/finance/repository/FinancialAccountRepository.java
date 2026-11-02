package com.finguard.transactionapi.finance.repository;

import com.finguard.transactionapi.finance.domain.FinancialAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialAccountRepository extends JpaRepository<FinancialAccount, UUID> {
    // NO KEY UPDATE serializes same-account confirmations and blocks active/currency changes.
    @Query(value = """
            SELECT id AS "id", user_id AS "userId", active AS "active", currency_code AS "currencyCode"
            FROM {h-schema}financial_account
            WHERE id = :accountId AND user_id = :currentUserId
            FOR NO KEY UPDATE
            """, nativeQuery = true)
    Optional<ConfirmationAccount> findOwnedForConfirmation(
            @Param("accountId") UUID accountId, @Param("currentUserId") UUID currentUserId);

    interface ConfirmationAccount {
        UUID getId();
        UUID getUserId();
        boolean getActive();
        String getCurrencyCode();
    }

    Optional<FinancialAccount> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM FinancialAccount a WHERE a.id = :accountId AND a.userId = :currentUserId")
    Optional<FinancialAccount> findOwnedForImport(
            @Param("accountId") UUID accountId, @Param("currentUserId") UUID currentUserId);
}
