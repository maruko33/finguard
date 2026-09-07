package com.finguard.transactionapi.transaction.controller;

import com.finguard.transactionapi.transaction.dto.TransactionResponse;
import com.finguard.transactionapi.transaction.dto.CreateTransactionRequest;
import com.finguard.transactionapi.transaction.service.TransactionService;


import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity;
import java.util.UUID;


@RestController
@RequestMapping("/transactions")
public class TransactionController{

    private final TransactionService service;

    //Constructor Injection
    public TransactionController(TransactionService service){
        this.service = service;
    }


        @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request) {

        TransactionResponse response =
                service.createTransaction(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

        @GetMapping("/{id}")
    public TransactionResponse findTransactionById(
            @PathVariable UUID id) {

        return service.findById(id);
    }
}