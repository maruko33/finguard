package com.finguard.transactionapi.transaction.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import com.finguard.transactionapi.transaction.persistence.SpringDataTransactionRepository;
import com.finguard.transactionapi.transaction.domain.Transaction;
import com.finguard.transactionapi.transaction.persistence.TransactionEntity;
import com.finguard.transactionapi.transaction.repository.TransactionRepository;

@Repository
public class PostgresTransactionRepository implements TransactionRepository {
    private final SpringDataTransactionRepository jpaRepository;

    public PostgresTransactionRepository(
            SpringDataTransactionRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }    

    @Override
    public Transaction save(Transaction transaction) {

        TransactionEntity entity = new TransactionEntity(
                transaction.getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );

        jpaRepository.save(entity);

        return transaction;
    }

    @Override 
    public Optional<Transaction>findById(UUID id){
        return jpaRepository.findById(id)
                .map(te -> new Transaction(
                        te.getId(),
                        te.getAmount(),
                        te.getCurrency(),
                        te.getStatus(),
                        te.getCreatedAt()
                ));
    }

}
