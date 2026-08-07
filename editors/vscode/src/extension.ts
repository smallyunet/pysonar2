import * as fs from "node:fs";
import * as path from "node:path";
import { createHash } from "node:crypto";
import * as vscode from "vscode";
import {
  Executable,
  LanguageClient,
  LanguageClientOptions,
  State,
} from "vscode-languageclient/node";

interface ServerStatus {
  state: "starting" | "indexing" | "ready" | "stale" | "error";
  message: string;
  phase?: "discovering" | "analyzing" | "finalizing" | "snapshot" | "up-to-date";
  current?: number;
  total?: number;
  path?: string;
  elapsedMillis?: number;
}

let clients: LanguageClient[] = [];
let output: vscode.OutputChannel;
let statusBar: vscode.StatusBarItem;
const folderStatuses = new Map<string, ServerStatus>();
const stoppingClients = new Set<LanguageClient>();

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  output = vscode.window.createOutputChannel("PySonar2");
  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 30);
  statusBar.command = "pysonar2.showOutput";
  context.subscriptions.push(output, statusBar);

  context.subscriptions.push(
    vscode.commands.registerCommand("pysonar2.showOutput", () => output.show(true)),
    vscode.commands.registerCommand("pysonar2.restartServer", async () => {
      await stopClients();
      await startClients(context);
    }),
    vscode.workspace.onDidChangeWorkspaceFolders(async () => {
      await stopClients();
      await startClients(context);
    }),
    vscode.workspace.onDidChangeConfiguration(async (event) => {
      if (event.affectsConfiguration("pysonar2")) {
        await stopClients();
        await startClients(context);
      }
    }),
  );

  await startClients(context);
}

export async function deactivate(): Promise<void> {
  await stopClients();
}

async function startClients(context: vscode.ExtensionContext): Promise<void> {
  const folders = vscode.workspace.workspaceFolders ?? [];
  if (folders.length === 0) {
    updateStatusBar();
    return;
  }

  const jar = resolveServerJar(context);
  if (!jar) {
    const message = "PySonar2 language server JAR was not found. Run npm run build in editors/vscode.";
    output.appendLine(message);
    void vscode.window.showErrorMessage(message);
    folderStatuses.set("workspace", { state: "error", message });
    updateStatusBar();
    return;
  }

  for (const folder of folders) {
    const client = createClient(context, folder, jar);
    clients.push(client);
    folderStatuses.set(folder.uri.toString(), {
      state: "starting",
      message: `Starting for ${folder.name}`,
    });
    client.onDidChangeState((event) => {
      if (event.newState === State.Stopped && !stoppingClients.has(client)) {
        folderStatuses.set(folder.uri.toString(), {
          state: "error",
          message: `Server stopped for ${folder.name}`,
        });
        updateStatusBar();
      }
    });
    client.onNotification("pysonar2/status", (status: ServerStatus) => {
      folderStatuses.set(folder.uri.toString(), status);
      output.appendLine(`[${folder.name}] ${status.state}: ${status.message}`);
      updateStatusBar();
    });

    try {
      await client.start();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      folderStatuses.set(folder.uri.toString(), { state: "error", message });
      output.appendLine(`[${folder.name}] failed to start: ${message}`);
    }
  }
  updateStatusBar();
}

function createClient(
  context: vscode.ExtensionContext,
  folder: vscode.WorkspaceFolder,
  jar: string,
): LanguageClient {
  const configuration = vscode.workspace.getConfiguration("pysonar2", folder.uri);
  const javaCommand = configuration.get<string>("java.command", "java");
  const maxHeapMb = Math.max(0, Math.floor(configuration.get<number>("java.maxHeapMb", 0)));
  const pythonPath = configuration.get<string>("python.path", "").trim();
  const exclude = configuration.get<string[]>("analysis.exclude", []);
  const diagnosticsMode = configuration.get<string>("diagnostics.mode", "conservative");
  const diagnosticsMaxPerFile = Math.max(
    0,
    Math.floor(configuration.get<number>("diagnostics.maxPerFile", 100)),
  );
  const environment = { ...process.env };
  if (pythonPath) {
    environment.PYSONAR_PYTHON = pythonPath;
  }
  const cacheNamespace = createHash("sha256")
    .update(`${folder.uri.toString()}\n${pythonPath || "python3"}\nast-v1`)
    .digest("hex")
    .slice(0, 24);
  const cacheDirectory = path.join(
    context.globalStorageUri.fsPath,
    "ast-cache",
    "ast-v1",
    cacheNamespace,
  );

  const serverOptions: Executable = {
    command: javaCommand,
    args: [
      ...(maxHeapMb > 0 ? [`-Xmx${maxHeapMb}m`] : []),
      "-cp",
      jar,
      "org.yinwang.pysonar.lsp.Main",
    ],
    options: {
      cwd: folder.uri.fsPath,
      env: environment,
    },
  };

  const watcher = vscode.workspace.createFileSystemWatcher(
    new vscode.RelativePattern(folder, "**/*.py"),
  );
  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      {
        scheme: "file",
        language: "python",
        pattern: "**/*.py",
      },
    ],
    workspaceFolder: folder,
    synchronize: {
      fileEvents: watcher,
    },
    initializationOptions: {
      exclude,
      diagnosticsMode,
      diagnosticsMaxPerFile,
      cacheDirectory,
    },
  };

  return new LanguageClient(
    `pysonar2-${folder.index}`,
    `PySonar2 (${folder.name})`,
    serverOptions,
    clientOptions,
  );
}

function resolveServerJar(context: vscode.ExtensionContext): string | undefined {
  const configured = vscode.workspace
    .getConfiguration("pysonar2")
    .get<string>("server.path", "")
    .trim();
  const candidates = [
    configured,
    context.asAbsolutePath(path.join("server", "pysonar-lsp.jar")),
    path.resolve(context.extensionPath, "..", "..", "target", "pysonar-3.3.5.jar"),
  ].filter(Boolean);
  return candidates.find((candidate) => fs.existsSync(candidate));
}

async function stopClients(): Promise<void> {
  const active = clients;
  clients = [];
  folderStatuses.clear();
  active.forEach((client) => stoppingClients.add(client));
  await Promise.allSettled(active.map((client) => client.stop()));
  active.forEach((client) => stoppingClients.delete(client));
  updateStatusBar();
}

function updateStatusBar(): void {
  if (folderStatuses.size === 0) {
    statusBar.text = "$(search) PySonar2 idle";
    statusBar.tooltip = "Open a workspace containing Python files to start PySonar2.";
    statusBar.show();
    return;
  }

  const statuses = [...folderStatuses.values()];
  const error = statuses.find((status) => status.state === "error");
  const indexing = statuses.find(
    (status) => status.state === "starting" || status.state === "indexing",
  );
  const stale = statuses.find((status) => status.state === "stale");
  const selected = error ?? indexing ?? stale ?? statuses[0];
  const icon = error
    ? "$(error)"
    : indexing
      ? "$(sync~spin)"
      : stale
        ? "$(warning)"
        : "$(check)";
  const progress = selected.state === "indexing" && selected.total && selected.total > 0
    ? ` ${Math.min(100, Math.floor(((selected.current ?? 0) * 100) / selected.total))}%`
    : ` ${selected.state}`;
  statusBar.text = `${icon} PySonar2${progress}`;
  statusBar.tooltip = statuses.map((status) => status.message).join("\n");
  statusBar.show();
}
