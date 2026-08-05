---
name: pysonar-code-intelligence
description: Use only after ordinary source search leaves unresolved cross-file Python binding, inferred-type, or reference-impact uncertainty that could change the implementation. Do not load for localized work, complete grep results, simple renames with obvious call sites, tests, formatting, non-Python code, or dynamic string/reflection dispatch.
---

# PySonar2 semantic evidence

Use PySonar2 as a bounded consumer of the Python semantic engine. Its purpose is to resolve a concrete
binding or static change-impact uncertainty, not to promise general token savings or replace runtime
validation. Use it to replace broad exploration, never to supplement evidence that is already sufficient.

1. If direct search already identifies the definition and executable call sites, stop using this Skill.
2. Ask one unresolved question with one discovery command:
   - Prefer `pysonar plan --root <root> --symbol <name> --intent <inspect|change> --max-results 8 --format compact-json` when a symbol name is known but its binding or impact is ambiguous.
   - Use `impact` for a known source position and cross-file change planning.
   - Use `context` for a known source position when only definitions, references, or inferred type are needed.
3. Let returned locations and snippets replace further repository-wide searches. Read only source whose exact contents remain necessary; do not bulk-read every candidate again.
4. Stop after one discovery query if results are empty, dynamic, noisy, or do not eliminate at least two likely file reads. Treat inferred types and reference impact as leads, not runtime proof.
5. Validate with focused project tests, lint, or type checks. Run `pysonar check` only when those are unavailable and a semantic diagnostic could change the result.

Do not run `doctor` preemptively. Use it only after an analysis command fails or reports an incompatible schema. PySonar2 analyzes saved files and dynamic imports, reflection, and monkey patching may be incomplete. See `references/cli-schema.md` only when command fields or failures need clarification.
