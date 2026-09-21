package com.finguard.transactionapi.transaction.service;

import com.finguard.transactionapi.transaction.repository.TransactionRepository;
import com.finguard.transactionapi.transaction.domain.Transaction;
import com.finguard.transactionapi.transaction.dto.CreateTransactionRequest;
import com.finguard.transactionapi.transaction.exception.TransactionNotFoundException;
import org.springframework.stereotype.Service;
import com.finguard.transactionapi.transaction.dto.TransactionResponse;
import com.finguard.transactionapi.transaction.messaging.TransactionCreatedEvent;
import com.finguard.transactionapi.transaction.messaging.TransactionEventPublisher;
import java.util.UUID;

@Service
public class TransactionService{
    private final TransactionRepository repository;
    private final TransactionEventPublisher eventPublisher;

    // Constructor Injection
    public TransactionService(TransactionRepository repository, TransactionEventPublisher eventPublisher){
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public TransactionResponse createTransaction(CreateTransactionRequest request){
            Transaction createdTransaction = repository.save(new Transaction(request.amount(), request.currency()));
            eventPublisher.publish(new TransactionCreatedEvent(
                    createdTransaction.getId(),
                    createdTransaction.getAmount(),
                    createdTransaction.getCurrency()
            ));
            return toResponse(createdTransaction);
        }

    //Debit: could throw exception when there is empty return here
    public TransactionResponse findById(UUID id) {
        return repository.findById(id)
                .map(transaction -> toResponse(transaction))
                .orElseThrow(() ->
                        new TransactionNotFoundException(
                                "Transaction with id " + id + " was not found"
                        )
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