package com.finguard.transactionapi.finance.repository;

import com.finguard.transactionapi.finance.domain.Category;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<Category> findByIdAndUserId(UUID id, UUID userId);
}
