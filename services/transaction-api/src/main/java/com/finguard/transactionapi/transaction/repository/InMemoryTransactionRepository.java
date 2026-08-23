package com.finguard.transactionapi.transaction.repository;

import com.finguard.transactionapi.transaction.domain.Transaction;

import java.util.Optional;
import java.util.UUID;

public class InMemoryTransactionRepository implements TransactionRepository{
    

    public Transaction save(Transaction transaction){
        return null;
    }

    public Optional<Transaction> findById(UUID id){
        return null;
    }
}
