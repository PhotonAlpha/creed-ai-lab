package com.creed.resource.migrationrisk.api.dto;

import com.creed.resource.migrationrisk.domain.RiskCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** {@code category} null = the whole file; {@code scope} null = every project (else a repo1 path or URL). */
public record IgnoreRuleView(long id, String pattern, RiskCategory category, String scope, String note,
                             Instant createdAt) {

    public record Create(@NotBlank @Size(max = 500) String pattern, RiskCategory category,
                         @Size(max = 1000) String scope, @Size(max = 1000) String note) {
    }
}
