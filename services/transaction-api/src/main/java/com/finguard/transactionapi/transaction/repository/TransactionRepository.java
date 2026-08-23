package com.finguard.transactionapi.transaction.repository;

import com.finguard.transactionapi.transaction.domain.Transaction;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {
    
    Transaction save(Transaction transaction);
    Optional<Transaction> findById(UUID id);

}
