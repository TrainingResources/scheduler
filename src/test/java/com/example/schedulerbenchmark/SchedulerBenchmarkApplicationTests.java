package com.example.schedulerbenchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.schedulerbenchmark.benchmark.BenchmarkCounters;

import org.junit.jupiter.api.Test;

class SchedulerBenchmarkApplicationTests {

	@Test
	void countersSnapshotContainsRates() {
		BenchmarkCounters counters = new BenchmarkCounters();
		counters.addClaimed(10);
		counters.addPublished(9);
		counters.addConsumed(8);
		counters.addAcked(7);
		counters.addRescheduled(6);

		var stats = counters.snapshot(5L, 4L, 3L);

		assertThat(stats.claimedTotal()).isEqualTo(10);
		assertThat(stats.publishedTotal()).isEqualTo(9);
		assertThat(stats.consumedTotal()).isEqualTo(8);
		assertThat(stats.ackedTotal()).isEqualTo(7);
		assertThat(stats.rescheduledTotal()).isEqualTo(6);
		assertThat(stats.pendingStreamMessages()).isEqualTo(5);
		assertThat(stats.dueZsetSize()).isEqualTo(4);
		assertThat(stats.streamLength()).isEqualTo(3);
		assertThat(stats.claimRatePerSecond()).isGreaterThan(0.0);
	}

}
