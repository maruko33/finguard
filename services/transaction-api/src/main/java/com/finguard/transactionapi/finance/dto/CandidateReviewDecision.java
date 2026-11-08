package com.finguard.transactionapi.finance.dto;

import java.util.UUID;

/** Null category clears it; an acknowledgement binds to the opaque saved evidence signature. */
public record CandidateReviewDecision(UUID candidateId, Boolean selectedForImport, UUID categoryId,
                                     Boolean duplicateAcknowledged, String expectedDuplicateMatchSignature) {}
