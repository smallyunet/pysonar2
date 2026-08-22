const worker = new Worker("worker.js", { type: "module" });
let pending;

const elements = Object.fromEntries([
  "status-card", "status-title", "status-message", "source-heading", "source-meta",
  "file-count", "coverage", "symbols", "diagnostics", "diagnostic-count", "runtime",
].map((id) => [id, document.getElementById(id)]));

function setStatus(state, title, message) {
  elements["status-card"].className = `status-card ${state}`;
  elements["status-title"].textContent = title;
  elements["status-message"].textContent = message;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

function symbolsFrom(source) {
  const matches = [...source.matchAll(/^\s*(?:async\s+)?(?:def|class)\s+([A-Za-z_]\w*)|^\s*([A-Za-z_]\w*)\s*=/gm)];
  return [...new Set(matches.map((match) => match[1] || match[2]))].slice(0, 32);
}

function analyze(payload) {
  pending = payload;
  elements["source-heading"].textContent = payload.path;
  elements["source-meta"].textContent = payload.url ? `Captured from ${new URL(payload.url).hostname}` : payload.title;
  setStatus("busy", "Analyzing locally", "Parsing and indexing this source in a Web Worker…");
  worker.postMessage({ ...payload, symbols: symbolsFrom(payload.source) });
}

worker.onmessage = ({ data }) => {
  if (!data.ok) {
    setStatus("error", "Analysis failed", `${data.error}. Try a smaller or complete Python block.`);
    return;
  }
  const summary = data.summary || {};
  const queries = data.plan?.queries || [];
  const diagnostics = data.check?.diagnostics || [];
  elements["file-count"].textContent = `${summary.parsedFiles ?? summary.parsed_files ?? 0} files`;
  elements.coverage.textContent = `${summary.symbolCount ?? summary.symbol_count ?? queries.length} symbols`;
  elements.runtime.textContent = `PySonar2 ${data.version} · Rust + WebAssembly`;
  elements.symbols.className = `results-list${queries.length ? "" : " empty"}`;
  elements.symbols.innerHTML = queries.length ? queries.map((query) => `
    <div class="result">
      <code>${escapeHtml(query.symbol || "unknown")}</code><span class="kind">${escapeHtml(query.confidence || "indexed")}</span>
      <small>${(query.definitions?.length || 0)} definitions · ${(query.references?.length || 0)} references</small>
    </div>`).join("") : "No named definitions found in this snippet.";
  elements["diagnostic-count"].textContent = String(diagnostics.length);
  elements.diagnostics.className = `results-list${diagnostics.length ? "" : " empty"}`;
  elements.diagnostics.innerHTML = diagnostics.length ? diagnostics.map((diagnostic) => `
    <div class="result"><strong>${escapeHtml(diagnostic.message)}</strong><span class="kind">${escapeHtml(diagnostic.severity)}</span>
    <small>${escapeHtml(diagnostic.file)}:${diagnostic.line}</small></div>`).join("") : "No conservative diagnostics for this source.";
  setStatus("", "Analysis complete", `${pending?.path || "Source"} stayed inside your browser.`);
};

chrome.runtime.onMessage.addListener((message) => {
  if (message?.type === "pysonar2:pending") analyze(message.payload);
});
chrome.storage.session.get("pysonar2Pending").then(({ pysonar2Pending }) => {
  if (pysonar2Pending) analyze(pysonar2Pending);
});
