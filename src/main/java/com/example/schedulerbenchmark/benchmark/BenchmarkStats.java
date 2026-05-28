package com.example.schedulerbenchmark.benchmark;

public record BenchmarkStats(
        long claimedTotal,
        long publishedTotal,
        long consumedTotal,
        long ackedTotal,
        long rescheduledTotal,
        double claimRatePerSecond,
        double publishRatePerSecond,
        double consumeRatePerSecond,
        Long pendingStreamMessages,
        Long dueZsetSize,
        Long streamLength) {
}
