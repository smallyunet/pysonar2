---
name: pysonar-code-intelligence
description: Resolve definitions, references, inferred types, and reference-based change impact when semantic uncertainty in an unfamiliar multi-file Python project could change the implementation. Use for ambiguous symbols, inheritance, aliases, or weakly typed cross-file flows. Do not use for well-localized tasks, formatting, tests, non-Python projects, or changes whose call sites are already clear from direct source search.
---

# PySonar2 code intelligence

Use the local `pysonar` CLI only when its semantic evidence can answer a concrete unresolved question. PySonar2 reindexes the saved project for every CLI invocation, so minimize invocations and output.

1. Search the relevant source with `rg` first. Skip PySonar2 when definitions and call sites are already clear or the requested change is well localized.
2. When a semantic question remains, run `pysonar doctor --format json` once per task before the first analysis. If it is unavailable or reports an incompatible schema, continue with direct source inspection and mention the limitation; do not block the task.
3. Resolve one saved file location that best represents the uncertainty. PySonar2 uses one-based line and character positions and analyzes saved workspace state only.
4. Run exactly one discovery query for that decision point:
   - Use `pysonar impact --root <root> --file <file> --line <line> --character <character> --max-results 12 --format json` for a cross-file change. `impact` already includes the context fields; do not also run `context` at the same location.
   - Use `pysonar context --root <root> --file <file> --line <line> --character <character> --max-results 12 --format json` only when definitions, references, or inferred type are needed without change-impact planning.
5. Read only the returned source locations that can affect the implementation. If `truncated` is true, increase `--max-results` or query another location only when the omitted evidence could change the decision.
6. After saved edits, run at most one batch diagnostic pass for all changed Python files: `pysonar check --root <root> --changed <file1> --changed <file2> --format json`. Then run focused project tests and any relevant lint or type checks. Skip `check` when PySonar2 was skipped during discovery or when project validation already supersedes its conservative diagnostics.
7. Treat inferred types and reference impact as leads, not proof. Confirm material claims in source. Fall back to direct inspection and the project's language server when results are unknown, incomplete, dynamic, or unsupported.

Do not repeat a command merely to confirm the same evidence. Stop using PySonar2 for the task when a query is slow, noisy, or fails to reduce uncertainty. Keep stdout as JSON input for reasoning, and surface errors or `limitations` only when they affect the result. See `references/cli-schema.md` only when command fields or compatibility behavior need clarification.
