# Analyzer benchmark and profiles

The Rust benchmark measures complete workspace analysis through the stable CLI. Build the release binary and install [hyperfine](https://github.com/sharkdp/hyperfine), then run:

```sh
cargo build --release -p pysonar-cli
benchmarks/analyzer/run-benchmark.sh /path/to/python/project 10 SymbolName
```

The runner writes raw warmup and measured samples as JSON under `target/benchmarks`. Compare results only for the same corpus, target triple, release profile, parser revision, and query symbol.

For CPU and allocation profiles, profile `target/release/pysonar plan --root CORPUS --symbol SYMBOL` with `samply`, Instruments, or Linux `perf`. Keep the hyperfine JSON and Git revision beside any published profile. WebAssembly bundle size is recorded from `extensions/browser/pkg/pysonar_wasm_bg.wasm` after `npm run build`.

PySonar2 4 currently refreshes changed persistent sessions with a complete atomic rebuild. Session responses report `rebuildMode`, changed and analyzed file counts, and the reason; no incremental-cache performance claim is made.
