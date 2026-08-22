import { cpSync, mkdirSync, rmSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const stage = resolve(root, "dist/pysonar2-browser-4.0.0");
rmSync(stage, { recursive: true, force: true });
mkdirSync(stage, { recursive: true });
for (const file of ["manifest.json", "service-worker.js", "content.js", "content.css", "worker.js", "sidepanel.html", "sidepanel.css", "sidepanel.js", "pkg"]) cpSync(resolve(root, file), resolve(stage, file), { recursive: true });
const zip = spawnSync("zip", ["-qr", resolve(root, "dist/pysonar2-browser-4.0.0.zip"), "pysonar2-browser-4.0.0"], { cwd: resolve(root, "dist"), stdio: "inherit" });
if (zip.status !== 0) process.exit(zip.status ?? 1);
console.log("Created dist/pysonar2-browser-4.0.0.zip");
