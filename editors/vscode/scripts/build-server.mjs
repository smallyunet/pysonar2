import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const extensionDirectory = resolve(scriptDirectory, "..");
const repositoryRoot = resolve(extensionDirectory, "../..");

const rustc = spawnSync("rustup", ["which", "--toolchain", "1.88.0", "rustc"], { encoding: "utf8" });
if (rustc.status !== 0) process.exit(rustc.status ?? 1);
const build = spawnSync("rustup", ["run", "1.88.0", "cargo", "build", "--release", "-p", "pysonar-lsp"], {
  cwd: repositoryRoot,
  stdio: "inherit",
  shell: process.platform === "win32",
  env: { ...process.env, RUSTC: rustc.stdout.trim() },
});

if (build.error) {
  throw build.error;
}
if (build.status !== 0) {
  process.exit(build.status ?? 1);
}

const executable = process.platform === "win32" ? "pysonar-lsp.exe" : "pysonar-lsp";
const source = join(repositoryRoot, "target", "release", executable);
const destinationDirectory = join(extensionDirectory, "server");
mkdirSync(destinationDirectory, { recursive: true });
copyFileSync(source, join(destinationDirectory, executable));
copyFileSync(join(repositoryRoot, "LICENSE"), join(extensionDirectory, "LICENSE"));
