package com.finguard.transactionapi.transaction.persistence;

import com.finguard.transactionapi.transaction.domain.TransactionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class TransactionEntity {
    
    @Id
    private UUID id;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;
    
    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TransactionEntity(){}

    public TransactionEntity(
            UUID id,
            BigDecimal amount,
            String currency,
            TransactionStatus status,
            Instant createdAt) {
        this.id = id;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
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
