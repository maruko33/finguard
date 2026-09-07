package com.finguard.transactionapi.transaction.domain;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;

public class Transaction{
    private final UUID id;
    private BigDecimal amount;
    private String currency;
    private TransactionStatus status;
    private final Instant createdAt;

    public Transaction(BigDecimal amount, String currency) {

        this.id = UUID.randomUUID();
        this.amount = amount;
        this.currency = currency;
        this.status = TransactionStatus.RECEIVED;
        this.createdAt = Instant.now();
    }

    public Transaction(UUID id, BigDecimal amount, String currency, TransactionStatus status, Instant createAt){
        this.id = id;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createAt;
    }
    public UUID getId() {
        return id;
    }
    
    public BigDecimal getAmount(){
        return amount;
    }

    public String getCurrency(){
        return currency;
    }

    public TransactionStatus getStatus(){
        return status;
    }

    public Instant getCreatedAt(){
        return createdAt;
    }

}