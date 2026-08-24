# PySonar2 protocol v1

This directory is the frozen compatibility contract shared by the Java 3.x implementation and the
Rust 4.x rewrite. Implementations may add optional fields, but they must not remove required fields,
change field meanings, change one-based source positions, or reuse an existing command with an
incompatible payload while emitting `schemaVersion: 1`.

## Transport

- One-shot commands write exactly one JSON object followed by a newline to stdout.
- `session` writes and reads newline-delimited JSON, one object per line.
- Progress and human-readable diagnostics use stderr only.
- Paths inside the analyzed root use `/`-separated root-relative names.
- Source positions are one-based lines and one-based characters.
- Exit code `0` means the command completed, including an empty result; `1` is an unexpected analysis
  or I/O failure; `2` is invalid input; `3` is a managed-install safety refusal; `127` means the
  launcher could not find its engine.

Every response requires `schemaVersion`, `cliVersion`, and `command`. Version 1 consumers must ignore
unknown fields.

## Commands

| Command | Required response fields beyond the envelope |
| --- | --- |
| `doctor` | `status`, `capabilities`, `java`, `python` |
| `analyze` | `root`, `fileCount`, `parsedFiles`, `failedFiles`, `symbolCount`, `referenceCount`, `coverageStatus`, `analysisMillis` |
| `plan` | `queries` |
| `context` | `query`, `symbol`, `inferredType`, `definitions`, `references`, `truncated`, `coverageStatus`, `applicable`, `confidence`, `unsupportedSemantics`, `coverage`, `limitations` |
| `impact` | all `context` fields plus `impactKind: "reference-based"`, `affectedFiles` |
| `check` | `changed`, `diagnostics`, `diagnosticCount`, `limitations` |
| `session-ready` | `root`, `fileCount` and all rebuild metrics |
| `refresh` | `fileCount` and all rebuild metrics |
| `quit` | envelope only, plus optional request `id` |
| `error` | `error`, `exitCode` |

The Rust implementation may report runtime fields appropriate to itself in `doctor`; the stable
semantic requirement is that `status` is `ok` only when analysis is ready and `capabilities` contains
the public capability names.

## Semantic rules

- `coverageStatus` is `complete`, `partial`, or `empty`.
- `confidence` is `high`, `partial`, or `unsupported`.
- `context.applicable` means the position resolves in a parsed file and is not governed by known
  unsupported semantics. Partial project coverage is allowed and must produce `confidence: partial`.
- `impact.applicable` additionally requires complete workspace coverage.
- `impact` is reference-based evidence, never a complete runtime call graph.
- `truncated` must propagate when any returned definition, reference, candidate, or fallback occurrence
  was limited.
- Unknown or framework-injected behavior must be visible through `unsupportedSemantics`, `coverage`,
  `limitations`, and fail-closed applicability.

The machine-readable envelope, source-location, coverage, diagnostic, and session-request shapes are
defined by [`schema.json`](schema.json). Canonical session messages live in
[`session.jsonl`](session.jsonl).
