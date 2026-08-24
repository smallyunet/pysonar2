# Python conformance results

## Result first

The 2026-08-24 pinned run shows that PySonar2 4.0.0 now loads declared Python source encodings and
native stubs robustly, and recovers most of its preserved definition/reference oracle. Type inference
and flow-sensitive reaching definitions remain incomplete, so this is evidence of measured coverage,
not a general correctness proof or safe-refactoring guarantee.

| Evidence | Result | Valid interpretation |
| --- | ---: | --- |
| CPython 3.14.7 `Lib/test` | 1,146/1,148 parsed | both failures are intentional `badsyntax` fixtures; declared non-UTF-8 encodings load natively |
| Python typing conformance tree | 155/155 parsed | includes 151 `.py` plus 4 native `.pyi`; strict diagnostic conformance remains out of scope and unscored |
| typeshed isolated mirror | 5,331/5,331 native `.pyi` parsed | native stub discovery works, including `stdlib/venv`; parser acceptance is not typing conformance |
| TypeEvalPy micro source | 207/207 parsed | complete parser acceptance for the hand-authored corpus |
| TypeEvalPy type gold | 388/868 exact (44.70%) | 383 wrong and 97 missing inferred types; broad type-inference correctness is not established |
| TypeEvalPy Autogen | 7,121/7,121 parsed | generated-corpus scale and robustness only, not independent semantic proof |
| Preserved PySonar2 references | 285/300 exact destinations (95.00%) | remaining mismatches are concentrated in point-sensitive reaching definitions |
| Preserved PySonar2 types | 133/300 exact (44.33%) | substantially improved, but Java-era inferred-type compatibility remains partial |
| Historical changes | 1/12 safe-complete | precision 1.000, recall 0.485; high precision but insufficient recall |

TypeEvalPy still records no exact matches in context-sensitivity, field-sensitivity (all depths),
inter-procedural, object-sensitivity, or path-sensitivity. The strongest categories now include
classes (75/122), assignments (58/82), dictionaries (48/107), lists (33/60), MRO (23/34), and
functions (22/37).

The historical modern-Python slice is healthier: 1/4 safe-complete, precision 1.000, recall 0.844,
and F1 0.915. All eight legacy cases fail closed because workspace coverage is partial. This is useful
conservative behavior, but fail-closed reporting does not recover missing references.

## Reproduction and evidence

The unified runner and exact upstream commits are documented in
[`benchmarks/conformance`](../benchmarks/conformance/README.md). The complete per-query results are:

- [`benchmarks/conformance/results/2026-08-24.json`](../benchmarks/conformance/results/2026-08-24.json);
- [`benchmarks/change-safety/results/2026-08-24-rust-v4.json`](../benchmarks/change-safety/results/2026-08-24-rust-v4.json).

The result categories are intentionally separate:

- parser coverage measures accepted source files and robustness, not semantic correctness;
- TypeEvalPy scores normalized inferred types against an external gold set;
- preserved reference fixtures score exact definition destinations and types independently; and
- historical changes score candidate edit surfaces against real upstream commits, with applicability
  required for `safeComplete`.

## Current priority gaps

1. Add point-sensitive reaching definitions so exhaustive branches, loop updates, and overwritten
   parameters do not expose every definition globally.
2. Improve container key/element tracking, generator yield types, decorators, and higher-order calls.
3. Add context-, object-, field-, inter-procedural-, and path-sensitive inference before claiming
   broad typing coverage.
4. Recover the remaining historical recall while preserving the current zero-false-positive result.
