import init, { PySonar, version } from "./pkg/pysonar_wasm.js";

let engine;

self.onmessage = async ({ data }) => {
  try {
    if (!engine) {
      await init(new URL("./pkg/pysonar_wasm_bg.wasm", import.meta.url));
      engine = new PySonar("browser");
    }
    engine.setFile(data.path, data.source);
    const summary = JSON.parse(engine.analyze());
    const plan = JSON.parse(engine.plan(JSON.stringify(data.symbols || []), "inspect", 12));
    const check = JSON.parse(engine.check(JSON.stringify([data.path])));
    self.postMessage({ ok: true, version: version(), summary, plan, check });
  } catch (error) {
    self.postMessage({ ok: false, error: error?.message || String(error) });
  }
};
