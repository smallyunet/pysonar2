# Changelog

## 4.1.0 — 2026-08-24

PySonar2 returns to the original project's core role: a whole-project type inferencer and semantic
indexer for Python. The release removes the coding-agent Skill and synthetic agent benchmark, while
keeping protocol v1 compatibility for existing consumers.

Highlights:

- split the analyzer and workspace into focused Rust modules without changing the public core API;
- support declared Python source encodings, native `.pyi` discovery, and real virtual-environment
  packages while excluding actual virtual-environment roots;
- improve call, container, field, inheritance, definition, and reference propagation;
- add pinned CPython, Python typing, typeshed, TypeEvalPy, preserved-oracle, and historical-change
  evaluation with auditable per-query records;
- reframe the CLI, LSP, VS Code, browser/WASM, documentation, and package metadata around type
  inference and semantic indexing; and
- publish native CLI/LSP archives, a VSIX, a Chromium/WASM ZIP, and SHA-256 checksums from the release
  workflow.

Measured release status:

- CPython regression corpus: 1,146/1,148 parsed; both failures are intentional invalid-syntax files;
- typeshed mirror: 5,331/5,331 `.pyi` files parsed;
- TypeEvalPy: 386/868 expected types matched exactly (44.47%);
- preserved PySonar2 reference destinations: 285/300 exact (95.00%); and
- historical reference discovery: precision 1.000, recall 0.485 on the full 12-case corpus.

These are bounded measurements, not a claim of standards type-checker conformance, complete Python
runtime modeling, or safe automated refactoring.
