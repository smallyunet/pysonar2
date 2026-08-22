import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const extension = resolve(here, "..");
const root = resolve(extension, "../..");
const rustc = spawnSync("rustup", ["which", "--toolchain", "1.88.0", "rustc"], { encoding: "utf8" });
if (rustc.status !== 0) process.exit(rustc.status ?? 1);
const cargo = spawnSync("rustup", ["run", "1.88.0", "cargo", "build", "-p", "pysonar-wasm", "--release", "--target", "wasm32-unknown-unknown"], {
  cwd: root,
  stdio: "inherit",
  env: { ...process.env, RUSTC: rustc.stdout.trim() },
});
if (cargo.status !== 0) process.exit(cargo.status ?? 1);
mkdirSync(resolve(extension, "pkg"), { recursive: true });
const bindgen = process.env.WASM_BINDGEN || "wasm-bindgen";
const result = spawnSync(bindgen, [resolve(root, "target/wasm32-unknown-unknown/release/pysonar_wasm.wasm"), "--target", "web", "--out-dir", resolve(extension, "pkg"), "--no-typescript"], { cwd: root, stdio: "inherit" });
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
