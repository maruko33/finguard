package com.finguard.transactionapi.transaction.service;

import com.finguard.transactionapi.transaction.repository.TransactionRepository;
import com.finguard.transactionapi.transaction.domain.Transaction;
import com.finguard.transactionapi.transaction.dto.CreateTransactionRequest;
import org.springframework.stereotype.Service;
import com.finguard.transactionapi.transaction.dto.TransactionResponse;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionService{
    private final TransactionRepository repository;

    // Constructor Injection
    public TransactionService(TransactionRepository repository){
        this.repository = repository;
    }

    public TransactionResponse createTransaction(CreateTransactionRequest request){
            Transaction createdTransaction = repository.save(new Transaction(request.amount(), request.currency()));
            return new TransactionResponse(
                createdTransaction.getId(),
                createdTransaction.getAmount(),
                createdTransaction.getCurrency(),
                createdTransaction.getStatus(),
                createdTransaction.getCreatedAt()
            );
        }

    //Debit: could throw exception when there is empty return here
    public Optional<Transaction> findById(UUID id){
        return repository.findById(id);
    }
}