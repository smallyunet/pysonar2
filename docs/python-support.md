# Python support

PySonar2 4 parses Python 3.10–3.14 syntax in-process with Ruff 0.11.13. It does not import modules, execute source, or require an installed Python interpreter.

## Semantic coverage

| Area | Status |
| --- | --- |
| Modules, functions, classes, parameters, assignments | Indexed |
| Absolute and relative imports | Indexed conservatively |
| Cross-file definitions and references | Indexed for statically resolved bindings |
| Basic literal and declaration types | Inferred conservatively |
| UTF-16 editor positions | Supported |
| Parse diagnostics | Supported |
| Reflection, monkey patching, dynamic imports | Not complete |
| Framework dependency injection and generated members | Reported as unsupported when detected |
| Runtime call graph and strict type checking | Out of scope |

Syntax acceptance is not a promise of complete type semantics. Queries expose `coverageStatus`, `confidence`, `applicable`, `unsupportedSemantics`, and `limitations`. An inapplicable or partial impact response must not be used as a complete safe-change boundary.
