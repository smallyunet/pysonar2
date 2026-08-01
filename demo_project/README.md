# PySonar2 demo project

This small project exists specifically to demonstrate PySonar2's generated code browser. It combines
cross-module imports, classes, constructors, collection inference, branching, recursion, and async code
without requiring third-party packages.

Generate the static site from the repository root:

```sh
mvn package
java -jar target/pysonar-3.0.0.jar demo_project ./demo-html
```

Open `demo-html/index.html` in a browser or publish the directory on any static host.
