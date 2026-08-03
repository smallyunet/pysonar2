# Change Log

## 0.2.1

- Bundle PySonar2 3.3.0 with compact symbol planning and persistent JSONL analysis sessions.
- Expose batched inspect/change plans for coding agents while clearly labeling lexical fallback results.
- Ship the narrower agent skill and reproducible natural-trigger benchmark artifacts.

## 0.2.0

- Bundle PySonar2 3.2.0 with dedicated support for Python 3.11 exception groups.
- Index Python 3.12 type aliases and generic type parameters, including variadic and parameter-spec forms.
- Preserve Python 3.13 type-parameter defaults and Python 3.14 template-string interpolations.
- Resolve class decorators, metaclass expressions, modern exception causes and binders, and async comprehensions.

## 0.1.3

- Default to conservative diagnostics that show only high-confidence findings and suppress inference uncertainty.
- Report analyzer uncertainty as warnings instead of presenting every finding as a red error.
- Cap diagnostics per file and add `conservative`, `all`, and `off` modes.
- Discover nested Python project/package roots in multi-repository workspaces.
- Bind unresolved imports as unknown values to prevent thousands of cascading name, attribute, and call errors.
- Stop treating type annotations as runtime expressions until annotation-aware inference is implemented.

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
