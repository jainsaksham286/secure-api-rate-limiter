const adminInput = document.querySelector("#admin-key");
const adminForm = document.querySelector("#admin-form");
const connectionStatus = document.querySelector("#connection-status");
const dashboardError = document.querySelector("#dashboard-error");
const clientRows = document.querySelector("#client-rows");
const chart = document.querySelector("#traffic-chart");
const chartContext = chart.getContext("2d");
const demoResult = document.querySelector("#demo-result");
const demoKeys = { free: "demo-key-free-123", pro: "demo-key-pro-456" };
let adminKey = "";
let currentSeries = [];
let refreshTimer;

try {
  adminKey = sessionStorage.getItem("monitor-admin-key") || "";
  adminInput.value = adminKey;
} catch {
  adminKey = "";
}

adminForm.addEventListener("submit", (event) => {
  event.preventDefault();
  adminKey = adminInput.value.trim();
  try {
    if (adminKey) sessionStorage.setItem("monitor-admin-key", adminKey);
    else sessionStorage.removeItem("monitor-admin-key");
  } catch {
    // The key remains in memory if browser storage is unavailable.
  }
  refreshDashboard();
});

document.querySelector("#refresh-button").addEventListener("click", refreshDashboard);
document.querySelector("#auto-refresh").addEventListener("change", configureAutoRefresh);
document.querySelector("#send-one").addEventListener("click", () => sendDemoRequests(1));
document.querySelector("#send-burst").addEventListener("click", () => sendDemoRequests(20));
window.addEventListener("resize", drawChart);

function configureAutoRefresh() {
  clearInterval(refreshTimer);
  if (document.querySelector("#auto-refresh").checked) {
    refreshTimer = setInterval(refreshDashboard, 4000);
  }
}

async function refreshDashboard() {
  if (!adminKey) {
    setConnection("Enter the admin key to load monitoring data.", "");
    showTableMessage("Connect with an admin key to view client activity.");
    drawChart();
    return;
  }
  dashboardError.hidden = true;
  setConnection("Loading monitoring data…", "");
  try {
    const headers = { "X-Admin-Key": adminKey };
    const [summaryResponse, clientsResponse, seriesResponse] = await Promise.all([
      fetch("/api/monitoring/summary", { headers }),
      fetch("/api/monitoring/clients", { headers }),
      fetch("/api/monitoring/timeseries", { headers }),
    ]);
    if ([summaryResponse, clientsResponse, seriesResponse].some((response) => response.status === 401)) {
      throw new Error("invalid-key");
    }
    if (![summaryResponse, clientsResponse, seriesResponse].every((response) => response.ok)) {
      throw new Error("server-error");
    }
    const [summary, clients, series] = await Promise.all([
      summaryResponse.json(), clientsResponse.json(), seriesResponse.json(),
    ]);
    renderSummary(summary);
    renderClients(clients);
    currentSeries = series.points || [];
    drawChart();
    document.querySelector("#last-updated").textContent = `Updated ${new Date().toLocaleTimeString()}`;
    setConnection("Monitoring connected", "connected");
  } catch (error) {
    if (error.message === "invalid-key") {
      setConnection("Admin key rejected", "invalid");
      showError("Invalid admin key. Update the key above and connect again.");
    } else {
      setConnection("Monitoring unavailable", "invalid");
      showError("Server unreachable or monitoring request failed. Check that the local app is running.");
    }
  }
}

function renderSummary(summary) {
  setText("#metric-total", formatNumber(summary.totalRequests));
  setText("#metric-accepted", formatNumber(summary.acceptedRequests));
  setText("#metric-rejected", formatNumber(summary.rejectedRequests));
  setText("#metric-rate", `${Number(summary.rejectionRatePercent || 0).toFixed(1)}%`);
  setText("#metric-clients", formatNumber(summary.activeClients));
  setText("#metric-uptime", formatUptime(summary.uptimeSeconds));
  const accepted = Number(summary.acceptedRequests || 0);
  const rejected = Number(summary.rejectedRequests || 0);
  setText("#traffic-ratio", `Accepted / rejected: ${rejected ? (accepted / rejected).toFixed(2) : accepted ? "∞" : "—"}`);
}

function renderClients(clients) {
  clientRows.replaceChildren();
  setText("#client-count", `${clients.length} ${clients.length === 1 ? "client" : "clients"}`);
  if (!clients.length) {
    showTableMessage("No requests recorded yet.");
    return;
  }
  for (const client of clients) {
    const row = document.createElement("tr");
    appendCell(row, client.clientId, "client-name");
    appendCell(row, formatNumber(client.total));
    appendCell(row, formatNumber(client.accepted), "accepted-value");
    appendCell(row, formatNumber(client.rejected), "rejected-value");
    const rateCell = document.createElement("td");
    rateCell.className = "rate-cell";
    const track = document.createElement("span");
    track.className = "rate-track";
    const fill = document.createElement("span");
    const rate = Math.max(0, Math.min(100, Number(client.rejectionRatePercent || 0)));
    fill.className = `rate-fill${rate >= 60 ? " danger" : rate >= 30 ? " warning" : ""}`;
    fill.style.width = `${rate}%`;
    track.append(fill);
    rateCell.append(track, document.createTextNode(`${rate.toFixed(1)}%`));
    row.append(rateCell);

    const tokenCell = document.createElement("td");
    const tokenTrack = document.createElement("span");
    tokenTrack.className = "token-track";
    const tokenFill = document.createElement("span");
    const capacity = client.clientId === "demo-pro" ? 20 : client.clientId === "demo-free" ? 5 : 1;
    const remaining = Math.max(0, Number(client.currentTokensRemaining || 0));
    tokenFill.className = "token-fill";
    tokenFill.style.width = `${Math.min(100, remaining / capacity * 100)}%`;
    tokenTrack.append(tokenFill);
    tokenCell.append(tokenTrack, document.createTextNode(`${remaining} / ${capacity}`));
    row.append(tokenCell);
    appendCell(row, client.lastRequestTime ? new Date(client.lastRequestTime).toLocaleTimeString() : "—");
    clientRows.append(row);
  }
}

