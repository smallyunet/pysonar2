# PySonar2 Code Intelligence for VS Code

PySonar2 Code Intelligence brings PySonar2's whole-project Python type inference and cross-file index
into VS Code through a Java language server.

The extension currently provides:

- go to definition;
- find all references;
- inferred-type and docstring hovers;
- document and workspace symbols;
- semantic diagnostics; and
- one isolated language-server process per workspace folder.

PySonar2 complements existing Python extensions. It does not provide formatting, debugging, completion,
or environment management, so keeping the Microsoft Python extension and Pylance enabled is recommended.

## Requirements

- VS Code 1.91 or newer
- Java 11 or newer
- Python 3.10 or newer

By default the extension runs `java` and `python3` from `PATH`. Use `pysonar2.java.command` and
`pysonar2.python.path` when those runtimes live elsewhere.

## Analysis model

The server indexes the saved files in each workspace folder. After a Python file is saved, it waits
briefly for related file events, performs a serialized workspace rebuild, and atomically publishes the
new index. Navigation remains available from the previous completed snapshot while indexing.

Unsaved changes are marked as `stale` in the status bar. Save the document to refresh semantic results.
Common virtual-environment, dependency, cache, build, and generated directories are excluded by default.

## Commands

- `PySonar2: Reindex Workspace` restarts the per-folder servers and builds fresh indexes.
- `PySonar2: Show Output` opens status and failure details.

## Settings

| Setting | Purpose |
| --- | --- |
| `pysonar2.java.command` | Java 11+ executable used to launch the server. |
| `pysonar2.python.path` | Optional Python 3.10+ interpreter. |
| `pysonar2.server.path` | Development override for the bundled server JAR. |
| `pysonar2.analysis.exclude` | Workspace-relative glob patterns omitted from indexing. |

Changing a PySonar2 setting restarts the language server so the new runtime and file-selection settings
take effect consistently.

## Run the repository demo

From `editors/vscode`:

```sh
npm install
npm run build
code .
```

Press `F5` and choose **Run PySonar2 Extension Demo**. The Extension Development Host opens
`../../demo_project`. Try these flows:

1. Open `main.py` and hover over `DemoApp` or `report`.
2. Use **Go to Definition** on `DemoApp`, `build_report`, or `prediction.summary`.
3. Use **Find All References** on `Market`, `Prediction`, or `weighted_signal`.
4. Open the Outline view or run **Go to Symbol in Workspace**.
5. Save a Python file and watch the PySonar2 status item move from `indexing` to `ready`.

You can also open `demo.code-workspace` to inspect the extension and Python demo side by side.

To verify the packaged Java server over real stdio JSON-RPC without opening VS Code, run:

```sh
npm run build
npm run smoke
```

## Build a VSIX

```sh
npm install
npm run package
```

The prepublish step builds the Java project, copies `target/pysonar-3.1.0.jar` to the extension package as
`server/pysonar-lsp.jar`, bundles the TypeScript client, and produces `pysonar2-code-intelligence.vsix`.
Set `PYSONAR_MAVEN_REPO_LOCAL` when the build needs to use a non-default Maven dependency cache.

## Current limitations

- Semantic results follow the last saved workspace state; unsaved-buffer overlays are not implemented.
- Rebuilds are whole-workspace rather than dependency-graph incremental.
- Python 3.11+ syntax may parse successfully even when a feature only has navigation or traversal-level
  semantics in PySonar2.
- Web extensions are not supported because the analyzer starts Java and CPython processes and reads a
  local or remote workspace filesystem.
