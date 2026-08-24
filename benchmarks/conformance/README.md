# Python conformance evidence

This benchmark pins and separates authoritative or peer-reviewed Python corpora by the claim each one
can support. It never converts parser acceptance into semantic correctness and never counts an
unsupported capability as a pass.

The manifest in [`suites.json`](suites.json) currently covers:

- CPython's official regression corpus for parse coverage and robustness;
- the Python Typing Council conformance sources for parse coverage, with diagnostic conformance
  explicitly unscored because PySonar2 is not a strict type checker;
- typeshed's standard-library and third-party stubs, copied unchanged into an isolated mirror for
  native `.pyi` discovery and parser acceptance;
- TypeEvalPy's hand-authored micro-benchmark for type-inference gold scoring; and
- TypeEvalPy Autogen for generated-corpus parser scale only.

The runner also scores every preserved repository `tests/**/refs.json` entry against Rust v4. This is
the local definition/reference gold set that the ordinary workspace test previously only parsed.

Build a release binary and run the pinned suite:

```sh
cargo build --release -p pysonar-cli --locked
python3 benchmarks/conformance/run_conformance.py \
  --binary target/release/pysonar \
  --output benchmarks/conformance/results/YYYY-MM-DD.json
```

Repositories are cloned at exact commits under `/private/tmp/pysonar-conformance-sources` by default.
Use `--cache` to select another disposable cache and repeat `--suite` for a focused run. The full JSON
retains per-query TypeEvalPy and preserved-reference records so aggregate claims can be audited.

The summary JSON keeps aggregate evidence small enough to review. Per-query TypeEvalPy and preserved
reference records are written beside it under `results/YYYY-MM-DD/*.jsonl` and linked through each
summary's `recordsFile` field.

The historical change-safety benchmark remains separate because its gold set measures real edit
surfaces rather than language conformance. Run its current Rust adapter with:

```sh
python3 benchmarks/change-safety/run_benchmark.py \
  --adapter pysonar \
  --pysonar target/release/pysonar \
  --output benchmarks/change-safety/results/YYYY-MM-DD-rust-v4.json
```
