# PySonar2 demo project

This dependency-free project demonstrates PySonar2 across three representative surfaces:

- cross-file navigation through imports, re-exports, aliases, properties, and C3 inheritance;
- type flow through annotations, constructors, typed sets/generators, recursion, decorators, context
  managers, and async protocols;
- modern syntax including keyword-only parameters, walrus bindings, structural matching, captures, and
  typed comprehensions and pattern-capture propagation.

Generate the static site from the repository root:

```sh
brew install smallyunet/tap/pysonar2
pysonar demo_project ./demo-html
```

Open `demo-html/index.html` in a browser or publish the directory on any static host.
The current generated demo is available at <https://smallyunet.github.io/pysonar2/>.

## VS Code language-server demo

The same project is used by the repository's VS Code extension demo. From `editors/vscode`, install and
build the extension, then press `F5` using the **Run PySonar2 Extension Demo** launch configuration:

```sh
cd editors/vscode
npm ci
npm run build
code .
```

In the Extension Development Host, hover over inferred values in `main.py`, follow `PredictionEngine`
and `build_report` across modules, find references to `Market`, `display_name`, or `adjust`, and save a
file to watch the workspace index refresh. For the newer guided examples, follow `inspect_symbol`
through its decorator factory, inspect both `normalize_symbol` declarations, and navigate the `label`
and `captured` bindings in `syntax.py`.
