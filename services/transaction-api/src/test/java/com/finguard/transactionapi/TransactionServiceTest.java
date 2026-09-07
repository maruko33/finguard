package com.finguard.transactionapi.transaction;

import com.finguard.transactionapi.transaction.domain.TransactionStatus;
import com.finguard.transactionapi.transaction.dto.CreateTransactionRequest;
import com.finguard.transactionapi.transaction.exception.TransactionNotFoundException;
import com.finguard.transactionapi.transaction.repository.InMemoryTransactionRepository;
import com.finguard.transactionapi.transaction.service.TransactionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionServiceTest {

    private TransactionService service;

    @BeforeEach
    void setUp() {
        service = new TransactionService(
                new InMemoryTransactionRepository()
        );
    }

    @Test
    void createTransactionStartsAsReceived() {

        var request =
                new CreateTransactionRequest(
                        new BigDecimal("125.50"),
                        "CAD"
                );

        var response =
                service.createTransaction(request);

        assertNotNull(response.id());
        assertEquals(
                TransactionStatus.RECEIVED,
                response.status()
        );
        assertEquals(
                new BigDecimal("125.50"),
                response.amount()
        );
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
