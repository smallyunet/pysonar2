import { spawn } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const extensionDirectory = resolve(scriptDirectory, "..");
const repositoryRoot = resolve(extensionDirectory, "../..");
const demoRoot = join(repositoryRoot, "demo_project");
const jar = join(extensionDirectory, "server", "pysonar-lsp.jar");

const server = spawn("java", ["-cp", jar, "org.yinwang.pysonar.lsp.Main"], {
  cwd: demoRoot,
  stdio: ["pipe", "pipe", "inherit"],
});

let buffer = Buffer.alloc(0);
let ready = false;
let definitionRequested = false;

const timeout = setTimeout(() => fail("Timed out waiting for the language server"), 20_000);

server.stdout.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  drainMessages();
});
server.on("error", (error) => fail(error.message));
server.on("exit", (code) => {
  if (!ready && code !== 0) {
    fail(`Language server exited with code ${code}`);
  }
});

send({
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: {
    processId: process.pid,
    rootUri: pathToFileURL(demoRoot).toString(),
    capabilities: {},
    workspaceFolders: [
      {
        uri: pathToFileURL(demoRoot).toString(),
        name: "demo_project",
      },
    ],
    initializationOptions: { exclude: [] },
  },
});

function drainMessages() {
  while (true) {
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd < 0) {
      return;
    }
    const header = buffer.subarray(0, headerEnd).toString("ascii");
    const match = /Content-Length: (\d+)/i.exec(header);
    if (!match) {
      fail(`Invalid language-server header: ${header}`);
      return;
    }
    const contentLength = Number(match[1]);
    const messageEnd = headerEnd + 4 + contentLength;
    if (buffer.length < messageEnd) {
      return;
    }
    const body = buffer.subarray(headerEnd + 4, messageEnd).toString("utf8");
    buffer = buffer.subarray(messageEnd);
    handle(JSON.parse(body));
  }
}

function handle(message) {
  if (message.id === 1) {
    if (!message.result?.capabilities?.definitionProvider) {
      fail("Server did not advertise definition support");
      return;
    }
    if (message.result.capabilities.textDocumentSync?.save !== true) {
      fail("Server did not register save notifications");
      return;
    }
    send({ jsonrpc: "2.0", method: "initialized", params: {} });
    return;
  }

  if (message.method === "pysonar2/status" && message.params?.state === "ready") {
    ready = true;
    if (!definitionRequested) {
      definitionRequested = true;
      send({
        jsonrpc: "2.0",
        id: 2,
        method: "textDocument/definition",
        params: {
          textDocument: { uri: pathToFileURL(join(demoRoot, "main.py")).toString() },
          position: { line: 2, character: 28 },
        },
      });
    }
    return;
  }

  if (message.id === 2) {
    const definitions = Array.isArray(message.result) ? message.result : [];
    if (definitions.length === 0) {
      fail("Definition request returned no locations");
      return;
    }
    console.log(`LSP smoke test passed: indexed demo and resolved ${definitions[0].uri}`);
    send({ jsonrpc: "2.0", id: 3, method: "shutdown", params: null });
    return;
  }

  if (message.id === 3) {
    send({ jsonrpc: "2.0", method: "exit", params: null });
    clearTimeout(timeout);
  }
}

function send(message) {
  const body = Buffer.from(JSON.stringify(message), "utf8");
  server.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
  server.stdin.write(body);
}

function fail(message) {
  clearTimeout(timeout);
  server.kill();
  console.error(`LSP smoke test failed: ${message}`);
  process.exitCode = 1;
}
