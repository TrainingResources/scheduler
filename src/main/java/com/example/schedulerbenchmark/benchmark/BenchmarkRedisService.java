package com.example.schedulerbenchmark.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkRedisService {
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local jobs = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            if #jobs > 0 then
              redis.call('ZREM', KEYS[1], unpack(jobs))
            end
            return jobs
            """, List.class);

    private final StringRedisTemplate redis;
    private final BenchmarkProperties properties;

    public BenchmarkRedisService(StringRedisTemplate redis, BenchmarkProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String jobKey(String member) {
        return properties.redis().jobKeyPrefix() + member;
    }

    @SuppressWarnings("unchecked")
    public List<String> claimDue(long nowUtcMillis, int batchSize) {
        List<?> result = redis.execute(CLAIM_SCRIPT, List.of(properties.redis().dueKey()),
                Long.toString(nowUtcMillis), Integer.toString(batchSize));
        if (result == null || result.isEmpty()) {
            return List.of();
        }
        return result.stream().map(String.class::cast).toList();
    }

    public List<Object> readJobs(List<String> members) {
        return redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(org.springframework.data.redis.core.RedisOperations operations)
                    throws DataAccessException {
                for (String member : members) {
                    operations.opsForHash().entries(jobKey(member));
                }
                return null;
            }
        });
    }

    public void publishJobs(List<Map<String, String>> messages) {
        redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(org.springframework.data.redis.core.RedisOperations operations)
                    throws DataAccessException {
                for (Map<String, String> message : messages) {
                    operations.opsForStream().add(MapRecord.create(properties.redis().streamKey(), message));
                }
                return null;
            }
        });
    }

    public void seedJobs(List<SeedJob> jobs) {
        redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(org.springframework.data.redis.core.RedisOperations operations)
                    throws DataAccessException {
                for (SeedJob job : jobs) {
                    operations.opsForHash().putAll(jobKey(job.member()), job.hash());
                    operations.opsForZSet().add(properties.redis().dueKey(), job.member(), job.dueAtUtcMillis());
                }
                return null;
            }
        });
    }

    public void reschedule(List<RescheduleJob> jobs) {
        redis.executePipelined(new SessionCallback<>() {
            @Override
            public Object execute(org.springframework.data.redis.core.RedisOperations operations)
                    throws DataAccessException {
                for (RescheduleJob job : jobs) {
                    operations.opsForHash().put(jobKey(job.member()), "executionTimeUtcMillis",
                            Long.toString(job.nextExecutionTimeUtcMillis()));
                    operations.opsForZSet().add(properties.redis().dueKey(), job.member(),
                            job.nextExecutionTimeUtcMillis());
                }
                return null;
            }
        });
    }

    public void createConsumerGroupIfMissing() {
        byte[] streamKey = properties.redis().streamKey().getBytes(StandardCharsets.UTF_8);
        redis.execute((RedisCallback<Void>) connection -> {
            try {
                connection.streamCommands().xGroupCreate(
                        streamKey, properties.redis().streamGroup(), ReadOffset.from("0-0"), true);
            } catch (RuntimeException ex) {
                if (ex.getMessage() == null || !ex.getMessage().contains("BUSYGROUP")) {
                    throw ex;
                }
            }
            return null;
        });
    }

    public List<MapRecord<String, Object, Object>> readFromGroup(String consumerName, int batchSize, long blockMs) {
        return redis.opsForStream().read(
                org.springframework.data.redis.connection.stream.Consumer.from(
                        properties.redis().streamGroup(), consumerName),
                org.springframework.data.redis.connection.stream.StreamReadOptions.empty()
                        .count(batchSize)
                        .block(java.time.Duration.ofMillis(blockMs)),
                StreamOffset.create(properties.redis().streamKey(), ReadOffset.lastConsumed()));
    }

    public long acknowledge(List<RecordId> recordIds) {
        if (recordIds.isEmpty()) {
            return 0;
        }
        Long acked = redis.opsForStream().acknowledge(
                properties.redis().streamKey(),
                properties.redis().streamGroup(),
                recordIds.toArray(RecordId[]::new));
        return acked == null ? 0 : acked;
    }

    public BenchmarkStats stats(BenchmarkCounters counters) {
        Long dueZsetSize = redis.opsForZSet().zCard(properties.redis().dueKey());
        Long streamLength = redis.opsForStream().size(properties.redis().streamKey());
        Long pending = null;
        try {
            PendingMessagesSummary summary = redis.opsForStream().pending(
                    properties.redis().streamKey(), properties.redis().streamGroup());
            pending = summary == null ? null : summary.getTotalPendingMessages();
        } catch (RuntimeException ignored) {
            pending = null;
        }
        return counters.snapshot(pending, dueZsetSize, streamLength);
    }

    public void resetKeys() {
        redis.delete(properties.redis().dueKey());
        redis.delete(properties.redis().streamKey());
        deleteJobKeys();
    }

    private void deleteJobKeys() {
        redis.execute((RedisCallback<Void>) connection -> {
            RedisConnection cursorConnection = connection;
            try (var cursor = cursorConnection.keyCommands().scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(properties.redis().jobKeyPrefix() + "*")
                            .count(10_000)
                            .build())) {
                while (cursor.hasNext()) {
                    connection.keyCommands().del(cursor.next());
                }
            }
            return null;
        });
    }

    public record SeedJob(String member, long dueAtUtcMillis, Map<String, String> hash) {
    }

    public record RescheduleJob(String member, long nextExecutionTimeUtcMillis) {
    }
}
