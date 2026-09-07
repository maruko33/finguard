package com.finguard.transactionapi.transaction.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataTransactionRepository extends JpaRepository<TransactionEntity, UUID>{
    
}

//Spring Data JPA creates the implementation at runtime
//you dont need to write save()、findById()、findAll()、deleteById()