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
| Parameter, return, and variable annotations | Inference | Annotations seed unknown values and parameters; observed runtime types keep precedence. Models PEP 604 unions, `Literal`, `Callable`, `type`, collection, iterator, awaitable, and common metadata wrappers conservatively. |
| Assignment expressions (`:=`) | Inference | The target is bound to the inferred value type. |
| f-strings | Inference | Embedded expressions are visited and the result is `str`. |
| Structural pattern matching | Inference | Sequence, mapping, star, class-keyword, `as`, and OR-pattern captures inherit useful subject or attribute types; positional class patterns without modeled attributes remain conservative. |
| `async def` and `await` | Inference | Async calls produce an `Awaitable[T]`; `await` unwraps direct and union-member awaitables. |
| `async for` and `async with` | Inference | `__aiter__`/`__anext__` and `__aenter__` result types are propagated, including awaitable unwrapping. Opaque third-party protocols remain conservative. |
| Generators and `yield from` | Inference | Generator expressions/functions retain element types; delegated iterable element types flow through `yield from`. Send and return channels are not modeled separately. |
| Comprehensions | Inference | List, dict, set, generator, and async generator element types are inferred in an isolated Python 3 comprehension scope. |
| Class/function decorators and class keywords | Inference | Callable decorator results propagate inside-out for functions and classes; properties, setters, `classmethod`, and `staticmethod` receive focused semantics. Opaque transforms remain conservative. |
| Context managers | Inference | `with ... as` and `async with ... as` use `__enter__`/`__aenter__` result types. |
| Sets, bytes, and ellipsis | Inference | Sets/frozensets retain element types, bytes remain distinct from strings, and ellipsis has its own type. |
| `raise ... from ...` and `except ... as ...` | Navigation | Exception, cause, and handler bindings are preserved. |
| Unknown/newer CPython AST nodes | Traversal fallback | Known descendants remain visible and node kinds are listed in the summary. |

## Python 3.11-3.14 additions

| Feature | Level | Notes |
| --- | --- | --- |
| Exception groups and `except*` (3.11) | Navigation | `TryStar` and handler bindings are preserved; exception-group type splitting is conservative. |
| `type` aliases and generic type parameters (3.12) | Inference | `TypeAlias`, `TypeVar`, `TypeVarTuple`, and `ParamSpec` have dedicated nodes and lexical bindings; alias shape and bounded/default parameter types propagate conservatively. |
| Type-parameter defaults (3.13) | Inference | Bounds and defaults are retained, indexed, and used as conservative seed types. |
| Template strings (3.14) | Inference | Interpolated expressions are visited and the result is string-like. |

## Known semantic gaps

- Advanced annotation semantics such as variance, protocols, overloads, and full generic substitution are not modeled.
- Unbounded type parameters remain conservative unknown types rather than a full generic type algebra; bounds and defaults provide seed types.
- Positional class patterns do not yet interpret arbitrary `__match_args__` values.
- Exception groups preserve control flow and bindings but do not split member types by individual `except*` clauses.
- The built-in and standard-library models still require a separate Python 3 modernization pass.
- Arbitrary descriptor, metaclass, and opaque third-party decorator transformations remain conservative.

Every dedicated AST model should have a focused parser test and, where it creates or resolves names,
an inference/reference assertion. CI interpreter coverage only proves compatibility with the tested
suite; this matrix defines the intended semantic contract.
