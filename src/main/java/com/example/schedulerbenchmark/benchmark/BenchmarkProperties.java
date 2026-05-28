package com.example.schedulerbenchmark.benchmark;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "benchmark")
public record BenchmarkProperties(
        String nodeId,
        boolean autoStart,
        RedisKeys redis,
        Scheduler scheduler,
        Stream stream,
        Seed seed) {

    public record RedisKeys(String dueKey, String jobKeyPrefix, String streamKey, String streamGroup) {
    }

    public record Scheduler(boolean enabled, Claim claim, long pollIntervalMs) {
        public record Claim(int batchSize) {
        }
    }

    public record Stream(Consumer consumer) {
        public record Consumer(boolean enabled, int threads, int batchSize, long blockMs) {
        }
    }

    public record Seed(int postgresMaxRows, int redisPipelineSize) {
    }
}
