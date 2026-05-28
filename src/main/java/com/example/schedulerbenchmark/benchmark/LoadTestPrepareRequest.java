package com.example.schedulerbenchmark.benchmark;

import jakarta.validation.constraints.Min;

public record LoadTestPrepareRequest(
        @Min(1) Long uniquePerMinute,
        @Min(1) Long recurringPerMinute,
        @Min(1) Integer durationMinutes,
        @Min(0) Long startDelaySeconds,
        String namespace) {

    long effectiveUniquePerMinute() {
        return uniquePerMinute == null ? 500_000 : uniquePerMinute;
    }

    long effectiveRecurringPerMinute() {
        return recurringPerMinute == null ? 500_000 : recurringPerMinute;
    }

    int effectiveDurationMinutes() {
        return durationMinutes == null ? 5 : durationMinutes;
    }

    long effectiveStartDelaySeconds() {
        return startDelaySeconds == null ? 300 : startDelaySeconds;
    }

    String effectiveNamespace() {
        return namespace == null || namespace.isBlank()
                ? "loadtest-" + java.time.Instant.now().toString().replaceAll("[^0-9]", "")
                : namespace;
    }
}
