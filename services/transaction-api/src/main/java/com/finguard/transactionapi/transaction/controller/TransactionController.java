package com.finguard.transactionapi.transaction.controller;

import com.finguard.transactionapi.transaction.dto.TransactionResponse;
import com.finguard.transactionapi.transaction.dto.CreateTransactionRequest;
import com.finguard.transactionapi.transaction.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;



@RestController
@RequestMapping("/transactions")
public class TransactionController{

    private final TransactionService service;

    //Constructor Injection
    public TransactionController(TransactionService service){
        this.service = service;
    }

    @PostMapping
    public TransactionResponse CreateTransaction(
        @Valid @RequestBody CreateTransactionRequest request){
        return service.createTransaction(request);
    }
}