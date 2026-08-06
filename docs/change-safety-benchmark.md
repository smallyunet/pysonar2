# Historical change-safety benchmark

## Result first

The first historical-change pilot does **not** establish a broad change-safety advantage for
PySonar2. On 12 identifier changes replayed from pinned Click, Flask, and Werkzeug commits, exact-name
`rg` reproduced more complete upstream edit surfaces than PySonar2, Jedi, or Rope.

PySonar2 did show a narrower useful property: every candidate it returned belonged to the historical
edit set, and it exactly reproduced four changes. On the ambiguous local name `apply`, PySonar2
returned the two intended occurrences while `rg` returned six unrelated occurrences as well. This is
evidence that semantic references can reduce same-name review noise, but current recall is too low to
claim generally safe automated renames.

## Method

The benchmark materializes the parent of each upstream change commit into a fresh directory. For each
case, it asks PySonar2, Jedi, Rope 1.14.0, and exact-name `rg` for the occurrences they would edit. The
gold set consists of executable Python identifier tokens that the pinned upstream commit changed from
the old name to the new name.

The primary metric is `safeComplete`: no missed gold locations and no non-gold candidate locations.
Micro precision, recall, F1, elapsed time, errors, normalized locations, repository commits, and tool
versions are retained in the result JSON.

| Tool | Safe complete | Errors | Precision | Recall | F1 | Time |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| PySonar2 3.3.2 | 4/12 | 0 | 1.000 | 0.479 | 0.648 | 55.7s |
| Jedi 0.19.0 | 4/12 | 0 | 0.989 | 0.509 | 0.672 | 2.4s |
| Rope 1.14.0 | 2/12 | 8 | 1.000 | 0.272 | 0.428 | 1.6s |
| `rg` 15.1.0 | 8/12 | 0 | 0.923 | 1.000 | 0.960 | 0.1s |

The raw result is
[`benchmarks/change-safety/results/2026-08-06.json`](../benchmarks/change-safety/results/2026-08-06.json).

## Case-level findings

PySonar2 exactly reproduced these four edit surfaces:

- Flask `get_package_path` to `get_root_path`;
- Werkzeug `_unicodify_value` to `_unicodify_header_value`;
- Werkzeug `url_split` to `url_parse`; and
- Werkzeug's local `apply` to `setmethod` rename.

The strongest relative case was `apply`: PySonar2 and Jedi returned exactly the two binding-related
occurrences, while exact-name search returned eight occurrences and six were unrelated same-name
identifiers.

The main failures were:

- method calls through an inferred Click command object were not connected to `resultcallback`;
- Flask pytest fixture injection was not modeled, producing 42 misses for `apps_tmpdir`;
- inherited or overridden attribute assignments were incomplete;
- some old Python syntax caused a module to be absent from semantic results without making the CLI
  command fail; and
- PySonar2 took substantially longer because every case performed a fresh whole-project analysis.

Rope failed eight cases because the historical snapshots contained Python 2 syntax that Rope 1.14.0
could not parse. This is retained as an out-of-the-box compatibility result, not interpreted as proof
that PySonar2 resolved those files: the PySonar2 zero-result legacy case shows that successful process
exit is not the same as complete analysis.

## Valid claims and non-claims

This pilot supports only the following claims:

1. PySonar2 can return high-precision reference evidence for some real historical changes.
2. Semantic binding can remove substantial same-name noise in at least one adjudicated case.
3. Current reference recall and analysis-completeness reporting are not sufficient for a general safe
   rename claim.

It does not prove token savings, broad superiority over text search, runtime-call-graph completeness,
or production-ready automated refactoring.

## Next gates

The committed result is a regression baseline. Product work should target the measured misses rather
than add favorable synthetic cases. A stronger follow-up should:

1. ~~report parse and module coverage in every `context` and `impact` response;~~ completed in 3.3.3
   with explicit coverage, confidence, and applicability fields;
2. improve attribute references through inferred instances and inherited attributes;
3. distinguish framework-injected names, such as pytest fixtures, as unsupported rather than silently
   complete;
4. add a modern-Python-only historical slice so compatibility and semantic accuracy are reported
   separately; and
5. rerun the exact pinned corpus after each change, requiring no regression in precision and a material
   increase over the current 0.479 recall before making a safe-change claim.

The benchmark implementation and reproduction command are documented in
[`benchmarks/change-safety/README.md`](../benchmarks/change-safety/README.md).
