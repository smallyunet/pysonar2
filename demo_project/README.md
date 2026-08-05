# PySonar2 demo project

This small project exists specifically to demonstrate PySonar2's generated code browser. It combines
cross-module imports, classes, constructors, collection inference, branching, recursion, and async code
without requiring third-party packages.

Generate the static site from the repository root:

```sh
mvn package
java -jar target/pysonar-3.3.2.jar demo_project ./demo-html
```

Open `demo-html/index.html` in a browser or publish the directory on any static host.
The current generated demo is available at <https://smallyunet.github.io/pysonar2/>.

## VS Code language-server demo

The same project is used by the repository's VS Code extension demo. From `editors/vscode`, install and
build the extension, then press `F5` using the **Run PySonar2 Extension Demo** launch configuration:

```sh
cd editors/vscode
npm install
npm run build
code .
```

In the Extension Development Host, hover over inferred values in `main.py`, follow `DemoApp` and
`build_report` across modules, find references to `Market` or `weighted_signal`, and save a file to watch
the workspace index refresh.
