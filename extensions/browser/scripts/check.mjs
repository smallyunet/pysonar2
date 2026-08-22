import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const manifest = JSON.parse(readFileSync(resolve(root, "manifest.json"), "utf8"));
if (manifest.manifest_version !== 3 || manifest.version !== "4.0.0") throw new Error("invalid manifest");
for (const file of ["service-worker.js", "content.js", "worker.js", "sidepanel.js"]) {
  const check = spawnSync(process.execPath, ["--check", resolve(root, file)], { stdio: "inherit" });
  if (check.status !== 0) process.exit(check.status ?? 1);
}
console.log("Browser extension static checks passed");
