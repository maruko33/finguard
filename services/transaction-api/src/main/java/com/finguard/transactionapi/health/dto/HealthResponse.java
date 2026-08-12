package com.finguard.transactionapi.health.dto;

public record HealthResponse(
        String status,
        String service
) {
}