# Python support

PySonar2 4 parses Python 3.10–3.14 syntax in-process with Ruff 0.11.13. It does not import modules,
execute source, or require an installed Python interpreter. Both `.py` and `.pyi` files participate in
workspace discovery and indexing.

## Semantic coverage

| Area | Status |
| --- | --- |
| Modules, functions, classes, parameters, assignments | Indexed |
| Absolute and relative imports | Indexed conservatively |
| Cross-file definitions and references | Indexed for statically resolved bindings |
| Literals, annotations, assignments, calls, attributes, and containers | Inferred experimentally |
| Declared source encodings | Supported |
| Native `.pyi` stub discovery | Supported |
| UTF-16 editor positions | Supported |
| Parse and source-load diagnostics | Supported |
| Flow-, context-, object-, field-, and path-sensitive inference | Incomplete |
| Reflection, monkey patching, dynamic imports, generated members | Incomplete |
| Runtime call graphs and standards type-checker diagnostics | Out of scope |

Syntax acceptance is not a promise of complete type semantics. Definitions and references represent
the known static index, not every possible runtime target. Queries expose `coverageStatus`,
`confidence`, `applicable`, `unsupportedSemantics`, and `limitations` so consumers can preserve that
boundary.
