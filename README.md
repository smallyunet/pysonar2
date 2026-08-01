# PySonar2 - a type inferencer and indexer library for Python

PySonar2 is a type inferencer and indexer library for Python, designed for analysing large code bases.
The resulting index can be used to build code browsers and code search engines.

PySonar2 has a sophisticated type system and whole-project interprocedural analysis to infer types
and locate definitions. It handles first-class functions (closures) and control flow correctly.
As a result, the accuracy of the index often outperforms Python IDEs such as PyCharm.

PySonar2 uses advanced analysis techniques similar to control-flow analysis (k-CFA, CFA2, etc), but
with a much simpler theory and without their drawbacks. It is both highly accurate and highly performant,
thus it became the choice of several large scale code index infrastructures,

Major users include:

- Google
- Sourcegraph
- Insight.io (now part of Elastic)

<a href="http://www.yinwang.org/resources/demos/pysonar2/email/header.py.html">
<img src="http://www.yinwang.org/images/pysonar2.gif" width="70%">
</a>


### Supported runtimes

PySonar2 3.x supports Python 3.10 and newer. Python 2 is not supported.

The analyzer runs CPython's built-in `ast` parser in a persistent `python3` process. If your
supported interpreter has a different executable name, set `PYSONAR_PYTHON` to its path.

PySonar2 itself targets Java 11 and can be built with Java 11 or newer.


### How to build

    mvn package -DskipTests


### Demo

The repository includes a dedicated multi-file project that demonstrates cross-module navigation,
class and collection inference, branching, recursion, and async syntax. Build its static code browser
with:

    mvn package
    java -jar target/pysonar-3.0.0.jar demo_project ./demo-html

Open `demo-html/index.html` in a browser. Hover over or focus a symbol to inspect its inferred type,
and follow links between definitions and references. The generated site is responsive, self-contained,
and can be hosted on any static file server.

You can use the same command with another Python file or directory to generate a browser for your own
code. Large trees, such as a Python standard library, may take a few minutes to analyze.

Note that this is just a simple demo program based on the library. PySonar2 is not meant to be an
end-user tool. It is mainly designed as a library for Python IDEs, developer tools and code search
engines, so its interface may not be as appealing as an end-user tool.

If you have problems with it, please feel free to contact me.


### System requirements

* Python 3.10+
* Java 11+
* maven


### Environment variables

PySonar2 uses CPython's built-in `ast` package to parse Python code, so make sure `python3` points
to Python 3.10 or newer. Alternatively, select an interpreter explicitly:

    export PYSONAR_PYTHON=/path/to/python3

`PYTHONPATH` environment variable is used for locating the Python standard libraries. It is
important to point it to the correct Python library, for example

    export PYTHONPATH=/usr/lib/python3

If this is not set up correctly, references to library code will not be found.


### Contribute

You are welcome to make code contributions.

Because of the highly complex and unpublished theory behind PySonar2, things may go wrong easily
with even an innocent-looking change. If you hope to contribute to PySonar2, please discuss with me
first before making significant changes, otherwise I may not be able to review your changes.

For basic verification, you can run the unit tests. PySonar2 has a basic test framework. You can run
the tests using this command:

    mvn test

If you modify the code or tests, you need to generate new expected results. Run these command lines:

    mvn package -DskipTests
    java -classpath target/pysonar-<version>.jar org.yinwang.pysonar.TestInference -generate tests

To write new tests, you just need to write relevant Python code demonstrating your change, put them
into a directory named `tests/testname.test`(test directory name must end with ".test"). Please look
at the `tests` directory for examples.

Please don't expect the tests to catch all bugs. Be very careful :)


### License

Apache 2.0 License. See LICENSE file.
