# Rust v4 migration contract

PySonar2 4 replaces the Java engine and CPython parser process with a Rust workspace. The released Java 3.x line is preserved on the `java` branch and by the `v3.4.0` tag; `main` contains only the Rust implementation.

## Frozen compatibility

- Protocol schema version remains `1`.
- Command names, one-based positions, response envelopes, exit-code meanings, coverage, confidence, applicability, and truncation retain their documented meanings.
- Existing clients must ignore additive fields.
- Results fail closed when parse coverage or dynamic semantics are incomplete.

## Runtime changes

| 3.x | 4.x |
| --- | --- |
| Java 11+ engine | Native Rust engine |
| CPython subprocess parser | In-process Ruff parser |
| JAR/Java LSP | Native `pysonar-lsp` |
| Desktop/server only | Native and WebAssembly |

The Rust engine intentionally starts with a smaller, auditable semantic surface than the historical Java inference engine. Protocol compatibility does not imply identical inference for every legacy fixture. The preserved `.test` corpus remains a compatibility oracle; differences must stay visible through tests and release notes rather than being hidden.

## Release gates

The repository verifies formatting, Clippy, workspace tests, legacy Python-corpus parsing, protocol fixtures, CLI/session behavior, native LSP smoke behavior, the WASM target, browser packaging, and VS Code packaging. Cross-platform native archives are produced by the release workflow.
