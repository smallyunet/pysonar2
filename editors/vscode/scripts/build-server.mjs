import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const extensionDirectory = resolve(scriptDirectory, "..");
const repositoryRoot = resolve(extensionDirectory, "../..");

const mavenArguments = ["-DskipTests", "clean", "package"];
if (process.env.PYSONAR_MAVEN_REPO_LOCAL) {
  mavenArguments.unshift(`-Dmaven.repo.local=${process.env.PYSONAR_MAVEN_REPO_LOCAL}`);
}

const build = spawnSync("mvn", mavenArguments, {
  cwd: repositoryRoot,
  stdio: "inherit",
  shell: process.platform === "win32",
});

if (build.error) {
  throw build.error;
}
if (build.status !== 0) {
  process.exit(build.status ?? 1);
}

const source = join(repositoryRoot, "target", "pysonar-3.3.3.jar");
const destinationDirectory = join(extensionDirectory, "server");
mkdirSync(destinationDirectory, { recursive: true });
copyFileSync(source, join(destinationDirectory, "pysonar-lsp.jar"));
copyFileSync(join(repositoryRoot, "LICENSE"), join(extensionDirectory, "LICENSE"));
