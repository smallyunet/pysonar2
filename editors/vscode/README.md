# PySonar2 Type Inference for VS Code

This reference integration exposes PySonar2's whole-project type inference and semantic index in VS
Code. It bundles `pysonar-lsp`; Java and Python are not runtime dependencies, and source code never
leaves the workspace.

Install from the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=smallyu.pysonar2-code-intelligence) or run:

```sh
code --install-extension smallyu.pysonar2-code-intelligence
```

Features:

- go to definition and find references;
- inferred-type hovers;
- document and workspace symbols;
- conservative parse and semantic diagnostics;
- one isolated native server per workspace folder.

Requirements: VS Code 1.91 or newer. The packaged extension contains the native server for its target platform. Development builds may set `pysonar2.server.path` to another `pysonar-lsp` binary.

Commands:

- **PySonar2: Reindex Workspace** restarts the server and rebuilds the index.
- **PySonar2: Show Output** opens runtime and failure details.

Inference is experimental and the index covers statically resolved Python relationships. PySonar2
complements Python formatters, debuggers, standards-focused type checkers, and environment managers; it
does not replace them.
