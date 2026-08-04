# PySonar2 Agent Skill benchmark

## 2026-08-03 natural-trigger follow-up

After the first pilot exposed fixed Skill-loading overhead, the Skill and benchmark were changed so
that control and treatment prompts are byte-identical. The treatment environment merely makes the
Skill and CLI available; it does not instruct the model to use them. The Skill description now requires
unresolved uncertainty after ordinary source search, and its default workflow no longer runs `doctor`
or `check`.

The follow-up used seven task categories, two conditions, three repetitions, hidden validators, and a
seeded randomized schedule: 42 completed trials. The corrected dynamic-dispatch prompt explicitly
distinguishes runtime Python callbacks from Codex plugins, avoiding an observed `plugin-creator` Skill
confound. A new same-name/import-alias task exercises natural semantic routing.

| Task category | Control tokens (3 runs) | Skill tokens (3 runs) | Delta |
| --- | ---: | ---: | ---: |
| Import-alias definition | 280,863 | 232,618 | -17.2% |
| First-class callback flow | 245,032 | 252,434 | +3.0% |
| Dynamic plugin dispatch | 276,621 | 247,646 | -10.5% |
| Unannotated factory/type flow | 299,211 | 304,019 | +1.6% |
| Localized boundary fix | 242,139 | 232,555 | -4.0% |
| Cross-file rename impact | 264,422 | 327,025 | +23.7% |
| Same-name/import-alias ambiguity | 295,168 | 298,996 | +1.3% |
| **Total** | **1,903,456** | **1,895,293** | **-0.43%** |

Both conditions passed 21/21 validators. Control took 931.4 seconds and treatment took 934.8 seconds
(+0.36%). Treatment used 8,201 fewer uncached input tokens and 1,318 more output tokens. Only one of 21
treatment trials loaded `pysonar-code-intelligence`; after direct search made the binding clear, it
correctly skipped the analyzer. No treatment trial invoked `plan`, `context`, `impact`, or `check`.

This meets the narrow routing goal: on small tasks where direct inspection is sufficient, installing
the Skill is approximately token-neutral rather than adding the previous fixed 15.5% overhead. It does
**not** show analyzer-driven savings. The new compact `plan` command and persistent JSONL `session`
protocol are covered by CLI tests, but require a large, genuinely ambiguous repository benchmark before
making a token-reduction claim.

Structured measurements, including all 42 trial rows, command counts, output characters, Skill reads,
and analyzer calls, are in
[`2026-08-03-natural.json`](../benchmarks/agent-skill/results/2026-08-03-natural.json).

Remaining limitations:

- Three repetitions expose substantial model variance but are still a small sample.
- Synthetic repositories underrepresent large-project exploration cost.
- Per-task deltas vary widely even without Skill activation; the aggregate should be treated as a
  routing regression result, not causal proof of a 0.43% saving.
- PySonar2's semantic references remain incomplete for some import aliases and module attributes.
  `plan` therefore labels its conservative exact-identifier fallback separately from semantic matches.
- The benchmark predates the content-hash and reverse-import incremental rebuild added later. Current
  `session refresh` responses report whether the rebuild was full, incremental, or a no-change reuse;
  this historical result did not measure that path.

## 2026-08-03 forced-use diverse-task pilot

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
