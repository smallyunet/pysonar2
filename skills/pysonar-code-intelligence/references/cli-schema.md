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

`plan` accepts one or more repeated `--symbol` values and analyzes the project once. Each query contains semantic exact-name candidates plus explicitly labeled `exact-identifier-text` occurrences as a conservative fallback for incomplete alias or attribute references. For `--intent change`, it also returns the fallback affected-file shortlist. Treat occurrences as candidates, not proof that same-named symbols share a binding. `--format compact-json` omits verbose timing, root, kinds, inferred types, and repeated limitations.

`session` keeps one `AnalysisSession` alive over newline-delimited JSON. It first emits `session-ready`, then accepts `{"command":"plan","symbol":"name","intent":"inspect|change","maxResults":8}`, `{"command":"refresh"}`, and `{"command":"quit"}`. `symbol` may be a string or array. Plans reuse the current immutable snapshot; `refresh` performs an explicit atomic whole-workspace rebuild after saved edits.

`check` returns conservative diagnostics, optionally filtered by repeated or comma-separated `--changed` paths. It is an exception path when focused project validation is unavailable, not a default follow-up to analysis. It does not replace tests, lint, or a strict type checker.
