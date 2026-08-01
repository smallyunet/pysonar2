# Change Log

## 0.1.2

- Report discovery, file-level analysis progress, current paths, elapsed time, and finalization phases.
- Show analysis percentage directly in the VS Code status bar.
- Reuse binding/reference projections and per-file line indexes to remove quadratic snapshot work.
- Release analyzer state after snapshot publication and report live/max heap usage.
- Add an optional Java heap setting and actionable out-of-memory errors.

## 0.1.1

- Publish the first stable Visual Studio Marketplace release.
- Bundle the Java Language Server so users do not need to deploy a separate PySonar2 service.
- Clean the Maven build before packaging to keep the bundled server artifact reproducible.

## 0.1.0

- Add a Java Language Server backed by PySonar2 whole-project analysis.
- Provide definition, references, hover, document symbols, workspace symbols, and diagnostics.
- Run one isolated language-server process per VS Code workspace folder.
- Rebuild saved workspace snapshots with configurable exclude patterns.
- Add runtime discovery settings, status feedback, reindex and output commands, demo launch configuration,
  and VSIX packaging.
