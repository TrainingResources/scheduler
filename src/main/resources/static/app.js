const TARGET_TOTAL = 5_000_000;
const state = {
  lastStats: null,
  lastSampleAt: null,
  deltaRates: [],
  peakDueZset: Number(localStorage.getItem("benchmarkPeakDueZset") || "0"),
  prepare: JSON.parse(localStorage.getItem("benchmarkPrepare") || "null"),
  prepareJob: null
};

const $ = (id) => document.getElementById(id);
const fmt = new Intl.NumberFormat("en-US");
const rateFmt = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });

function number(value) {
  return fmt.format(value ?? 0);
}

function rate(value) {
  return `${rateFmt.format(value ?? 0)}/s`;
}

function utc(ms) {
  if (!ms) return "-";
  return new Date(ms).toISOString().replace(".000Z", "Z");
}

function duration(ms) {
  if (ms == null) return "-";
  if (ms <= 0) return "due now";
  const totalSeconds = Math.ceil(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `${response.status} ${response.statusText}`);
  }
  return response.json();
}

function toast(message) {
  const el = $("toast");
  el.textContent = message;
  el.classList.add("visible");
  window.clearTimeout(toast.timer);
  toast.timer = window.setTimeout(() => el.classList.remove("visible"), 3600);
}

function setRuntime(running) {
  const status = $("runtimeStatus");
  status.classList.toggle("running", running);
  status.classList.toggle("stopped", !running);
  $("runtimeText").textContent = running ? "Running" : "Stopped";
}

function renderPrepare() {
  const prepare = state.prepare;
  $("initialRedisJobs").textContent = prepare ? number(prepare.initialRedisJobs) : "-";
  $("expectedTotalExecutions").textContent = prepare ? number(prepare.expectedTotalExecutions) : "-";
  $("startUtc").textContent = prepare ? utc(prepare.baseDueAtUtcMillis) : "-";
  $("stopUtc").textContent = prepare ? utc(prepare.stopBeforeEpochSeconds * 1000) : "-";
}

function renderPrepareJob(job) {
  if (!job) return;
  state.prepareJob = job;
  const total = job.initialRedisJobs || 0;
  const seeded = job.jobsSeeded || 0;
  const percent = total > 0 ? Math.min(100, (seeded / total) * 100) : 0;
  $("prepareMessage").textContent = job.message || "Preparing data";
  $("preparePercent").textContent = `${percent.toFixed(1)}%`;
  $("prepareFill").style.width = `${percent}%`;
  $("prepareFoot").textContent = `${number(seeded)} / ${number(total)} initial jobs, wave ${job.wavesCompleted || 0}/${job.wavesTotal || 0}`;

  if (job.initialRedisJobs || job.expectedTotalExecutions) {
    state.prepare = {
      namespace: job.namespace,
      initialRedisJobs: job.initialRedisJobs,
      expectedTotalExecutions: job.expectedTotalExecutions,
      baseDueAtUtcMillis: job.baseDueAtUtcMillis,
      startEpochSeconds: job.startEpochSeconds,
      stopBeforeEpochSeconds: job.stopBeforeEpochSeconds
    };
    localStorage.setItem("benchmarkPrepare", JSON.stringify(state.prepare));
    renderPrepare();
  }

  if (job.completed) {
    $("prepareMessage").textContent = "Prepare complete";
    $("preparePercent").textContent = "100.0%";
    $("prepareFill").style.width = "100%";
  }
}

