package com.example.schedulerbenchmark.benchmark;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SeedRequest(
        @Positive long totalJobs,
        @NotNull Long dueAtUtcMillis,
        @Min(0) @Max(100) int recurringPercent,
        @Min(1) Integer ruleCountPerUserKey,
        String namespace,
        @Min(0) Long startIndex) {

    int effectiveRuleCountPerUserKey() {
        return ruleCountPerUserKey == null ? 1 : ruleCountPerUserKey;
    }

    String effectiveNamespace() {
        return namespace == null || namespace.isBlank() ? "benchmark" : namespace;
    }

    long effectiveStartIndex() {
        return startIndex == null ? 0 : startIndex;
    }
}
