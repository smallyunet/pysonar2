# PySonar2 product positioning

## Definition

PySonar2 is a local-first, whole-project semantic engine for safe Python changes. It converts a saved
Python workspace into auditable language facts: bindings, definitions, references, inferred types,
import relationships, diagnostics, provenance, confidence, and explicit limitations.

The product promise is not that static analysis knows every runtime behavior. The promise is that tools
which plan, review, or apply Python changes can distinguish analyzer-backed facts, conservative textual
fallbacks, and unresolved dynamic behavior instead of silently conflating them.

## Primary users

- maintainers of large or lightly annotated Python services;
- platform teams performing package moves, API migrations, and deprecation cleanup;
- library and framework maintainers reviewing public-symbol changes;
- builders of refactoring, code-review, CI, indexing, and migration systems; and
- language-neutral change-intelligence products that need a Python semantic provider.

## Primary jobs

1. Resolve which Python binding a source occurrence denotes.
2. Enumerate the known static references and affected import closure for a symbol change.
3. Preserve provenance and confidence when semantic coverage is incomplete.
4. Refresh a saved-workspace snapshot incrementally and atomically.
5. Export stable facts that another system can compare, validate, or turn into a safe write plan.

## Product boundary

PySonar2 owns Python language analysis. It does not own the complete change-intelligence workflow.

| PySonar2 owns | Integrators own |
| --- | --- |
| Python parsing and module discovery | Git and pull-request state |
| Binding and import resolution | Versioned snapshot selection |
| Value and type inference | Cross-language graph orchestration |
| Definitions and static references | Change policy and approval |
| Incremental dependency invalidation | Safe write application and rollback |
| Confidence, provenance, and limitations | Review UI, CI reporting, and workflow automation |

[CodeEngram](https://github.com/smallyunet/code-engram) is the intended higher-level integration for
versioned semantic snapshots, change impact, safe refactoring, and cross-language workflows. PySonar2's
VS Code extension remains an inspection and diagnostic surface for the engine. Agent Skills remain an
experimental consumer of the same contract.

## Non-goals

PySonar2 is not intended to become:

- a replacement for a standards-focused Python type checker;
- a linter, formatter, debugger, completion engine, or environment manager;
- a complete runtime call graph for reflection, monkey patching, or configuration-driven dispatch;
- a general-purpose hosted code-search platform;
- a security query platform; or
- a universal token-saving layer for coding agents.

## Differentiation

PySonar2 should concentrate on whole-project semantic evidence for real, often under-annotated Python
code. Its differentiated output is not another list of diagnostics. It is a reusable fact set that says
what binding was resolved, which static references are known, which files are connected through imports,
what was inferred, what fallback was used, and what remains uncertain.

## Success metrics

Correctness and change safety come before integration efficiency:

1. binding-resolution precision;
2. static-reference precision and recall;
3. missed-reference and wrong-same-name rates on historical changes;
4. import-impact and affected-file recall;
5. safe-rename and API-migration completion under fail-closed gates;
6. confidence calibration for incomplete or dynamic behavior;
7. incremental refresh latency, memory, and invalidation scope; and
8. downstream time, token, or cost only when correctness is equal.

## Roadmap direction

1. Stabilize a versioned semantic-fact export contract with provenance and confidence.
2. Improve alias, module-attribute, inheritance, override, and public-symbol reference coverage.
3. Add diff-aware analyzer outputs needed by safe rename and API migration consumers.
4. Complete the PySonar2 provider for CodeEngram's normalized semantic model.
5. Validate the provider against historical Python refactors and migrations, not only synthetic agent tasks.
6. Keep LSP, static browser, CLI, and Agent Skill as consumers of the same engine contract.
