# Analyzer benchmark and profiles

This benchmark records raw samples for three distinct paths:

- `coldFull`: a full analysis with a new, empty AST-cache namespace;
- `persistentCacheFull`: a new analyzer process reusing serialized ASTs; and
- `noChange`: a second LSP rebuild whose workspace content hashes are unchanged; and
- optional `incrementalChange`: controlled comment-only edits in a benchmark-owned corpus copy.

Each JSON result includes the corpus SHA-256, Git commit and dirty-state flag, JDK/Python/OS metadata,
wall time, JVM-process CPU time, current-thread Java allocation bytes, GC counters, rebuild scope, and
AST-cache hits/misses. JVM CPU and allocation counters do not include the CPython parser child process.

Build, then run five measured iterations:

```sh
mvn package
benchmarks/analyzer/run-benchmark.sh /path/to/python/project 5
benchmarks/analyzer/run-benchmark.sh /path/to/python/project 5 package/core.py
```

The command writes a timestamped JSON document under `target/benchmarks`. Compare results only when the
corpus digest, runtime configuration, and benchmark method match. Keep raw samples with any published
summary instead of reporting only the median.

## CPU and allocation profile

JDK Flight Recorder's `profile` settings capture sampled execution stacks, allocation samples, GC, file
I/O, and process events:

```sh
benchmarks/analyzer/profile-jfr.sh /path/to/python/project
jfr summary target/profiles/analyzer-*.jfr
jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample target/profiles/analyzer-*.jfr
```

Open the `.jfr` file in JDK Mission Control for call trees, flame graphs, and allocation hot spots. Keep
the adjacent JSON result: it binds the profile run to the exact corpus and implementation metadata.

The optional third argument selects a workspace-relative Python file for a change-sensitive benchmark.
The runner copies the corpus to its unique benchmark cache namespace, appends a different comment before
each measured rebuild, and records `RebuildMetrics`; it never modifies the supplied corpus. Repository
tests separately cover deterministic one-file, reverse-dependency-closure, deletion, and no-change
decisions.

For workspaces with at least 20 Python files, PySonar2 selects a persistent-cache full rebuild when the
reverse-import closure reaches 60% of files. The benchmark still records the mode and reason, so a broad
dependency change cannot be mislabeled as a narrow incremental win.