function appendCell(row, value, className = "") {
  const cell = document.createElement("td");
  if (className) cell.className = className;
  cell.textContent = value == null ? "—" : String(value);
  row.append(cell);
}

function showTableMessage(message) {
  clientRows.replaceChildren();
  const row = document.createElement("tr");
  const cell = document.createElement("td");
  cell.className = "table-message";
  cell.colSpan = 7;
  cell.textContent = message;
  row.append(cell);
  clientRows.append(row);
}

function drawChart() {
  const width = Math.max(280, chart.clientWidth);
  const height = 230;
  const pixelRatio = window.devicePixelRatio || 1;
  chart.width = Math.floor(width * pixelRatio);
  chart.height = Math.floor(height * pixelRatio);
  chartContext.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
  chartContext.clearRect(0, 0, width, height);
  const left = 35;
  const right = width - 8;
  const top = 12;
  const bottom = height - 24;
  chartContext.strokeStyle = "#2d3d49";
  chartContext.fillStyle = "#91a2ad";
  chartContext.font = "10px Cascadia Code, Consolas, monospace";
  for (let grid = 0; grid < 4; grid++) {
    const y = top + (bottom - top) * grid / 3;
    chartContext.beginPath();
    chartContext.moveTo(left, y);
    chartContext.lineTo(right, y);
    chartContext.stroke();
  }
  if (!currentSeries.length) {
    chartContext.fillText("Waiting for traffic data", left + 12, (top + bottom) / 2);
    return;
  }
  const maxValue = Math.max(1, ...currentSeries.flatMap((point) => [point.accepted, point.rejected]));
  chartContext.fillText(String(maxValue), 5, top + 4);
  chartContext.fillText("0", 20, bottom + 3);
  drawLine("accepted", "#68d7ad", maxValue, left, right, top, bottom);
  drawLine("rejected", "#ff7e78", maxValue, left, right, top, bottom);
}

function drawLine(field, color, maxValue, left, right, top, bottom) {
  if (currentSeries.length === 1) return;
  chartContext.beginPath();
  currentSeries.forEach((point, index) => {
    const x = left + (right - left) * index / (currentSeries.length - 1);
    const y = bottom - (bottom - top) * Number(point[field] || 0) / maxValue;
    if (index === 0) chartContext.moveTo(x, y);
    else chartContext.lineTo(x, y);
  });
  chartContext.strokeStyle = color;
  chartContext.lineWidth = 2;
  chartContext.stroke();
}

async function sendDemoRequests(count) {
  const selectedKey = demoKeys[document.querySelector("#demo-tier").value];
  const sendButton = document.querySelector("#send-one");
  const burstButton = document.querySelector("#send-burst");
  sendButton.disabled = true;
  burstButton.disabled = true;
  demoResult.className = "demo-result";
  demoResult.replaceChildren(document.createTextNode(`Sending ${count} request${count === 1 ? "" : "s"}…`));
  try {
    const results = await Promise.all(Array.from({ length: count }, async () => {
      const response = await fetch("/api/demo/data", { headers: { "X-API-Key": selectedKey } });
      return {
        status: response.status,
        remaining: response.headers.get("X-RateLimit-Remaining") || "—",
        retryAfter: response.headers.get("Retry-After"),
      };
    }));
    const accepted = results.filter((result) => result.status === 200).length;
    const rejected = results.filter((result) => result.status === 429).length;
    const last = results[results.length - 1];
    const retry = last.retryAfter ? ` · Retry-After ${last.retryAfter}s` : "";
    demoResult.className = `demo-result ${rejected ? "rejected" : "success"}`;
    demoResult.textContent = `${accepted} accepted · ${rejected} rate limited · last status ${last.status} · remaining ${last.remaining}${retry}`;
    refreshDashboard();
  } catch {
    demoResult.className = "demo-result error";
    demoResult.textContent = "Server unreachable. Start the local application and retry.";
  } finally {
    sendButton.disabled = false;
    burstButton.disabled = false;
  }
}

function setConnection(message, state) {
  connectionStatus.textContent = message;
  connectionStatus.className = `connection-status ${state}`;
}

function showError(message) {
  dashboardError.textContent = message;
  dashboardError.hidden = false;
}

function setText(selector, value) {
  document.querySelector(selector).textContent = String(value);
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString();
}

function formatUptime(seconds) {
  const duration = Math.max(0, Number(seconds || 0));
  const hours = Math.floor(duration / 3600);
  const minutes = Math.floor(duration % 3600 / 60);
  const remainder = duration % 60;
  return hours ? `${hours}h ${minutes}m` : minutes ? `${minutes}m ${remainder}s` : `${remainder}s`;
}

configureAutoRefresh();
refreshDashboard();