package com.finguard.transactionapi.transaction;

import com.finguard.transactionapi.transaction.controller.TransactionController;
import com.finguard.transactionapi.transaction.dto.CreateTransactionRequest;
import com.finguard.transactionapi.transaction.repository.InMemoryTransactionRepository;
import com.finguard.transactionapi.transaction.service.TransactionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class TransactionControllerTest {

    private TransactionController controller;

    @BeforeEach
    void setUp() {

        TransactionService service =
                new TransactionService(
                        new InMemoryTransactionRepository()
                );

        controller =
                new TransactionController(service);
    }

    @Test
    void createTransactionReturns201() {

        var request =
                new CreateTransactionRequest(
                        new BigDecimal("125.50"),
                        "CAD"
                );

        var response =
                controller.createTransaction(request);

        assertEquals(
                HttpStatus.CREATED,
                response.getStatusCode()
        );

        assertNotNull(response.getBody());
    }
}