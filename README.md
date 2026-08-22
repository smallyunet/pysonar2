# PySonar2

[![CI](https://github.com/smallyunet/pysonar2/actions/workflows/ci.yml/badge.svg)](https://github.com/smallyunet/pysonar2/actions/workflows/ci.yml)
[![VS Code Marketplace](https://img.shields.io/visual-studio-marketplace/v/smallyu.pysonar2-code-intelligence?label=VS%20Code)](https://marketplace.visualstudio.com/items?itemName=smallyu.pysonar2-code-intelligence)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**Local-first whole-project Python semantics, rewritten in Rust.**

PySonar2 4 indexes Python source without executing it or starting Java or Python. One Rust semantic core powers the native CLI, language server, and WebAssembly browser runtime. It returns definitions, references, inferred types, symbols, conservative diagnostics, and explicit coverage limitations for change inspection and automation.

The Java 3.x implementation is preserved on the [`java`](https://github.com/smallyunet/pysonar2/tree/java) branch. Protocol v1 remains frozen for existing consumers such as CodeEngram.

## Install

```sh
brew install smallyunet/tap/pysonar2
pysonar --version
pysonar doctor --format json
```

Or download a native archive from the [latest GitHub release](https://github.com/smallyunet/pysonar2/releases/latest).

The [VS Code extension](https://marketplace.visualstudio.com/items?itemName=smallyu.pysonar2-code-intelligence) bundles the native language server. The [Chromium extension](extensions/browser/README.md) analyzes Python snippets entirely inside a Web Worker with WebAssembly; it performs no network requests.

## Semantic queries

```sh
pysonar plan --root . --symbol Handler --intent change --format compact-json
pysonar context --root . --file app.py --line 42 --character 8 --format json
pysonar impact --root . --file app.py --line 42 --character 8 --format json
pysonar check --root . --changed app.py --format json
```

For several queries, `pysonar session --root . --format json` keeps a snapshot alive over newline-delimited JSON. Every response carries `schemaVersion`, `cliVersion`, and `command`; source positions are one-based. See [protocol v1](protocol/v1/README.md).

Impact is reference-based evidence, not a complete runtime call graph. Parse failures, resource limits, framework injection, reflection, monkey patching, and unresolved dynamic behavior are reported through coverage, confidence, applicability, and limitations rather than silently treated as safe.

## Architecture

```text
Python source ──> pysonar-core (Ruff parser + semantic index)
                     ├── pysonar CLI / JSON sessions
                     ├── pysonar-lsp / VS Code
                     └── pysonar-wasm / Chromium side panel
```

Workspace crates:

- `pysonar-core`: workspace model, bindings, references, types, diagnostics;
- `pysonar-protocol`: frozen schema v1 models and envelopes;
- `pysonar-cli`: one-shot queries, persistent sessions, demos, and agent Skill management;
- `pysonar-lsp`: definitions, references, hover, symbols, and diagnostics;
- `pysonar-wasm`: bounded in-browser virtual workspace API.

## Build and verify

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo build -p pysonar-wasm --target wasm32-unknown-unknown

cd editors/vscode && npm ci && npm run check && npm run build && npm run smoke
cd ../../extensions/browser && npm run build && npm run check && npm run package
```

Rust 1.88 is pinned in `rust-toolchain.toml`. The parser is pinned to Ruff 0.11.13 for reproducible Python 3.10–3.14 syntax support.

## Documentation

- [Rust v4 migration contract](docs/rust-v4-migration.md)
- [Python support](docs/python-support.md)
- [Product role and non-goals](docs/product-positioning.md)
- [Agent Skill](skills/pysonar-code-intelligence/SKILL.md)
- [VS Code extension](editors/vscode/README.md)
- [Browser extension](extensions/browser/README.md)

## License

[Apache License 2.0](LICENSE)
