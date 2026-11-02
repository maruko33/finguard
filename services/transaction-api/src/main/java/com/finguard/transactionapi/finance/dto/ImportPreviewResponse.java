package com.finguard.transactionapi.finance.dto;

import com.finguard.transactionapi.finance.domain.ImportStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;

public record ImportPreviewResponse(
        UUID id,
        UUID accountId,
        String fileName,
        ImportStatus status,
        long version,
        Page<CandidateSummary> candidates) {
}
