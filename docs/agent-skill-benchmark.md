# PySonar2 Agent Skill benchmark

## 2026-08-03 diverse-task pilot

This pilot tests whether the `pysonar-code-intelligence` Skill improves equal-quality Codex task
completion, and whether it avoids the analyzer when direct inspection is sufficient. It replaces the
earlier unexecuted two-task proposal with 12 completed trials: six task categories, two isolated
conditions, and one run per cell.

### Method

- Project baseline: `5d3eca535b411de95394dbcc37e28f0f56859d34`
- Codex CLI: `0.146.0-alpha.9.2`
- Model: `gpt-5.6-sol`, medium reasoning
- Conditions: control without the Skill; treatment with only this repository's Skill linked into an
  isolated `CODEX_HOME`
- Correctness: a visible standard-library `unittest` plus a separate post-run validator
- Tokens: `input_tokens + output_tokens` from `turn.completed`; cached input is included in input and
  is shown separately. `reasoning_output_tokens` is diagnostic metadata and is not added again.
- Time: wall-clock duration of each Codex subprocess, excluding the post-run validator

The task fixtures, prompts, validators, and runner live in
[`benchmarks/agent-skill`](../benchmarks/agent-skill/README.md). Raw JSONL transcripts were retained for
the analysis run but are not committed because they are verbose and can contain environment-specific
paths. The extracted measurements are committed as
[`2026-08-03.json`](../benchmarks/agent-skill/results/2026-08-03.json).

### Results

All 12 trials passed their hidden validators, so the comparison is quality-matched for this fixture set.

| Task category | Control tokens | Skill tokens | Delta | Control time | Skill time | Analyzer commands |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Cross-file rename impact | 105,131 | 123,903 | +17.9% | 54.1s | 55.2s | `doctor`, `impact`, `check` |
| Import-alias definition | 91,031 | 111,163 | +22.1% | 42.6s | 42.3s | skipped |
| Unannotated factory/type flow | 89,414 | 91,799 | +2.7% | 40.6s | 38.1s | skipped |
| First-class callback flow | 91,076 | 110,205 | +21.0% | 41.2s | 43.0s | skipped |
| Localized boundary fix | 85,784 | 89,601 | +4.4% | 36.7s | 36.6s | skipped |
| Dynamic plugin dispatch | 72,156 | 90,538 | +25.5% | 37.6s | 40.6s | skipped |
| **Total** | **534,592** | **617,209** | **+15.5%** | **252.9s** | **255.8s** | **3 calls in 1 task** |

Aggregate uncached input was 72,446 tokens for control and 94,092 for the Skill condition. Output was
6,466 and 7,021 tokens respectively. The Skill condition therefore added 82,617 total tokens and 2.9
seconds while preserving the same 6/6 correctness rate.

### Interpretation

The result does **not** demonstrate token savings. These repositories are deliberately small, and
ordinary `rg` plus a few source reads resolved five of the six tasks. The Skill correctly followed its
task-sensitive routing and skipped the analyzer in those five cases, but reading and applying the Skill
still added context and one extra inspection step. On the one true reference-impact task, the analyzer
returned the correct affected files and the treatment passed, but the avoided exploration was too small
to offset `doctor`, `impact`, and `check`.

The useful result is narrower:

1. the optimized Skill does not mechanically run the full analyzer chain on every Python task;
2. its reference-impact path works end to end and preserves correctness; and
3. small, obvious repositories are a negative case for token efficiency, even when routing is correct.

### Limitations and next benchmark

- One repetition per cell is a smoke benchmark, not a statistically stable performance estimate.
- The fixed control-then-treatment order can interact with provider-side caching.
- Synthetic fixtures make correctness unambiguous but underrepresent the exploration cost of a large,
  unfamiliar repository.
- The treatment prompt explicitly requests faithful Skill use, while the control explicitly forbids it;
  this verifies the mechanism but adds a small prompt difference.
- Codex Desktop's outer macOS sandbox prevented a nested `sandbox-exec`. Trials therefore used the
  runner's explicit `--allow-unsandboxed-child` mode inside the already-confined desktop session.
- Reference-based impact is not a runtime call graph, and the dynamic plugin task intentionally falls
  outside the analyzer's reliable static coverage.

A stronger follow-up should keep these routing cases but add large real-project tasks with known patches,
randomize condition order, and run at least three repetitions. The main success criterion should remain
hidden-test correctness first, followed by total tokens only among equally correct runs.
