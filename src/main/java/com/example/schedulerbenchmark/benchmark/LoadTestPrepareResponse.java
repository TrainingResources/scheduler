package com.example.schedulerbenchmark.benchmark;

public record LoadTestPrepareResponse(
        String namespace,
        long baseDueAtUtcMillis,
        long startEpochSeconds,
        long stopBeforeEpochSeconds,
        long initialRedisJobs,
        long expectedUniqueExecutions,
        long expectedRecurringExecutions,
        long expectedTotalExecutions,
        long postgresRowsInserted) {
}
