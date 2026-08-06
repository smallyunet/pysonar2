# PySonar2 Code Intelligence for VS Code

PySonar2 Code Intelligence is the inspection surface for PySonar2's whole-project Python semantic
engine. It brings saved-workspace bindings, references, inferred types, symbols, and conservative
diagnostics into VS Code through a Java language server.

Project links: [interactive web demo](https://smallyunet.github.io/pysonar2/) ·
[VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=smallyu.pysonar2-code-intelligence) ·
[GitHub releases](https://github.com/smallyunet/pysonar2/releases/latest)

## Install

Install the stable release from the
[Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=smallyu.pysonar2-code-intelligence),
search for **PySonar2 Code Intelligence** in VS Code, or run:

```sh
code --install-extension smallyu.pysonar2-code-intelligence
```

The extension bundles the PySonar2 server JAR, so no separate server deployment is required. Analysis
runs on the machine hosting the VS Code workspace extension. In a Remote SSH workspace, Java and Python
must be installed on the remote host.

The extension currently provides:

- go to definition;
- find all references;
- inferred-type and docstring hovers;
- document and workspace symbols;
- conservative semantic diagnostics with cascade suppression; and
- one isolated language-server process per workspace folder.

The extension is designed for semantic inspection while planning or reviewing changes. It complements
existing Python extensions and does not compete with their formatting, debugging, completion, strict
type-checking, or environment-management responsibilities, so keeping the Microsoft Python extension
and Pylance enabled is recommended.

## Requirements

- VS Code 1.91 or newer
- Java 11 or newer
- Python 3.10 or newer

By default the extension runs `java` and `python3` from `PATH`. Use `pysonar2.java.command` and
`pysonar2.python.path` when those runtimes live elsewhere.

## Analysis model

The server indexes the saved files in each workspace folder. After a Python file is saved, it waits
briefly for related file events, compares content hashes, and analyzes the changed files plus their
transitive reverse-import dependents. It then atomically publishes the merged snapshot. Navigation
remains available from the previous completed snapshot while indexing.

Serialized ASTs persist under VS Code's extension global-storage directory and are partitioned by
workspace, selected Python interpreter, and cache format. Unchanged source content is reused across
rebuilds and language-server restarts. Outside VS Code, set `PYSONAR_CACHE_DIR` to choose a durable cache
root; otherwise the CLI uses a persistent namespace under the system temporary directory.

Unsaved changes are marked as `stale` in the status bar. Save the document to refresh semantic results.
Common virtual-environment, dependency, cache, build, and generated directories are excluded by default.
Nested Python package roots and directories containing `pyproject.toml`, `setup.py`, or `setup.cfg` are
added to module resolution automatically, including when the VS Code workspace is a larger monorepo.

During a rebuild, the PySonar2 output channel reports the active phase, elapsed time, discovered file
count, analysis percentage, current workspace-relative path, rebuild mode, analyzed/workspace counts,
and AST-cache hits/misses. The status bar shows the current analysis percentage; select it to open the
detailed output. Progress notifications are throttled so large workspaces remain responsive.

If an unusually large project exhausts the Java heap, first exclude unrelated roots such as tests,
scripts, migrations, or sibling repositories. Increase `pysonar2.java.maxHeapMb` only when the workspace
must be analyzed as one unit. Progress lines include current and maximum heap usage to make that decision
visible rather than speculative.

For a repository that contains several independent Python surfaces, a focused workspace configuration
can avoid indexing code that cannot contribute to application navigation:

```json
{
  "pysonar2.analysis.exclude": ["tests/**", "scripts/**", "alembic/**"],
  "pysonar2.diagnostics.mode": "conservative",
  "pysonar2.diagnostics.maxPerFile": 100,
  "pysonar2.java.maxHeapMb": 2048
}
```

Exclude only roots whose definitions and references you do not need. The heap override is optional; the
optimized snapshot path normally stays well below that limit.

## Commands

- `PySonar2: Reindex Workspace` restarts the per-folder servers and builds fresh indexes.
- `PySonar2: Show Output` opens status and failure details.

## Settings

| Setting | Purpose |
| --- | --- |
| `pysonar2.java.command` | Java 11+ executable used to launch the server. |
| `pysonar2.java.maxHeapMb` | Optional JVM heap limit in MB; `0` keeps the JVM default. |
| `pysonar2.python.path` | Optional Python 3.10+ interpreter. |
| `pysonar2.server.path` | Development override for the bundled server JAR. |
| `pysonar2.analysis.exclude` | Workspace-relative glob patterns omitted from indexing. |
| `pysonar2.diagnostics.mode` | `conservative` shows only high-confidence findings; `all` exposes analyzer warnings; `off` disables diagnostics. |
| `pysonar2.diagnostics.maxPerFile` | Maximum findings published for one file; `0` disables diagnostics. |

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

The prepublish step builds the Java project, copies `target/pysonar-3.3.3.jar` to the extension package as
`server/pysonar-lsp.jar`, bundles the TypeScript client, and produces `pysonar2-code-intelligence.vsix`.
Set `PYSONAR_MAVEN_REPO_LOCAL` when the build needs to use a non-default Maven dependency cache.

The Marketplace release uses the same VSIX artifact produced by this command. Publishing is handled by
the `smallyu` Marketplace publisher; a local build does not contact or depend on a hosted PySonar2 API.

## Current limitations

- Semantic results follow the last saved workspace state; unsaved-buffer overlays are not implemented.
- Incremental invalidation follows static imports. Dynamic imports are conservatively connected to all
  workspace modules, and unsupported backslash-continued import syntax triggers a full rebuild.
- PySonar2's legacy call-driven inference can let callers refine imported function types. Incremental
  snapshots keep the previous complete type projection for unchanged dependency definitions instead of
  replacing it with a partial caller-only view; a future semantic dependency graph can invalidate those
  definitions more precisely.
- Modern Python 3.11-3.14 syntax is indexed, with conservative navigation semantics for exception groups
  and generic type parameters.
- Type annotations are parsed for navigation but are not yet used as complete inference inputs. Conservative
  diagnostics avoid treating unresolved annotation and dependency types as definite code errors.
- Web extensions are not supported because the analyzer starts Java and CPython processes and reads a
  local or remote workspace filesystem.
