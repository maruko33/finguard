package com.finguard.transactionapi.finance.dto;

import java.util.List;

/** Full replacement of review decisions for only the listed Candidates. */
public record SaveImportReviewRequest(Long expectedBatchVersion, List<CandidateReviewDecision> decisions) {}
