# Historical change-safety benchmark

This benchmark replays 12 identifier changes from pinned commits in Click, Flask, and Werkzeug. Each
tool starts from the parent commit and returns the source occurrences it would edit. The gold set is
derived independently from executable Python identifier occurrences changed by the upstream commit.

The primary metric is `safeComplete`: the candidate plan contains every gold occurrence and no other
occurrence. Precision, recall, F1, missed locations, wrong locations, elapsed time, tool versions, and
the complete normalized candidate sets are retained in the result JSON.

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

Repositories are cached under `/private/tmp/pysonar-change-safety-cache` and every case is materialized
into a fresh temporary directory. Commits, queries, gold locations, candidates, and tool versions are
recorded so a result can be audited without trusting the summary.

## Interpretation boundary

The benchmark measures whether a tool can reproduce the identifier-edit surface of a historical
change. It does not claim that the original commit was a pure rename, that static references form a
runtime call graph, or that applying only the identifier edits reproduces every behavioral change in
the commit. A larger follow-up should add upstream tests and manually adjudicated API migrations after
this reference-discovery pilot identifies valid task families.
