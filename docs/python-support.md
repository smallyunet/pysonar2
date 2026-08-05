# Python language support

PySonar2 uses the selected CPython interpreter for syntax parsing, then converts CPython's AST into
its own semantic model. A Python version being accepted by the parser does not imply that every
language feature has complete type semantics.

The labels in this matrix mean:

- **Inference**: definitions, references, and useful inferred types are modeled.
- **Navigation**: definitions and references are preserved, but type semantics are intentionally
  conservative.
- **Traversal fallback**: the outer node is not modeled, but recognized child expressions are still
  visited and the unsupported node kind is reported in the analysis summary.

## Python 3.10 baseline

| Feature | Level | Notes |
| --- | --- | --- |
| Functions, classes, imports, closures, calls | Inference | Core whole-project analysis path. |
| Positional-only and keyword-only parameters | Inference | Parameter binding and displayed signatures preserve `/` and `*`. |
| Parameter, return, and variable annotations | Inference | Annotations seed unknown values and parameters; observed runtime types keep precedence. Basic class, `list`, `dict`, `tuple`, `Optional`, and `Union` forms are modeled conservatively. |
| Assignment expressions (`:=`) | Inference | The target is bound to the inferred value type. |
| f-strings | Inference | Embedded expressions are visited and the result is `str`. |
| Structural pattern matching | Navigation | Value/class expressions and capture bindings are indexed; capture types remain unknown. |
| `async def` and `await` | Inference | Async calls produce an `Awaitable[T]` and `await` unwraps `T`; async iteration and context-manager protocols remain conservative. |
| `async for` and `async with` | Navigation | Nodes and references are preserved; async iterator/context-manager protocol types are not modeled yet. |
| `yield from` | Navigation | Nodes and references are preserved; generator protocol types are not modeled yet. |
| Comprehensions | Inference | Element/key/value types are inferred; async comprehension semantics are conservative. |
| Class/function decorators and class keywords | Navigation | Decorator, base, and metaclass expressions are indexed; `property`, `classmethod`, and `staticmethod` receive focused call/attribute semantics while arbitrary decorator transforms remain conservative. |
| `raise ... from ...` and `except ... as ...` | Navigation | Exception, cause, and handler bindings are preserved. |
| Unknown/newer CPython AST nodes | Traversal fallback | Known descendants remain visible and node kinds are listed in the summary. |

## Python 3.11-3.14 additions

| Feature | Level | Notes |
| --- | --- | --- |
| Exception groups and `except*` (3.11) | Navigation | `TryStar` and handler bindings are preserved; exception-group type splitting is conservative. |
| `type` aliases and generic type parameters (3.12) | Navigation | `TypeAlias`, `TypeVar`, `TypeVarTuple`, and `ParamSpec` have dedicated nodes and lexical bindings. |
| Type-parameter defaults (3.13) | Navigation | Bounds and default expressions are retained and indexed. |
| Template strings (3.14) | Inference | Interpolated expressions are visited and the result is string-like. |

## Known semantic gaps

- Advanced annotation semantics such as variance, protocols, overloads, and full generic substitution are not modeled.
- Pattern captures are bound conservatively as unknown types.
- Type parameters are lexically scoped but remain conservative unknown types rather than a full generic type algebra.
- Exception groups preserve control flow and bindings but do not split member types by individual `except*` clauses.
- Generator expressions and sets still use list-like internal approximations.
- `bytes` and ellipsis retain legacy type approximations.
- The built-in and standard-library models still require a separate Python 3 modernization pass.
- Property setters and arbitrary descriptor transformations remain conservative.

Every dedicated AST model should have a focused parser test and, where it creates or resolves names,
an inference/reference assertion. CI interpreter coverage only proves compatibility with the tested
suite; this matrix defines the intended semantic contract.