function renderStats(stats) {
  const now = Date.now();
  if (state.lastStats && state.lastSampleAt) {
    const elapsed = Math.max(1, (now - state.lastSampleAt) / 1000);
    const delta = Math.max(0, stats.consumedTotal - state.lastStats.consumedTotal);
    state.deltaRates.push(delta / elapsed);
    state.deltaRates = state.deltaRates.slice(-60);
  }
  state.lastStats = stats;
  state.lastSampleAt = now;

  const target = state.prepare?.expectedTotalExecutions || TARGET_TOTAL;
  const progress = Math.min(100, (stats.ackedTotal / target) * 100);
  const instantRate = state.deltaRates.at(-1) || 0;
  const dueSize = stats.dueZsetSize ?? 0;
  state.peakDueZset = Math.max(state.peakDueZset, dueSize, state.prepare?.initialRedisJobs || 0);
  localStorage.setItem("benchmarkPeakDueZset", String(state.peakDueZset));
  const backlogPercent = state.peakDueZset > 0 ? Math.min(100, (dueSize / state.peakDueZset) * 100) : 0;

  $("ackedTotal").textContent = number(stats.ackedTotal);
  $("ackedTotalDetail").textContent = number(stats.ackedTotal);
  $("progressText").textContent = `${progress.toFixed(1)}% of ${number(target)} target`;
  $("progressPercent").textContent = `${progress.toFixed(1)}%`;
  $("progressFill").style.width = `${progress}%`;
  $("processedExecutions").textContent = `${number(stats.ackedTotal)} / ${number(target)}`;
  $("backlogPercent").textContent = `${backlogPercent.toFixed(1)}%`;
  $("backlogFill").style.width = `${backlogPercent}%`;
  $("runState").textContent = runStateText(stats, progress, instantRate);
  $("dueCountdown").textContent = dueCountdownText();

  $("consumeRate").textContent = rate(instantRate || stats.consumeRatePerSecond);
  $("consumeRateMinute").textContent = `${number(Math.round((instantRate || stats.consumeRatePerSecond) * 60))}/min`;
  $("pendingStreamMessages").textContent = number(stats.pendingStreamMessages);
  $("dueZsetSize").textContent = number(stats.dueZsetSize);

  $("claimedTotal").textContent = number(stats.claimedTotal);
  $("publishedTotal").textContent = number(stats.publishedTotal);
  $("consumedTotal").textContent = number(stats.consumedTotal);
  $("rescheduledTotal").textContent = number(stats.rescheduledTotal);
  $("dueZsetDetail").textContent = number(stats.dueZsetSize);
  $("streamLength").textContent = number(stats.streamLength);
  $("pendingDetail").textContent = number(stats.pendingStreamMessages);
  $("claimRate").textContent = rate(stats.claimRatePerSecond);
  $("publishRate").textContent = rate(stats.publishRatePerSecond);
  $("lastUpdated").textContent = new Date().toLocaleTimeString();

  drawChart();
}

function runStateText(stats, progress, instantRate) {
  const dueSize = stats.dueZsetSize ?? 0;
  const pending = stats.pendingStreamMessages ?? 0;
  const dueInMs = state.prepare?.baseDueAtUtcMillis ? state.prepare.baseDueAtUtcMillis - Date.now() : null;
  if (progress >= 100 && pending === 0) {
    return "Completed target. Stream pending is empty.";
  }
  if (state.prepare && dueInMs != null && dueInMs > 0 && stats.ackedTotal === 0 && dueSize > 0) {
    return `Scheduler can be running, but jobs are not due yet. First bucket starts in ${duration(dueInMs)}.`;
  }
  if (stats.ackedTotal === 0 && dueSize > 0) {
    return "Prepared data is in Redis. Start the benchmark, then progress and throughput will move when jobs become due.";
  }
  if (instantRate > 0 || stats.consumeRatePerSecond > 0 || pending > 0) {
    return `Live run active. Current delta rate is ${rate(instantRate || stats.consumeRatePerSecond)}.`;
  }
  if (dueSize === 0 && stats.streamLength === 0) {
    return "No benchmark data loaded. Use Prepare 5M or seed a smaller run.";
  }
  return "Runtime is idle. Counters reset on application restart; Redis backlog is still shown below.";
}

function dueCountdownText() {
  if (!state.prepare?.baseDueAtUtcMillis) return "-";
  return duration(state.prepare.baseDueAtUtcMillis - Date.now());
}

