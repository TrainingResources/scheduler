#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
UNIQUE_PER_MINUTE="${UNIQUE_PER_MINUTE:-500000}"
RECURRING_PER_MINUTE="${RECURRING_PER_MINUTE:-500000}"
DURATION_MINUTES="${DURATION_MINUTES:-5}"
START_DELAY_SECONDS="${START_DELAY_SECONDS:-300}"
NAMESPACE="${NAMESPACE:-loadtest-$(date -u +%Y%m%d%H%M%S)}"

json_post() {
  local path="$1"
  local body="${2:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X POST "$BASE_URL$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -fsS -X POST "$BASE_URL$path"
  fi
}

BASE_DUE_MS=$(( ($(date -u +%s) + START_DELAY_SECONDS) * 1000 ))

echo "Resetting benchmark keys and counters at $BASE_URL"
json_post /benchmark/reset >/dev/null

echo "Seeding $RECURRING_PER_MINUTE recurring jobs due at $BASE_DUE_MS"
json_post /benchmark/seed "{
  \"totalJobs\": $RECURRING_PER_MINUTE,
  \"dueAtUtcMillis\": $BASE_DUE_MS,
  \"recurringPercent\": 100,
  \"namespace\": \"$NAMESPACE-recurring\",
  \"startIndex\": 0
}"
echo

for minute in $(seq 0 $((DURATION_MINUTES - 1))); do
  due_ms=$((BASE_DUE_MS + minute * 60000))
  start_index=$((minute * UNIQUE_PER_MINUTE))
  echo "Seeding minute $minute: $UNIQUE_PER_MINUTE one-shot jobs due at $due_ms"
  json_post /benchmark/seed "{
    \"totalJobs\": $UNIQUE_PER_MINUTE,
    \"dueAtUtcMillis\": $due_ms,
    \"recurringPercent\": 0,
    \"namespace\": \"$NAMESPACE-unique\",
    \"startIndex\": $start_index
  }"
  echo
done

total_unique=$((UNIQUE_PER_MINUTE * DURATION_MINUTES))
total_recurring_executions=$((RECURRING_PER_MINUTE * DURATION_MINUTES))
total_executions=$((total_unique + total_recurring_executions))
start_epoch=$((BASE_DUE_MS / 1000))
stop_epoch=$((start_epoch + DURATION_MINUTES * 60))

cat <<EOF

Prepared load test namespace: $NAMESPACE
Initial Redis ZSET jobs: $((total_unique + RECURRING_PER_MINUTE))
Expected executions over ${DURATION_MINUTES} minutes: $total_executions
  one-shot executions: $total_unique
  recurring executions: $total_recurring_executions

Start at UTC epoch seconds: $start_epoch
Stop before UTC epoch seconds: $stop_epoch

Run:
  curl -X POST "$BASE_URL/benchmark/start"
  watch -n 1 'curl -s "$BASE_URL/benchmark/stats" | jq'
  curl -X POST "$BASE_URL/benchmark/stop"

EOF
