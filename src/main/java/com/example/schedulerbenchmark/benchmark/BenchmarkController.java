package com.example.schedulerbenchmark.benchmark;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/benchmark")
public class BenchmarkController {
    private final BenchmarkSeedService seedService;
    private final BenchmarkRuntimeService runtimeService;
    private final BenchmarkRedisService redisService;
    private final BenchmarkCounters counters;
    private final BenchmarkProperties properties;
    private final LoadTestPrepareJobService prepareJobService;

    public BenchmarkController(BenchmarkSeedService seedService, BenchmarkRuntimeService runtimeService,
            BenchmarkRedisService redisService, BenchmarkCounters counters, BenchmarkProperties properties,
            LoadTestPrepareJobService prepareJobService) {
        this.seedService = seedService;
        this.runtimeService = runtimeService;
        this.redisService = redisService;
        this.counters = counters;
        this.properties = properties;
        this.prepareJobService = prepareJobService;
    }

    @PostMapping("/seed")
    public SeedResponse seed(
            @RequestBody(required = false) SeedRequest body,
            @RequestParam(required = false) Long totalJobs,
            @RequestParam(required = false) Long dueAtUtcMillis,
            @RequestParam(required = false) Integer recurringPercent,
            @RequestParam(required = false) Integer ruleCountPerUserKey,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) Long startIndex) {
        return seedService.seed(seedRequest(body, totalJobs, dueAtUtcMillis, recurringPercent, ruleCountPerUserKey,
                namespace, startIndex));
    }

    @PostMapping("/loadtest/prepare-5m")
    public LoadTestPrepareResponse prepareFiveMinuteLoadTest(@RequestBody(required = false) LoadTestPrepareRequest body) {
        runtimeService.stop();
        redisService.resetKeys();
        counters.reset();
        return seedService.prepareFiveMinuteLoadTest(body == null
                ? new LoadTestPrepareRequest(null, null, null, null, null)
                : body);
    }

    @PostMapping("/loadtest/prepare-5m/async")
    public LoadTestPrepareJobStatus prepareFiveMinuteLoadTestAsync(@RequestBody(required = false) LoadTestPrepareRequest body) {
        return prepareJobService.start(body);
    }

    @GetMapping("/loadtest/prepare-status")
    public LoadTestPrepareJobStatus prepareStatus() {
        return prepareJobService.status();
    }

    @GetMapping("/stats")
    public BenchmarkStats stats() {
        return redisService.stats(counters);
    }

    @GetMapping("/status")
    public RuntimeStatus status() {
        return new RuntimeStatus(
                runtimeService.isRunning(),
                redisService.stats(counters),
                new RuntimeConfig(
                        runtimeService.isRunning(),
                        "UTC",
                        "Redis ZSET + Redis Streams",
                        properties.redis().dueKey(),
                        properties.redis().streamKey()));
    }

    @PostMapping("/start")
    public ResponseEntity<ControlResponse> start() {
        boolean changed = runtimeService.start();
        return ResponseEntity.ok(new ControlResponse(runtimeService.isRunning(), changed));
    }

    @PostMapping("/stop")
    public ResponseEntity<ControlResponse> stop() {
        boolean changed = runtimeService.stop();
        return ResponseEntity.ok(new ControlResponse(runtimeService.isRunning(), changed));
    }

    @PostMapping("/reset")
    public ResponseEntity<ControlResponse> reset() {
        runtimeService.stop();
        redisService.resetKeys();
        counters.reset();
        return ResponseEntity.ok(new ControlResponse(runtimeService.isRunning(), true));
    }

    public record ControlResponse(boolean running, boolean changed) {
    }

    public record RuntimeStatus(boolean running, BenchmarkStats stats, RuntimeConfig config) {
    }

    public record RuntimeConfig(
            boolean schedulerActive,
            String timeMode,
            String architecture,
            String dueKey,
            String streamKey) {
    }

    private SeedRequest seedRequest(SeedRequest body, Long totalJobs, Long dueAtUtcMillis, Integer recurringPercent,
            Integer ruleCountPerUserKey, String namespace, Long startIndex) {
        if (body == null && totalJobs == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalJobs is required");
        }
        SeedRequest request = body == null
                ? new SeedRequest(totalJobs, dueAtUtcMillis, valueOrZero(recurringPercent), ruleCountPerUserKey,
                        namespace, startIndex)
                : new SeedRequest(
                        totalJobs == null ? body.totalJobs() : totalJobs,
                        dueAtUtcMillis == null ? body.dueAtUtcMillis() : dueAtUtcMillis,
                        recurringPercent == null ? body.recurringPercent() : recurringPercent,
                        ruleCountPerUserKey == null ? body.ruleCountPerUserKey() : ruleCountPerUserKey,
                        namespace == null ? body.namespace() : namespace,
                        startIndex == null ? body.startIndex() : startIndex);
        if (request.totalJobs() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalJobs must be positive");
        }
        if (request.dueAtUtcMillis() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dueAtUtcMillis is required");
        }
        if (request.recurringPercent() < 0 || request.recurringPercent() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "recurringPercent must be between 0 and 100");
        }
        if (request.ruleCountPerUserKey() != null && request.ruleCountPerUserKey() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ruleCountPerUserKey must be at least 1");
        }
        if (request.startIndex() != null && request.startIndex() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startIndex must be zero or greater");
        }
        return request;
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
