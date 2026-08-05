# Coding-tool integration benchmark

This benchmark measures PySonar2 as an optional coding-tool integration, not as the product definition.
The natural mode compares Codex with and without the repository's `pysonar-code-intelligence` Skill; the
forced-tool mode compares always-off and always-on analyzer policies without Skill routing. It uses seven
small, isolated Python tasks that cover:

- cross-file reference impact;
- import-alias definition resolution;
- unannotated factory/type flow;
- first-class callback flow;
- a localized change where the analyzer should be skipped; and
- dynamic dispatch that exposes an analyzer boundary; and
- ambiguous same-name bindings connected through import aliases.

Each task has a visible `unittest` test and a separate validator that scores the completed workspace.
The control and Skill conditions use separate `CODEX_HOME` directories; only the Skill home contains a
link to `skills/pysonar-code-intelligence`. Both conditions use the same model, reasoning effort, task
text, starting files, and validator. In the default `natural` mode, prompts are byte-identical across
conditions: treatment differs only by making the Skill and CLI available, so Skill activation is
natural rather than forced. Trial order is randomized with a recorded seed.

To isolate the analyzer itself rather than Skill routing, use `--mode forced-tool`. Its control prompt
forbids PySonar2, while its treatment prompt requires an actual `plan`, `context`, or `impact` call
before broad source inspection. The forced-tool treatment does not install the Skill. A treatment
trial with no recorded analyzer command, or a control trial with one, is marked invalid through
`pysonarUsageValid` and causes the runner to fail.

Run the matrix with:

```sh
python3 benchmarks/agent-skill/run_benchmark.py \
  --mode natural \
  --repetitions 3 \
  --seed 20260803 \
  --results-dir /private/tmp/pysonar-agent-results
```

Codex Desktop on macOS already runs inside an outer sandbox and cannot start a second `sandbox-exec`
profile. Only in that already-confined environment, use:

```sh
python3 benchmarks/agent-skill/run_benchmark.py \
  --mode natural \
  --allow-unsandboxed-child \
  --repetitions 3 \
  --seed 20260803 \
  --results-dir /private/tmp/pysonar-agent-results
```

The second form disables the child CLI sandbox and is unsafe on an otherwise unrestricted machine.
The runner writes raw Codex JSONL, stderr, and a machine-readable `results.json` to the selected results
directory. Measurements include correctness, usage, duration, completed items, agent messages, command
count, command-output characters, approximate source-read output, and analyzer commands. Raw model
transcripts are intentionally not committed.

Run the strict always-off versus always-on comparison with:

```sh
python3 benchmarks/agent-skill/run_benchmark.py \
  --mode forced-tool \
  --allow-unsandboxed-child \
  --repetitions 3 \
  --seed 20260805 \
  --results-dir /private/tmp/pysonar-forced-tool-results
```

The checked-in 2026-08-03 forced-use pilot and natural-trigger follow-up are summarized in
[`docs/agent-skill-benchmark.md`](../../docs/agent-skill-benchmark.md), with structured measurements in
[`results/2026-08-03.json`](results/2026-08-03.json) and
[`results/2026-08-03-natural.json`](results/2026-08-03-natural.json).
