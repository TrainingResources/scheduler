package com.example.schedulerbenchmark.benchmark;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;

@Service
public class LoadTestPrepareJobService {
    private final BenchmarkSeedService seedService;
    private final BenchmarkRuntimeService runtimeService;
    private final BenchmarkRedisService redisService;
    private final BenchmarkCounters counters;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<LoadTestPrepareJobStatus> status = new AtomicReference<>(
            idle("No prepare job has been started."));

    public LoadTestPrepareJobService(BenchmarkSeedService seedService, BenchmarkRuntimeService runtimeService,
            BenchmarkRedisService redisService, BenchmarkCounters counters) {
        this.seedService = seedService;
        this.runtimeService = runtimeService;
        this.redisService = redisService;
        this.counters = counters;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public LoadTestPrepareJobStatus status() {
        return status.get();
    }

    public LoadTestPrepareJobStatus start(LoadTestPrepareRequest request) {
        LoadTestPrepareJobStatus current = status.get();
        if (current.running()) {
            return current;
        }
        LoadTestPlan plan = plan(request == null ? new LoadTestPrepareRequest(null, null, null, null, null) : request);
        LoadTestPrepareJobStatus started = new LoadTestPrepareJobStatus(
                true,
                false,
                false,
                "Resetting Redis benchmark keys.",
                plan.namespace(),
                0,
                plan.durationMinutes() + 1,
                0,
                plan.initialRedisJobs(),
                plan.expectedTotalExecutions(),
                plan.baseDueAtUtcMillis(),
                plan.startEpochSeconds(),
                plan.stopBeforeEpochSeconds());
        status.set(started);
        executor.submit(() -> run(plan));
        return started;
    }

    private void run(LoadTestPlan plan) {
        Progress progress = new Progress();
        int wavesCompleted = 0;
        try {
            runtimeService.stop();
            redisService.resetKeys();
            counters.reset();

            update(plan, wavesCompleted, progress.jobsSeeded(), "Seeding recurring wave.");
            int recurringWave = wavesCompleted;
            SeedResponse recurring = seedService.seed(new SeedRequest(
                    plan.recurringPerMinute(),
                    plan.baseDueAtUtcMillis(),
                    100,
                    null,
                    plan.namespace() + "-recurring",
                    0L),
                    count -> {
                        progress.add(count);
                        update(plan, recurringWave, progress.jobsSeeded(), "Seeding recurring wave.");
                    });
            progress.setAtLeast(recurring.redisJobsSeeded());
            wavesCompleted++;
            update(plan, wavesCompleted, progress.jobsSeeded(), "Recurring wave seeded.");

            for (int minute = 0; minute < plan.durationMinutes(); minute++) {
                update(plan, wavesCompleted, progress.jobsSeeded(), "Seeding one-shot minute " + (minute + 1) + ".");
                long before = progress.jobsSeeded();
                int minuteNumber = minute + 1;
                int currentWave = wavesCompleted;
                SeedResponse unique = seedService.seed(new SeedRequest(
                        plan.uniquePerMinute(),
                        plan.baseDueAtUtcMillis() + (minute * 60_000L),
                        0,
                        null,
                        plan.namespace() + "-unique",
                        minute * plan.uniquePerMinute()),
                        count -> {
                            progress.add(count);
                            update(plan, currentWave, progress.jobsSeeded(),
                                    "Seeding one-shot minute " + minuteNumber + ".");
                        });
                progress.setAtLeast(before + unique.redisJobsSeeded());
                wavesCompleted++;
                update(plan, wavesCompleted, progress.jobsSeeded(), "Seeded minute " + (minute + 1) + ".");
            }

            status.set(new LoadTestPrepareJobStatus(
                    false,
                    true,
                    false,
                    "Prepared " + progress.jobsSeeded() + " initial Redis jobs.",
                    plan.namespace(),
                    wavesCompleted,
                    plan.durationMinutes() + 1,
                    progress.jobsSeeded(),
                    plan.initialRedisJobs(),
                    plan.expectedTotalExecutions(),
                    plan.baseDueAtUtcMillis(),
                    plan.startEpochSeconds(),
                    plan.stopBeforeEpochSeconds()));
        } catch (RuntimeException ex) {
            status.set(new LoadTestPrepareJobStatus(
                    false,
                    false,
                    true,
                    ex.getMessage(),
                    plan.namespace(),
                    wavesCompleted,
                    plan.durationMinutes() + 1,
                    progress.jobsSeeded(),
                    plan.initialRedisJobs(),
                    plan.expectedTotalExecutions(),
                    plan.baseDueAtUtcMillis(),
                    plan.startEpochSeconds(),
                    plan.stopBeforeEpochSeconds()));
        }
    }

    private void update(LoadTestPlan plan, int wavesCompleted, long jobsSeeded, String message) {
        status.set(new LoadTestPrepareJobStatus(
                true,
                false,
                false,
                message,
                plan.namespace(),
                wavesCompleted,
                plan.durationMinutes() + 1,
                jobsSeeded,
                plan.initialRedisJobs(),
                plan.expectedTotalExecutions(),
                plan.baseDueAtUtcMillis(),
                plan.startEpochSeconds(),
                plan.stopBeforeEpochSeconds()));
    }

    private LoadTestPlan plan(LoadTestPrepareRequest request) {
        long uniquePerMinute = request.effectiveUniquePerMinute();
        long recurringPerMinute = request.effectiveRecurringPerMinute();
        int durationMinutes = request.effectiveDurationMinutes();
        String namespace = request.effectiveNamespace();
        long baseDueAtUtcMillis = (Instant.now().getEpochSecond() + request.effectiveStartDelaySeconds()) * 1000;
        long expectedUniqueExecutions = uniquePerMinute * durationMinutes;
        long expectedRecurringExecutions = recurringPerMinute * durationMinutes;
        return new LoadTestPlan(
                uniquePerMinute,
                recurringPerMinute,
                durationMinutes,
                namespace,
                baseDueAtUtcMillis,
                expectedUniqueExecutions + recurringPerMinute,
                expectedUniqueExecutions + expectedRecurringExecutions,
                baseDueAtUtcMillis / 1000,
                (baseDueAtUtcMillis / 1000) + (durationMinutes * 60L));
    }

    private static LoadTestPrepareJobStatus idle(String message) {
        return new LoadTestPrepareJobStatus(false, false, false, message, "", 0, 0, 0, 0, 0, null, null, null);
    }

    private record LoadTestPlan(
            long uniquePerMinute,
            long recurringPerMinute,
            int durationMinutes,
            String namespace,
            long baseDueAtUtcMillis,
            long initialRedisJobs,
            long expectedTotalExecutions,
            long startEpochSeconds,
            long stopBeforeEpochSeconds) {
    }

    private static final class Progress {
        private long jobsSeeded;

        long jobsSeeded() {
            return jobsSeeded;
        }

        void add(long count) {
            jobsSeeded += count;
        }

        void setAtLeast(long value) {
            jobsSeeded = Math.max(jobsSeeded, value);
        }
    }
}
