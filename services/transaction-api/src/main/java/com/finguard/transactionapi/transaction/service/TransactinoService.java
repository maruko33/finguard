package com.finguard.transactionapi.transaction.service;

import com.finguard.transactionapi.transaction.repository.TransactionRepository;

import org.springframework.stereotype.Service;

@Service
public class TransactionService{
    private final TransactionRepository repository;

    // Constructor Injection
    public TransactionService(TransactionRepository repository){
        this.repository = repository;
    }

    //Next thing to do, writing function c
}