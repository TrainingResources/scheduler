package com.example.schedulerbenchmark.benchmark;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BenchmarkSeedService {
    private static final String SCHEDULE_JSON = """
            {"rules":[{"ruleId":"rule-1","cron":"0 * * * * *","enabled":true}]}
            """;

    private final BenchmarkRedisService redis;
    private final BenchmarkProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public BenchmarkSeedService(BenchmarkRedisService redis, BenchmarkProperties properties, JdbcTemplate jdbcTemplate) {
        this.redis = redis;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public SeedResponse seed(SeedRequest request) {
        return seed(request, ignored -> {
        });
    }

    @Transactional
    public SeedResponse seed(SeedRequest request, LongConsumer progressConsumer) {
        int ruleCount = request.effectiveRuleCountPerUserKey();
        int pipelineSize = properties.seed().redisPipelineSize();
        List<BenchmarkRedisService.SeedJob> buffer = new ArrayList<>(pipelineSize);
        long postgresRows = seedPostgresRows(request, ruleCount);
        String namespace = request.effectiveNamespace();
        long startIndex = request.effectiveStartIndex();

        for (long i = 0; i < request.totalJobs(); i++) {
            long sequence = startIndex + i;
            long userId = (sequence / ruleCount) + 1;
            String notificationKey = namespace + "-" + (sequence / ruleCount);
            String ruleId = "rule-" + ((sequence % ruleCount) + 1);
            String member = userId + ":" + notificationKey + ":" + ruleId;
            boolean recurring = isRecurring(i, request.recurringPercent());
            buffer.add(new BenchmarkRedisService.SeedJob(member, request.dueAtUtcMillis(), Map.of(
                    "userId", Long.toString(userId),
                    "notificationKey", notificationKey,
                    "ruleId", ruleId,
                    "executionTimeUtcMillis", Long.toString(request.dueAtUtcMillis()),
                    "version", "1",
                    "recurring", Boolean.toString(recurring))));
            if (buffer.size() >= pipelineSize) {
                redis.seedJobs(buffer);
                progressConsumer.accept(buffer.size());
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            redis.seedJobs(buffer);
            progressConsumer.accept(buffer.size());
        }
        return new SeedResponse(request.totalJobs(), postgresRows, request.dueAtUtcMillis());
    }

    @Transactional
    public LoadTestPrepareResponse prepareFiveMinuteLoadTest(LoadTestPrepareRequest request) {
        long uniquePerMinute = request.effectiveUniquePerMinute();
        long recurringPerMinute = request.effectiveRecurringPerMinute();
        int durationMinutes = request.effectiveDurationMinutes();
        String namespace = request.effectiveNamespace();
        long baseDueAtUtcMillis = (Instant.now().getEpochSecond() + request.effectiveStartDelaySeconds()) * 1000;
        long postgresRows = 0;

        postgresRows += seed(new SeedRequest(
                recurringPerMinute,
                baseDueAtUtcMillis,
                100,
                null,
                namespace + "-recurring",
                0L)).postgresRowsInserted();

        for (int minute = 0; minute < durationMinutes; minute++) {
            postgresRows += seed(new SeedRequest(
                    uniquePerMinute,
                    baseDueAtUtcMillis + (minute * 60_000L),
                    0,
                    null,
                    namespace + "-unique",
                    minute * uniquePerMinute)).postgresRowsInserted();
        }

        long expectedUniqueExecutions = uniquePerMinute * durationMinutes;
        long expectedRecurringExecutions = recurringPerMinute * durationMinutes;
        return new LoadTestPrepareResponse(
                namespace,
                baseDueAtUtcMillis,
                baseDueAtUtcMillis / 1000,
                (baseDueAtUtcMillis / 1000) + (durationMinutes * 60L),
                expectedUniqueExecutions + recurringPerMinute,
                expectedUniqueExecutions,
                expectedRecurringExecutions,
                expectedUniqueExecutions + expectedRecurringExecutions,
                postgresRows);
    }

    private boolean isRecurring(long index, int recurringPercent) {
        return recurringPercent > 0 && Math.floorMod(index, 100) < recurringPercent;
    }

    private long seedPostgresRows(SeedRequest request, int ruleCount) {
        int maxRows = properties.seed().postgresMaxRows();
        long distinctUserKeys = Math.ceilDiv(request.totalJobs(), ruleCount);
        long rowsToInsert = Math.min(maxRows, distinctUserKeys);
        Timestamp now = Timestamp.from(Instant.now());
        String namespace = request.effectiveNamespace();
        long startIndex = request.effectiveStartIndex();
        for (long i = 0; i < rowsToInsert; i++) {
            long sequence = startIndex + (i * ruleCount);
            jdbcTemplate.update("""
                    INSERT INTO user_notification_schedule
                      (user_id, notification_key, schedule_config_json, enabled, version, created_at, updated_at)
                    VALUES (?, ?, ?, true, 1, ?, ?)
                    ON CONFLICT (user_id, notification_key)
                    DO UPDATE SET schedule_config_json = EXCLUDED.schedule_config_json,
                                  enabled = EXCLUDED.enabled,
                                  version = user_notification_schedule.version + 1,
                                  updated_at = EXCLUDED.updated_at
                    """,
                    (sequence / ruleCount) + 1,
                    namespace + "-" + (sequence / ruleCount),
                    SCHEDULE_JSON,
                    now,
                    now);
        }
        return rowsToInsert;
    }
}
