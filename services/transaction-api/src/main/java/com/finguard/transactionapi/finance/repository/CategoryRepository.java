package com.finguard.transactionapi.finance.repository;

import com.finguard.transactionapi.finance.domain.Category;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    // SHARE protects non-key active/type fields through commit; order prevents opposing lock orders.
    @Query(value = """
            SELECT id AS "id", user_id AS "userId", entry_type AS "entryType", active AS "active"
            FROM {h-schema}category
            WHERE user_id = :currentUserId AND id IN (:categoryIds)
            ORDER BY id
            FOR SHARE
            """, nativeQuery = true)
    List<ConfirmationCategory> findForConfirmation(
            @Param("currentUserId") UUID currentUserId, @Param("categoryIds") List<UUID> categoryIds);

    interface ConfirmationCategory {
        UUID getId();
        UUID getUserId();
        String getEntryType();
        boolean getActive();
    }

    Optional<Category> findByIdAndUserId(UUID id, UUID userId);

    List<Category> findByUserIdAndIdIn(UUID userId, List<UUID> ids);
}
