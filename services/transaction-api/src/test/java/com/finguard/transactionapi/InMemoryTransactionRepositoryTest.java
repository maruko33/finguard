package com.finguard.transactionapi.transaction;

import com.finguard.transactionapi.transaction.domain.Transaction;
import com.finguard.transactionapi.transaction.repository.InMemoryTransactionRepository;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTransactionRepositoryTest {

    @Test
    void saveAndFindById() {

        InMemoryTransactionRepository repository =
                new InMemoryTransactionRepository();

        Transaction transaction =
                new Transaction(
                        new BigDecimal("100.00"),
                        "CAD"
                );

        repository.save(transaction);

        var result =
                repository.findById(transaction.getId());

        assertTrue(result.isPresent());
        assertEquals(
                transaction.getId(),
                result.get().getId()
        );
    }
}