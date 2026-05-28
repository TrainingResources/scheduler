package com.example.schedulerbenchmark.benchmark;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkRuntimeService.class);

    private final BenchmarkProperties properties;
    private final BenchmarkRedisService redis;
    private final BenchmarkCounters counters;
    private final Clock clock = Clock.systemUTC();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public BenchmarkRuntimeService(BenchmarkProperties properties, BenchmarkRedisService redis,
            BenchmarkCounters counters) {
        this.properties = properties;
        this.redis = redis;
        this.counters = counters;
    }

    @PostConstruct
    void startIfConfigured() {
        if (properties.autoStart()) {
            start();
        }
    }

    @PreDestroy
    void shutdown() {
        stop();
        executor.shutdownNow();
    }

    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        redis.createConsumerGroupIfMissing();
        if (properties.scheduler().enabled()) {
            executor.submit(this::schedulerLoop);
        }
        if (properties.stream().consumer().enabled()) {
            for (int i = 0; i < properties.stream().consumer().threads(); i++) {
                int consumerIndex = i;
                executor.submit(() -> consumerLoop(consumerIndex));
            }
        }
        log.info("Benchmark loops started nodeId={}", properties.nodeId());
        return true;
    }

    public boolean stop() {
        boolean changed = running.getAndSet(false);
        if (changed) {
            log.info("Benchmark loops stopped nodeId={}", properties.nodeId());
        }
        return changed;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void schedulerLoop() {
        int batchSize = properties.scheduler().claim().batchSize();
        long idleMillis = properties.scheduler().pollIntervalMs();
        while (running.get()) {
            try {
                List<String> members = redis.claimDue(clock.millis(), batchSize);
                if (members.isEmpty()) {
                    sleep(idleMillis);
                    continue;
                }
                counters.addClaimed(members.size());
                publishClaimed(members);
            } catch (RuntimeException ex) {
                log.warn("Scheduler loop iteration failed: {}", ex.getMessage(), ex);
                sleep(Math.max(100, idleMillis));
            }
        }
    }

    private void publishClaimed(List<String> members) {
        List<Object> hashResults = redis.readJobs(members);
        List<Map<String, String>> messages = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> hash = (Map<Object, Object>) hashResults.get(i);
            if (hash == null || hash.isEmpty()) {
                continue;
            }
            Map<String, String> message = new HashMap<>();
            message.put("member", members.get(i));
            message.put("nodeId", properties.nodeId());
            message.put("claimedAtUtcMillis", Long.toString(clock.millis()));
            putHashValue(hash, message, "userId");
            putHashValue(hash, message, "notificationKey");
            putHashValue(hash, message, "ruleId");
            putHashValue(hash, message, "executionTimeUtcMillis");
            putHashValue(hash, message, "version");
            putHashValue(hash, message, "recurring");
            messages.add(message);
        }
        if (!messages.isEmpty()) {
            redis.publishJobs(messages);
            counters.addPublished(messages.size());
        }
    }

    private void putHashValue(Map<Object, Object> hash, Map<String, String> message, String field) {
        Object value = hash.get(field);
        if (value != null) {
            message.put(field, value.toString());
        }
    }

    private void consumerLoop(int consumerIndex) {
        String consumerName = properties.nodeId() + "-" + consumerIndex + "-" + UUID.randomUUID();
        int batchSize = properties.stream().consumer().batchSize();
        long blockMs = properties.stream().consumer().blockMs();
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.readFromGroup(consumerName, batchSize, blockMs);
                if (records == null || records.isEmpty()) {
                    continue;
                }
                counters.addConsumed(records.size());
                rescheduleRecurring(records);
                List<RecordId> ids = records.stream().map(MapRecord::getId).toList();
                long acked = redis.acknowledge(ids);
                counters.addAcked(acked);
            } catch (RuntimeException ex) {
                log.warn("Consumer loop iteration failed: {}", ex.getMessage(), ex);
                sleep(250);
            }
        }
    }

    private void rescheduleRecurring(List<MapRecord<String, Object, Object>> records) {
        List<BenchmarkRedisService.RescheduleJob> jobs = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            if (!Boolean.parseBoolean(stringValue(value.get("recurring")))) {
                continue;
            }
            String member = stringValue(value.get("member"));
            long executionTime = parseLong(value.get("executionTimeUtcMillis"), clock.millis());
            jobs.add(new BenchmarkRedisService.RescheduleJob(member, executionTime + 60_000));
        }
        if (!jobs.isEmpty()) {
            redis.reschedule(jobs);
            counters.addRescheduled(jobs.size());
        }
    }

    private long parseLong(Object value, long fallback) {
        try {
            return Long.parseLong(stringValue(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            Thread.onSpinWait();
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
