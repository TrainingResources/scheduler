package com.example.schedulerbenchmark.benchmark;

public record LoadTestPrepareJobStatus(
        boolean running,
        boolean completed,
        boolean failed,
        String message,
        String namespace,
        int wavesCompleted,
        int wavesTotal,
        long jobsSeeded,
        long initialRedisJobs,
        long expectedTotalExecutions,
        Long baseDueAtUtcMillis,
        Long startEpochSeconds,
        Long stopBeforeEpochSeconds) {
}
