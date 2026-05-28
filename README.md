# Scheduler Benchmark

Small Spring Boot 3 / Java 25 service for testing whether Redis ZSET + Redis Streams can handle notification scheduler throughput.

The benchmark path is intentionally simple:

1. `POST /benchmark/seed` creates Redis `HASH` + `ZSET` jobs.
2. The scheduler loop atomically claims due ZSET members with Lua.
3. Claimed jobs are published to a Redis Stream.
4. Dummy consumers read with `XREADGROUP`, optionally reschedule recurring jobs by `+60000ms`, and `XACK`.

PostgreSQL stores the schedule configuration table, but seeding only inserts a capped number of rows by default so large runs do not spend time writing millions of DB records.

## Requirements

- Java 25
- Existing PostgreSQL and Redis, or the local `docker-compose.yml`
- No local Maven install required; use `./mvnw`

## Run Locally

Start local dependencies if needed:

```bash
docker compose up -d postgres redis
```

Run the service:

```bash
POSTGRES_URL=jdbc:postgresql://localhost:5432/scheduler \
POSTGRES_USER=postgres \
POSTGRES_PASSWORD=postgres \
REDIS_HOST=localhost \
REDIS_PORT=6379 \
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`.

## Configuration

All important settings are in `src/main/resources/application.yml` and can be overridden with environment variables.

```yaml
benchmark:
  node-id: ${NODE_ID:${random.uuid}}
  auto-start: false
  scheduler:
    enabled: true
    claim:
      batch-size: 1000
    poll-interval-ms: 10
  stream:
    consumer:
      enabled: true
      threads: 1
      batch-size: 1000
      block-ms: 1000
  seed:
    postgres-max-rows: 1000
    redis-pipeline-size: 5000
```

Useful throughput knobs:

- `SCHEDULER_CLAIM_BATCH_SIZE`
- `SCHEDULER_POLL_INTERVAL_MS`
- `STREAM_CONSUMER_THREADS`
- `STREAM_CONSUMER_BATCH_SIZE`
- `STREAM_CONSUMER_BLOCK_MS`
- `SEED_POSTGRES_MAX_ROWS`
- `SEED_REDIS_PIPELINE_SIZE`

## Benchmark Commands

Reset benchmark Redis keys and counters:

```bash
curl -X POST http://localhost:8080/benchmark/reset
```

Seed 100k due jobs:

```bash
NOW_MS=$(date -u +%s000)
curl -X POST http://localhost:8080/benchmark/seed \
  -H 'Content-Type: application/json' \
  -d "{\"totalJobs\":100000,\"dueAtUtcMillis\":${NOW_MS},\"recurringPercent\":0}"
```

Seed 1M due jobs:

```bash
NOW_MS=$(date -u +%s000)
curl -X POST http://localhost:8080/benchmark/seed \
  -H 'Content-Type: application/json' \
  -d "{\"totalJobs\":1000000,\"dueAtUtcMillis\":${NOW_MS},\"recurringPercent\":0}"
```

Start, watch stats, and stop:

```bash
curl -X POST http://localhost:8080/benchmark/start
watch -n 1 'curl -s http://localhost:8080/benchmark/stats | jq'
curl -X POST http://localhost:8080/benchmark/stop
```

Query-param seeding also works:

```bash
curl -X POST 'http://localhost:8080/benchmark/seed?totalJobs=100000&dueAtUtcMillis=1893456000000&recurringPercent=10&ruleCountPerUserKey=2&namespace=test-a&startIndex=0'
```

Optional seed fields:

- `namespace`: prefixes `notificationKey`, useful when seeding multiple waves without Redis member collisions.
- `startIndex`: offsets generated user/notification ids, useful for unique ranges across repeated seed calls.

## 5M / 5 Minute Load Test Setup

The script below prepares a 5 minute test with:

- 500k one-shot jobs per minute for 5 minutes.
- 500k recurring jobs due at minute 0, then rescheduled every minute.
- 5M expected executions total over the 5 minute window.

It seeds 3M initial Redis ZSET entries: 2.5M one-shot jobs plus 500k recurring jobs. The recurring jobs produce the other 2M executions through rescheduling.

```bash
BASE_URL=http://localhost:8080 ./scripts/prepare-5m-loadtest.sh
```

For the Portainer deployment:

```bash
BASE_URL=http://localhost:9104 ./scripts/prepare-5m-loadtest.sh
```

Defaults can be overridden:

```bash
BASE_URL=http://localhost:8080 \
UNIQUE_PER_MINUTE=500000 \
RECURRING_PER_MINUTE=500000 \
DURATION_MINUTES=5 \
START_DELAY_SECONDS=300 \
./scripts/prepare-5m-loadtest.sh
```

The script prints the exact start/stop epoch seconds. Start the benchmark close to the printed start time and stop before the printed stop time to avoid the recurring wave at minute 5.

The service also exposes the same setup as an endpoint:

```bash
curl -X POST http://localhost:8080/benchmark/loadtest/prepare-5m \
  -H 'Content-Type: application/json' \
  -d '{
    "uniquePerMinute": 500000,
    "recurringPerMinute": 500000,
    "durationMinutes": 5,
    "startDelaySeconds": 300,
    "namespace": "loadtest-5m"
  }'
```

This endpoint stops the runtime loops, deletes benchmark Redis keys, resets counters, and seeds the load-test waves.

## Redis Data Model

- ZSET `scheduler:due`
  - score: `execution_time_utc_millis`
  - member: `userId:notificationKey:ruleId`
- HASH `scheduler:job:{member}`
  - `userId`, `notificationKey`, `ruleId`, `executionTimeUtcMillis`, `version`, `recurring`
- STREAM `scheduler:stream`

## Horizontal Scaling

Run multiple app instances against the same Redis and PostgreSQL. Give each process a distinct `NODE_ID` and port:

```bash
NODE_ID=node-a SERVER_PORT=8080 ./mvnw spring-boot:run
NODE_ID=node-b SERVER_PORT=8081 ./mvnw spring-boot:run
```

All instances share the same stream consumer group. Scheduler claims are atomic because the ZSET read/remove happens inside a Redis Lua script.
