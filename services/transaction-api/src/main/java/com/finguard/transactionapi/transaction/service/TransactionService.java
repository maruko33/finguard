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
            return toResponse(createdTransaction);
        }

    //Debit: could throw exception when there is empty return here
    public Optional<TransactionResponse> findById(UUID id){
        return repository.findById(id).map(transaction -> toResponse(transaction)
        );
    }

    private TransactionResponse toResponse(Transaction transaction){
        return new TransactionResponse(
            transaction.getId(),
            transaction.getAmount(),
            transaction.getCurrency(),
            transaction.getStatus(),
            transaction.getCreatedAt()
        );        
    }
}