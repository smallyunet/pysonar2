import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";

const root = resolve(import.meta.dirname, "..");
const manifest = JSON.parse(readFileSync(resolve(root, "manifest.json"), "utf8"));
if (manifest.manifest_version !== 3 || manifest.version !== "4.1.0") throw new Error("invalid manifest");
const expectedIcons = Object.fromEntries([16, 32, 48, 128].map((size) => [String(size), `icons/icon-${size}.png`]));
if (JSON.stringify(manifest.icons) !== JSON.stringify(expectedIcons)) throw new Error("invalid extension icons");
if (manifest.action?.default_icon?.["16"] !== expectedIcons["16"] || manifest.action?.default_icon?.["32"] !== expectedIcons["32"]) {
  throw new Error("invalid action icons");
}
for (const [size, file] of Object.entries(expectedIcons)) {
  const png = readFileSync(resolve(root, file));
  if (png.toString("hex", 0, 8) !== "89504e470d0a1a0a" || png.readUInt32BE(16) !== Number(size) || png.readUInt32BE(20) !== Number(size)) {
    throw new Error(`invalid ${size}x${size} icon: ${file}`);
  }
}
for (const file of ["service-worker.js", "content.js", "worker.js", "sidepanel.js"]) {
  const check = spawnSync(process.execPath, ["--check", resolve(root, file)], { stdio: "inherit" });
  if (check.status !== 0) process.exit(check.status ?? 1);
}
console.log("Browser extension static checks passed");