function drawChart() {
  const canvas = $("rateChart");
  const ctx = canvas.getContext("2d");
  const { width, height } = canvas;
  ctx.clearRect(0, 0, width, height);

  const pad = 42;
  const chartW = width - pad * 2;
  const chartH = height - pad * 2;
  const values = state.deltaRates.length ? state.deltaRates : [0];
  const max = Math.max(20_000, ...values) * 1.12;
  const targetPerSecond = 1_000_000 / 60;

  ctx.strokeStyle = "#d9e1ea";
  ctx.lineWidth = 1;
  ctx.font = "13px system-ui, sans-serif";
  ctx.fillStyle = "#667085";

  for (let i = 0; i <= 4; i++) {
    const y = pad + (chartH / 4) * i;
    ctx.beginPath();
    ctx.moveTo(pad, y);
    ctx.lineTo(width - pad, y);
    ctx.stroke();
    const label = `${rateFmt.format((max / 4) * (4 - i))}/s`;
    ctx.fillText(label, 8, y + 4);
  }

  const targetY = pad + chartH - (Math.min(targetPerSecond, max) / max) * chartH;
  ctx.strokeStyle = "#b45309";
  ctx.lineWidth = 2;
  ctx.setLineDash([8, 8]);
  ctx.beginPath();
  ctx.moveTo(pad, targetY);
  ctx.lineTo(width - pad, targetY);
  ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = "#b45309";
  ctx.fillText("1M/min target", width - pad - 105, targetY - 8);

  ctx.strokeStyle = "#0f766e";
  ctx.lineWidth = 4;
  ctx.lineJoin = "round";
  ctx.lineCap = "round";
  ctx.beginPath();
  values.forEach((value, index) => {
    const x = pad + (values.length === 1 ? 0 : (chartW / (values.length - 1)) * index);
    const y = pad + chartH - (Math.min(value, max) / max) * chartH;
    if (index === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  });
  ctx.stroke();

  ctx.fillStyle = "#17202e";
  const latest = values.at(-1) || 0;
  ctx.fillText(`Latest delta: ${rate(latest)}`, pad, height - 12);
}

async function refresh() {
  try {
    const [status, prepareJob] = await Promise.all([
      request("/benchmark/status"),
      request("/benchmark/loadtest/prepare-status")
    ]);
    setRuntime(status.running);
    renderPrepareJob(prepareJob);
    renderStats(status.stats);
  } catch (error) {
    $("runtimeText").textContent = "Disconnected";
    toast("Could not refresh stats");
  }
}

async function prepareLoadTest() {
  const body = {
    uniquePerMinute: Number($("uniquePerMinute").value),
    recurringPerMinute: Number($("recurringPerMinute").value),
    durationMinutes: Number($("durationMinutes").value),
    startDelaySeconds: Number($("startDelaySeconds").value),
    namespace: $("namespace").value.trim()
  };
  toast("Preparing load-test data in background");
  const response = await request("/benchmark/loadtest/prepare-5m/async", {
    method: "POST",
    body: JSON.stringify(body)
  });
  renderPrepareJob(response);
  state.lastStats = null;
  state.lastSampleAt = null;
  state.deltaRates = [];
  state.peakDueZset = response.initialRedisJobs || 0;
  localStorage.setItem("benchmarkPeakDueZset", String(state.peakDueZset));
  await refresh();
  toast("Prepare started");
}

async function postControl(path, message) {
  const response = await request(path, { method: "POST" });
  setRuntime(response.running);
  await refresh();
  toast(message);
}

function bind() {
  $("prepareButton").addEventListener("click", () => prepareLoadTest().catch((error) => toast(error.message)));
  $("startButton").addEventListener("click", () => postControl("/benchmark/start", "Benchmark started").catch((error) => toast(error.message)));
  $("stopButton").addEventListener("click", () => postControl("/benchmark/stop", "Benchmark stopped").catch((error) => toast(error.message)));
  $("resetButton").addEventListener("click", () => postControl("/benchmark/reset", "Benchmark reset").catch((error) => toast(error.message)));
}

bind();
renderPrepare();
refresh();
window.setInterval(refresh, 2000);
