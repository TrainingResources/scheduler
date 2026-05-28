package com.example.schedulerbenchmark.benchmark;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

@Component
public class BenchmarkCounters {
    private final Clock clock = Clock.systemUTC();
    private final AtomicLong claimedTotal = new AtomicLong();
    private final AtomicLong publishedTotal = new AtomicLong();
    private final AtomicLong consumedTotal = new AtomicLong();
    private final AtomicLong ackedTotal = new AtomicLong();
    private final AtomicLong rescheduledTotal = new AtomicLong();
    private final AtomicLong startedAtMillis = new AtomicLong(clock.millis());

    public void addClaimed(long count) {
        claimedTotal.addAndGet(count);
    }

    public void addPublished(long count) {
        publishedTotal.addAndGet(count);
    }

    public void addConsumed(long count) {
        consumedTotal.addAndGet(count);
    }

    public void addAcked(long count) {
        ackedTotal.addAndGet(count);
    }

    public void addRescheduled(long count) {
        rescheduledTotal.addAndGet(count);
    }

    public void reset() {
        claimedTotal.set(0);
        publishedTotal.set(0);
        consumedTotal.set(0);
        ackedTotal.set(0);
        rescheduledTotal.set(0);
        startedAtMillis.set(clock.millis());
    }

    public BenchmarkStats snapshot(Long pendingStreamMessages, Long dueZsetSize, Long streamLength) {
        double elapsedSeconds = Math.max(1.0, (clock.millis() - startedAtMillis.get()) / 1000.0);
        long claimed = claimedTotal.get();
        long published = publishedTotal.get();
        long consumed = consumedTotal.get();
        return new BenchmarkStats(
                claimed,
                published,
                consumed,
                ackedTotal.get(),
                rescheduledTotal.get(),
                claimed / elapsedSeconds,
                published / elapsedSeconds,
                consumed / elapsedSeconds,
                pendingStreamMessages,
                dueZsetSize,
                streamLength);
    }
}
