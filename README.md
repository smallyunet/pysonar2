# PySonar2

[![CI](https://github.com/smallyunet/pysonar2/actions/workflows/ci.yml/badge.svg)](https://github.com/smallyunet/pysonar2/actions/workflows/ci.yml)
[![VS Code Marketplace](https://img.shields.io/visual-studio-marketplace/v/smallyu.pysonar2-code-intelligence?label=VS%20Code)](https://marketplace.visualstudio.com/items?itemName=smallyu.pysonar2-code-intelligence)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

**A whole-project type inferencer and semantic indexer for Python, rewritten in Rust.**

PySonar2 analyzes saved `.py` and `.pyi` source without importing modules or executing user code. Its
Rust core produces inferred types, bindings, definitions, references, symbols, module relationships,
parse diagnostics, and explicit coverage limitations for IDEs, code browsers, code search, and other
developer tools.

This repository is a modern reimplementation of the original PySonar2 type inferencer and indexer.
The released Java 3.x implementation is preserved on the
[`java`](https://github.com/smallyunet/pysonar2/tree/java) branch. Protocol v1 remains frozen for
existing integrations.

## Current status

PySonar2 is useful today as an experimental inference and indexing library, not as a standards-focused
type checker or a complete model of Python runtime behavior. The pinned 2026-08-24 conformance run
parses 1,146/1,148 CPython regression sources (the two failures are intentional invalid-syntax
fixtures), parses all 5,331 mirrored typeshed stubs, matches 386/868 TypeEvalPy expected types
(44.47%), and resolves 285/300 preserved reference destinations (95.00%). See the
[full evidence and limitations](docs/conformance-results.md).

## Install

```sh
brew install smallyunet/tap/pysonar2
pysonar --version
pysonar doctor --format json
```

Or download a native archive from the
[latest GitHub release](https://github.com/smallyunet/pysonar2/releases/latest).

The [VS Code extension](https://marketplace.visualstudio.com/items?itemName=smallyu.pysonar2-code-intelligence)
is a reference integration that bundles the native language server. The
[Chromium extension](extensions/browser/README.md) demonstrates the same core in WebAssembly and
performs analysis locally in a Web Worker.

## Queries

```sh
pysonar analyze --root . --format json
pysonar context --root . --file app.py --line 42 --character 8 --format json
pysonar plan --root . --symbol Handler --intent inspect --format compact-json
pysonar session --root . --format json
```

The compatibility protocol also provides `impact` and `check`. `impact` reports the known static
reference surface, not a complete runtime call graph or a safe refactoring boundary. `check` currently
reports parse and load diagnostics; it is not a standards type-checker command. Every response carries
`schemaVersion`, `cliVersion`, and `command`, and source positions are one-based. See
[protocol v1](protocol/v1/README.md).

## Architecture

```text
Python source ──> pysonar-core (Ruff parser + inference + semantic index)
                     ├── pysonar CLI / JSON sessions
                     ├── pysonar-lsp / VS Code reference integration
                     └── pysonar-wasm / Chromium demo
```

Workspace crates:

- `pysonar-core`: workspace discovery, bindings, inference, references, types, and diagnostics;
- `pysonar-protocol`: frozen schema v1 models and response envelopes;
- `pysonar-cli`: one-shot queries, persistent sessions, and demos;
- `pysonar-lsp`: definitions, references, inferred-type hover, symbols, and diagnostics; and
- `pysonar-wasm`: a bounded in-browser virtual workspace API.

## Build and verify

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
python3 -m unittest discover -s benchmarks/conformance -v
cargo build -p pysonar-wasm --target wasm32-unknown-unknown --locked

cd editors/vscode && npm ci && npm run check && npm run build && npm run smoke && npm run package
cd ../../extensions/browser && npm run build && npm run check && npm run package
```

Rust 1.88 is pinned in `rust-toolchain.toml`. The parser is pinned to Ruff 0.11.13 for reproducible
Python 3.10–3.14 syntax support.

## Documentation

- [Product scope and non-goals](docs/product-positioning.md)
- [Python support](docs/python-support.md)
- [Python conformance suites](benchmarks/conformance/README.md)
- [Current conformance results](docs/conformance-results.md)
- [Rust v4 migration contract](docs/rust-v4-migration.md)
- [VS Code reference integration](editors/vscode/README.md)
- [Browser/WASM demo](extensions/browser/README.md)

## License

[Apache License 2.0](LICENSE)
