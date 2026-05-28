package com.example.schedulerbenchmark.benchmark;

public record SeedResponse(long redisJobsSeeded, long postgresRowsInserted, long dueAtUtcMillis) {
}
