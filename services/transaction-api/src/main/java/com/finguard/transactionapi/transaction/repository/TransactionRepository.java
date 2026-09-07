package com.finguard.transactionapi.transaction.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.finguard.transactionapi.transaction.domain.Transaction;
import com.finguard.transactionapi.transaction.persistence.TransactionEntity;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {
    
    Transaction save(Transaction transaction);
    Optional<Transaction> findById(UUID id);

}


