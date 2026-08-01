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
| Parameter, return, and variable annotations | Navigation | Annotation names are indexed; annotations do not override inferred runtime types. |
| Assignment expressions (`:=`) | Inference | The target is bound to the inferred value type. |
| f-strings | Inference | Embedded expressions are visited and the result is `str`. |
| Structural pattern matching | Navigation | Value/class expressions and capture bindings are indexed; capture types remain unknown. |
| `async def`, `async for`, `async with`, `await` | Navigation | Nodes and references are preserved; coroutine protocol types are not modeled yet. |
| `yield from` | Navigation | Nodes and references are preserved; generator protocol types are not modeled yet. |
| Comprehensions | Inference | Element/key/value types are inferred; async comprehension semantics are conservative. |
| Unknown/newer CPython AST nodes | Traversal fallback | Known descendants remain visible and node kinds are listed in the summary. |

## Known semantic gaps

- Type annotations are not yet used as inference seeds.
- Pattern captures are bound conservatively as unknown types.
- Generator expressions and sets still use list-like internal approximations.
- `bytes` and ellipsis retain legacy type approximations.
- Python 3.11+ additions such as exception groups and Python 3.12+ type-parameter syntax do not yet
  have dedicated semantic models.
- The built-in and standard-library models still require a separate Python 3 modernization pass.

Every dedicated AST model should have a focused parser test and, where it creates or resolves names,
an inference/reference assertion. CI interpreter coverage only proves compatibility with the tested
suite; this matrix defines the intended semantic contract.
