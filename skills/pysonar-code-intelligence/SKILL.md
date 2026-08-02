---
name: pysonar-code-intelligence
description: Analyze definitions, references, inferred types, and reference-based change impact in multi-file Python projects. Use when investigating unfamiliar or weakly typed Python code, planning cross-file changes, or checking semantic impact after saved edits. Do not use for formatting, tests, non-Python projects, or simple single-file changes.
---

# PySonar2 code intelligence

Use the local `pysonar` CLI to collect semantic evidence before reasoning about unfamiliar or weakly typed Python code.

1. Run `pysonar doctor --format json` before the first analysis. If it is unavailable or reports an incompatible schema, stop and explain how to install or update PySonar2.
2. Resolve the saved file and its one-based line and character. PySonar2 analyzes saved workspace state only.
3. Use `pysonar context --root <root> --file <file> --line <line> --character <character> --max-results 50 --format json` to investigate a symbol.
4. Before a cross-file change, use `pysonar impact` with the same location. Treat the result as a definition/reference impact surface, not a complete runtime call graph.
5. After editing Python files, run `pysonar check --root <root> --changed <file> --format json` for each changed file, then run the project's normal tests, lint, and type checker.
6. Treat low-confidence, unknown, truncated, or limited results as leads. Confirm them by reading the referenced source. Never modify code solely from an inferred type.
7. Fall back to direct source inspection, `rg`, and the project's language server when PySonar2 reports incomplete or unsupported semantics.

Keep stdout as JSON input for reasoning. Surface stderr errors and the result's `limitations` to the user when they affect confidence. See `references/cli-schema.md` only when command fields or compatibility behavior need clarification.
