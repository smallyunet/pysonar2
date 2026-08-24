import { cpSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const { version } = JSON.parse(readFileSync(resolve(root, "manifest.json"), "utf8"));
const archiveName = `pysonar2-browser-${version}`;
const stage = resolve(root, `dist/${archiveName}`);
rmSync(stage, { recursive: true, force: true });
mkdirSync(stage, { recursive: true });
for (const file of ["manifest.json", "service-worker.js", "content.js", "content.css", "worker.js", "sidepanel.html", "sidepanel.css", "sidepanel.js", "icons", "pkg"]) cpSync(resolve(root, file), resolve(stage, file), { recursive: true });
const zip = spawnSync("zip", ["-qr", resolve(root, `dist/${archiveName}.zip`), archiveName], { cwd: resolve(root, "dist"), stdio: "inherit" });
if (zip.status !== 0) process.exit(zip.status ?? 1);
console.log(`Created dist/${archiveName}.zip`);
