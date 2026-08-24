# PySonar2 product positioning

## Definition

PySonar2 is a whole-project type inferencer and semantic indexer for Python, rewritten in Rust. It
turns saved Python source into reusable language facts: inferred types, bindings, definitions,
references, symbols, module relationships, parse diagnostics, and explicit limitations.

The core is a library and analysis engine. The CLI, language server, VS Code extension, and browser
extension are interfaces and reference integrations around that core.

## Primary users

- authors of IDEs and Python developer tools;
- builders of code browsers, code search, documentation, and repository indexing systems;
- researchers evaluating whole-project Python type inference; and
- maintainers who need a local, embeddable semantic index for lightly annotated code.

## Primary jobs

1. Infer useful types from Python source, annotations, assignments, calls, and cross-file relationships.
2. Resolve which binding a source occurrence denotes.
3. Index definitions, references, symbols, modules, and source locations across a workspace.
4. Export stable, machine-readable facts through Rust APIs, JSON, LSP, and WebAssembly.
5. Report parse failures and unsupported dynamic behavior without presenting gaps as complete results.

## Product boundary

| PySonar2 owns | Consumers own |
| --- | --- |
| Python source discovery and parsing | Editing and refactoring operations |
| Type and value inference | Approval, rollback, and change policy |
| Bindings, definitions, and references | Standards-focused type diagnostics |
| Module and symbol indexing | Runtime validation and test execution |
| Index serialization and query APIs | Product UI and workflow orchestration |
| Coverage and limitation reporting | Hosted storage and repository history |

`pysonar-core` is the product center. The CLI provides scriptable access and a persistent session
transport. The LSP, VS Code, and browser packages demonstrate how consumers can use the index; they do
not redefine the project as an IDE or browser product.

## Non-goals

PySonar2 is not currently intended to be:

- a standards-conforming replacement for Pyright, mypy, or other Python type checkers;
- a proof of safe automated refactoring or a complete runtime call graph;
- a linter, formatter, debugger, completion engine, or environment manager;
- an end-user IDE or hosted code-search service.

## Evidence and claims

Correctness claims must come from versioned, reproducible suites. Parser acceptance, type inference,
definition/reference resolution, and historical change recall are scored separately because success in
one category does not establish another. Current published results are in
[`conformance-results.md`](conformance-results.md).

The project may claim the exact measured results. It must not describe parser acceptance as typing
conformance, static references as complete runtime behavior, or partial inference as general Python
correctness.

## Roadmap direction

1. Recover and exceed the original PySonar2 semantic behavior on the preserved oracle.
2. Improve flow-sensitive reaching definitions, call propagation, containers, decorators, generators,
   inheritance, and module attributes.
3. Expand external type-inference and reference-resolution evaluation with pinned upstream data.
4. Stabilize an efficient, documented semantic-index API and serialized format.
5. Improve dependency-environment and typeshed integration while preserving source-only operation.
6. Add incremental indexing only after invalidation correctness is covered by dedicated tests.
