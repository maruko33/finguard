package com.finguard.transactionapi.finance.dto;

/** Confirm only the decisions already saved in the database. */
public record ConfirmImportRequest(Long expectedBatchVersion) {}
