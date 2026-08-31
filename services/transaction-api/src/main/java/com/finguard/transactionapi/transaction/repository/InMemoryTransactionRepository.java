package com.finguard.transactionapi.transaction.repository;

import com.finguard.transactionapi.transaction.domain.Transaction;

import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InMemoryTransactionRepository
        implements TransactionRepository {

    private final Map<UUID, Transaction> transactions =
            new HashMap<>();

    @Override
    public Transaction save(Transaction transaction) {
        transactions.put(
                transaction.getId(),
                transaction
        );

        return transaction;
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return Optional.ofNullable(
                transactions.get(id)
        );
    }
}