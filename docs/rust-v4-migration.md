# Rust v4 migration contract

PySonar2 4.x replaces the Java and CPython-process implementation with a pure Rust semantic core that
targets native executables and WebAssembly. The Java 3.x implementation remains the behavioral oracle
and maintenance line until the Rust release satisfies the gates below.

## Compatibility gates

1. Preserve protocol v1 command names, source positions, exit codes, applicability, confidence,
   truncation, and coverage meanings.
2. Run every legacy reference fixture and focused semantic test through a language-neutral Rust test
   harness, documenting intentional differences.
3. Support the documented Python 3.10-3.14 syntax and semantic matrix without invoking Python.
4. Preserve CodeEngram provider conformance and the public CLI session protocol.
5. Provide native CLI and LSP binaries plus the same semantic core compiled to WebAssembly.
6. Keep analysis fail-closed when parsing, dynamic framework behavior, or resource limits make the
   result incomplete.
7. Compare cold analysis, incremental refresh, peak memory, and WebAssembly bundle size before release.

The frozen protocol lives in [`protocol/v1`](../protocol/v1/README.md). Rust 4.x is a major runtime
release, but schema version 1 remains supported for existing consumers.
