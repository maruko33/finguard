package com.finguard.transactionapi;

import com.finguard.transactionapi.transaction.service.TransactionService;
import com.finguard.transactionapi.transaction.domain.Transaction;
import com.finguard.transactionapi.transaction.dto.CreateTransactionRequest;
import com.finguard.transactionapi.transaction.dto.TransactionResponse;
import com.finguard.transactionapi.transaction.exception.TransactionNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.finguard.transactionapi.transaction.repository.TransactionRepository;
import com.finguard.transactionapi.transaction.messaging.TransactionCreatedEvent;
import com.finguard.transactionapi.transaction.messaging.TransactionEventPublisher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionServiceTest {

        private TransactionRepository repository;
        private TransactionEventPublisher eventPublisher;
        private TransactionService service;

        @BeforeEach
        void setUp() {
        repository = mock(TransactionRepository.class);
        eventPublisher = mock(TransactionEventPublisher.class);

        service = new TransactionService(
                repository,
                eventPublisher
        );
        }

        @Test
        void shouldCreateTransactionAndPublishEvent() {

        CreateTransactionRequest request =
                new CreateTransactionRequest(
                        new BigDecimal("6000.00"),
                        "CAD"
                );

        when(repository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse response =
                service.createTransaction(request);

        assertEquals(new BigDecimal("6000.00"), response.amount());
        assertEquals("CAD", response.currency());

        verify(repository).save(any(Transaction.class));

        ArgumentCaptor<TransactionCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(TransactionCreatedEvent.class);

        verify(eventPublisher).publish(eventCaptor.capture());

        TransactionCreatedEvent publishedEvent =
                eventCaptor.getValue();

        assertEquals(response.id(), publishedEvent.transactionId());
        assertEquals(response.amount(), publishedEvent.amount());
        assertEquals(response.currency(), publishedEvent.currency());
        }

    @Test
    void findMissingTransactionThrowsException() {

        UUID missingId = UUID.randomUUID();

        assertThrows(
                TransactionNotFoundException.class,
                () -> service.findById(missingId)
        );
    }
}
