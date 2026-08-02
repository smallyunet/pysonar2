# PySonar2 agent CLI contract

All machine-readable commands emit one JSON object to stdout. Progress and errors use stderr. Paths in successful results are relative to `root` when they are inside the analyzed project.

Every response includes:

- `schemaVersion`: integer compatibility version. Consumers support schema version `1`.
- `cliVersion`: installed PySonar2 version.
- `command`: executed command or `error`.

Exit codes:

- `0`: success, including an empty result.
- `1`: unexpected analysis or I/O failure.
- `2`: invalid arguments or unsupported options.
- `3`: installation safety check refused an unmanaged overwrite or removal.
- `127`: launcher could not locate the PySonar2 JAR.

`context` returns the query, symbol text, inferred hover text, definitions, references, truncation status, and limitations.

`impact` is a superset of `context`: it returns the same evidence plus `impactKind: reference-based` and `affectedFiles`. Do not call both commands for the same location. It does not claim complete dynamic call-graph coverage.

`check` returns conservative diagnostics, optionally filtered by repeated or comma-separated `--changed` paths. Batch all changed Python files into one invocation because each CLI command performs a fresh project analysis. It does not replace tests, lint, or a strict type checker.
