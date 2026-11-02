package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.ImportStatus;
import java.util.UUID;

public record ImportReviewResult(UUID batchId, ImportStatus status, long version, int changedCandidateCount) {}
