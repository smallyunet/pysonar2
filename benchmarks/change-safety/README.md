# Historical change-safety benchmark

This benchmark replays 12 identifier changes from pinned commits in Click, Flask, and Werkzeug. Each
tool starts from the parent commit and returns the source occurrences it would edit. The gold set is
derived independently from executable Python identifier occurrences changed by the upstream commit.

The primary metric is `safeComplete`: the candidate plan contains every gold occurrence and no other
occurrence, and the analyzer declares the impact query applicable. Precision, recall, F1, missed
locations, wrong locations, applicability, coverage, elapsed time, tool versions, and the complete
normalized candidate sets are retained in the result JSON.

Cases are explicitly tagged `modern` or `legacy`. The modern slice contains the Python-3-only Click 8
and Werkzeug 2 transitions from 2020-2021; the legacy slice keeps older compatibility-heavy snapshots.
The result JSON reports both the full aggregate and `aggregateBySlice`, so parser compatibility is not
silently mixed with semantic reference accuracy.

The comparison includes:

- PySonar2 definition/reference impact;
- Jedi project references;
- Rope rename edits; and
- exact-name `rg`, representing a repository-wide textual baseline.

## Run

Install Rope into an isolated location instead of changing the project environment:

```sh
python3 -m pip install --target /private/tmp/pysonar-change-safety-tools rope==1.14.0
python3 benchmarks/change-safety/run_benchmark.py \
  --rope-path /private/tmp/pysonar-change-safety-tools \
  --output benchmarks/change-safety/results/YYYY-MM-DD.json
```

Run only the modern slice with `--slice modern`; repeat `--slice` to select multiple slices. PySonar2
records `coverageStatus`, `applicable`, query-specific `unsupportedSemantics`, and the full coverage
object for each case. In particular, direct pytest fixture declarations are reported as dynamic
parameter injection rather than silently treated as complete static references.

Repositories are cached under `/private/tmp/pysonar-change-safety-cache` and every case is materialized
into a fresh temporary directory. Commits, queries, gold locations, candidates, and tool versions are
recorded so a result can be audited without trusting the summary.

## Interpretation boundary

The benchmark measures whether a tool can reproduce the identifier-edit surface of a historical
change. It does not claim that the original commit was a pure rename, that static references form a
runtime call graph, or that applying only the identifier edits reproduces every behavioral change in
the commit. A larger follow-up should add upstream tests and manually adjudicated API migrations after
this reference-discovery pilot identifies valid task families.
