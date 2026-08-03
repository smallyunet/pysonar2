# Agent Skill benchmark

This benchmark compares Codex with and without the repository's
`pysonar-code-intelligence` Skill. It uses seven small, isolated Python tasks that cover:

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
text, starting files, and validator. Prompts are byte-identical across conditions: treatment differs
only by making the Skill and CLI available, so Skill activation is natural rather than forced. Trial
order is randomized with a recorded seed.

Run the matrix with:

```sh
python3 benchmarks/agent-skill/run_benchmark.py \
  --repetitions 3 \
  --seed 20260803 \
  --results-dir /private/tmp/pysonar-agent-results
```

Codex Desktop on macOS already runs inside an outer sandbox and cannot start a second `sandbox-exec`
profile. Only in that already-confined environment, use:

```sh
python3 benchmarks/agent-skill/run_benchmark.py \
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

The checked-in 2026-08-03 forced-use pilot and natural-trigger follow-up are summarized in
[`docs/agent-skill-benchmark.md`](../../docs/agent-skill-benchmark.md), with structured measurements in
[`results/2026-08-03.json`](results/2026-08-03.json) and
[`results/2026-08-03-natural.json`](results/2026-08-03-natural.json).
