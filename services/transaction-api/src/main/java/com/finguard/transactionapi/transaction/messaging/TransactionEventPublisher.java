package com.finguard.transactionapi.transaction.messaging;
public interface TransactionEventPublisher {
    void publish(TransactionCreatedEvent event);
}
