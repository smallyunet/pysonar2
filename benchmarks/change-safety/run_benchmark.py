#!/usr/bin/env python3
"""Replay historical Python renames and compare reference discovery tools."""

from __future__ import annotations

import argparse
import difflib
import io
import json
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import tokenize
from collections import defaultdict
from pathlib import Path
from typing import Any, Callable


BENCHMARK_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BENCHMARK_DIR.parents[1]
DEFAULT_CACHE = Path("/private/tmp/pysonar-change-safety-cache")
Location = tuple[str, int, int]


def run(command: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=True, **kwargs)


def git(repo: Path, *args: str) -> str:
    return run(["git", "-C", str(repo), *args]).stdout


def load_cases() -> list[dict[str, str]]:
    return json.loads((BENCHMARK_DIR / "cases.json").read_text())


def ensure_repository(case: dict[str, str], cache: Path) -> Path:
    repo = cache / "repositories" / case["repository"]
    if not repo.exists():
        repo.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--filter=blob:none", "--no-checkout", case["url"], str(repo)])
    try:
        git(repo, "cat-file", "-e", f"{case['changeCommit']}^{{commit}}")
    except subprocess.CalledProcessError:
        git(repo, "fetch", "origin", case["changeCommit"])
    return repo


def materialize(repo: Path, revision: str, destination: Path) -> None:
    archive = subprocess.run(
        ["git", "-C", str(repo), "archive", "--format=tar", revision],
        capture_output=True,
        check=True,
    ).stdout
    destination.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as bundle:
        bundle.extractall(destination)


def identifier_locations(source: str, name: str) -> list[tuple[int, int]]:
    try:
        tokens = tokenize.generate_tokens(io.StringIO(source).readline)
        return [(token.start[0], token.start[1] + 1) for token in tokens
                if token.type == tokenize.NAME and token.string == name]
    except (IndentationError, SyntaxError, tokenize.TokenError):
        pattern = re.compile(rf"\b{re.escape(name)}\b")
        return [(line_number, match.start() + 1)
                for line_number, line in enumerate(source.splitlines(), 1)
                for match in pattern.finditer(line)]


def changed_old_lines(repo: Path, parent: str, change: str, old: str, new: str) -> dict[str, set[int]]:
    diff = git(repo, "diff", "--unified=0", parent, change, "--", "*.py")
    result: dict[str, set[int]] = defaultdict(set)
    current_file: str | None = None
    old_line = 0
    hunk_old: list[tuple[int, str]] = []
    hunk_new: list[str] = []

    def flush() -> None:
        if current_file is None or not any(re.search(rf"\b{re.escape(new)}\b", line) for line in hunk_new):
            return
        for line_number, line in hunk_old:
            if re.search(rf"\b{re.escape(old)}\b", line):
                result[current_file].add(line_number)

    for line in diff.splitlines():
        if line.startswith("diff --git "):
            flush()
            hunk_old, hunk_new = [], []
            current_file = None
        elif line.startswith("+++ b/"):
            current_file = line[6:]
        elif line.startswith("@@"):
            flush()
            hunk_old, hunk_new = [], []
            match = re.match(r"@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@", line)
            if not match:
                raise RuntimeError(f"Cannot parse diff hunk: {line}")
            old_line = int(match.group(1))
        elif line.startswith("-") and not line.startswith("---"):
            hunk_old.append((old_line, line[1:]))
            old_line += 1
        elif line.startswith("+") and not line.startswith("+++"):
            hunk_new.append(line[1:])
        elif not line.startswith("\\"):
            old_line += 1
    flush()
    return result


def gold_locations(repo: Path, root: Path, case: dict[str, str], parent: str) -> set[Location]:
    changed = changed_old_lines(
        repo, parent, case["changeCommit"], case["oldName"], case["newName"]
    )
    gold: set[Location] = set()
    for relative, changed_lines in changed.items():
        source = (root / relative).read_text(errors="replace")
        for line, column in identifier_locations(source, case["oldName"]):
            if line in changed_lines:
                gold.add((relative, line, column))
    if not gold:
        raise RuntimeError(f"{case['id']}: historical diff produced an empty gold set")
    return gold


def query_location(root: Path, case: dict[str, str]) -> tuple[int, int]:
    source = (root / case["queryFile"]).read_text(errors="replace")
    matches = [(number, line.index(case["oldName"]) + 1)
               for number, line in enumerate(source.splitlines(), 1)
               if case["queryText"] in line]
    if len(matches) != 1:
        raise RuntimeError(f"{case['id']}: expected one query match, found {len(matches)}")
    return matches[0]


def normalize_path(root: Path, candidate: Path | str) -> str | None:
    path = Path(candidate).resolve()
    try:
        return path.relative_to(root.resolve()).as_posix()
    except ValueError:
        return None


def pysonar_adapter(root: Path, case: dict[str, str], line: int, column: int,
                    pysonar: Path, _: Path | None) -> set[Location]:
    result = run([
        str(pysonar), "impact", "--root", str(root), "--file", case["queryFile"],
        "--line", str(line), "--character", str(column), "--max-results", "10000",
        "--format", "json",
    ], timeout=300)
    payload = json.loads(result.stdout)
    locations = payload.get("definitions", []) + payload.get("references", [])
    return {(item["file"], item["startLine"], item["startCharacter"]) for item in locations}


def jedi_adapter(root: Path, case: dict[str, str], line: int, column: int,
                 _: Path, __: Path | None) -> set[Location]:
    import jedi

    jedi.settings.cache_directory = str(root.parent / "jedi-cache")
    source_path = root / case["queryFile"]
    project = jedi.Project(path=str(root))
    script = jedi.Script(path=str(source_path), project=project)
    names = script.get_references(line=line, column=column - 1, scope="project")
    result: set[Location] = set()
    for name in names:
        if name.module_path is None:
            continue
        relative = normalize_path(root, name.module_path)
        if relative is not None:
            result.add((relative, name.line, name.column + 1))
    return result


def changed_locations(before: str, after: str, name: str, relative: str) -> set[Location]:
    before_lines = before.splitlines()
    after_lines = after.splitlines()
    result: set[Location] = set()
    matcher = difflib.SequenceMatcher(a=before_lines, b=after_lines, autojunk=False)
    for tag, old_start, old_end, _, _ in matcher.get_opcodes():
        if tag == "equal":
            continue
        for index in range(old_start, old_end):
            for line, column in identifier_locations(before_lines[index] + "\n", name):
                result.add((relative, index + line, column))
    return result


def rope_adapter(root: Path, case: dict[str, str], line: int, column: int,
                 _: Path, tool_path: Path | None) -> set[Location]:
    if tool_path is None:
        raise RuntimeError("Rope tool path was not configured")
    sys.path.insert(0, str(tool_path))
    try:
        from rope.base.project import Project
        from rope.refactor.rename import Rename

        project = Project(str(root), ropefolder=None)
        try:
            resource = project.get_file(case["queryFile"])
            source = resource.read()
            lines = source.splitlines(keepends=True)
            offset = sum(len(value) for value in lines[:line - 1]) + column - 1
            changes = Rename(project, resource, offset).get_changes(case["newName"])
            result: set[Location] = set()
            for change in changes.changes:
                changed_resource = getattr(change, "resource", None)
                new_contents = getattr(change, "new_contents", None)
                if changed_resource is None or new_contents is None:
                    continue
                relative = changed_resource.path
                before = (root / relative).read_text(errors="replace")
                result.update(changed_locations(before, new_contents, case["oldName"], relative))
            return result
        finally:
            project.close()
    finally:
        sys.path.remove(str(tool_path))


def rg_adapter(root: Path, case: dict[str, str], _: int, __: int,
               ___: Path, ____: Path | None) -> set[Location]:
    pattern = rf"\b{re.escape(case['oldName'])}\b"
    process = subprocess.run(
        ["rg", "--json", "--glob", "*.py", pattern, str(root)],
        text=True, capture_output=True,
    )
    if process.returncode not in (0, 1):
        raise RuntimeError(process.stderr.strip())
    result: set[Location] = set()
    for raw in process.stdout.splitlines():
        event = json.loads(raw)
        if event.get("type") != "match":
            continue
        data = event["data"]
        relative = normalize_path(root, data["path"]["text"])
        if relative is None:
            continue
        for match in data["submatches"]:
            result.add((relative, data["line_number"], match["start"] + 1))
    return result


ADAPTERS: dict[str, Callable[..., set[Location]]] = {
    "pysonar": pysonar_adapter,
    "jedi": jedi_adapter,
    "rope": rope_adapter,
    "rg": rg_adapter,
}


def score(candidates: set[Location], gold: set[Location]) -> dict[str, Any]:
    true_positive = len(candidates & gold)
    false_positive = len(candidates - gold)
    false_negative = len(gold - candidates)
    precision = true_positive / len(candidates) if candidates else (1.0 if not gold else 0.0)
    recall = true_positive / len(gold) if gold else 1.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "goldCount": len(gold),
        "candidateCount": len(candidates),
        "truePositive": true_positive,
        "falsePositive": false_positive,
        "falseNegative": false_negative,
        "precision": round(precision, 6),
        "recall": round(recall, 6),
        "f1": round(f1, 6),
        "safeComplete": false_positive == 0 and false_negative == 0,
        "missed": [list(value) for value in sorted(gold - candidates)],
        "wrong": [list(value) for value in sorted(candidates - gold)],
    }


def version(command: list[str]) -> str:
    try:
        process = subprocess.run(command, text=True, capture_output=True, timeout=30)
        return (process.stdout or process.stderr).strip().splitlines()[0]
    except Exception as error:
        return f"unavailable: {error}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adapter", action="append", choices=sorted(ADAPTERS))
    parser.add_argument("--case", action="append", dest="case_ids")
    parser.add_argument("--pysonar", type=Path, default=PROJECT_ROOT / "bin" / "pysonar")
    parser.add_argument("--rope-path", type=Path)
    args = parser.parse_args()

    selected_cases = [case for case in load_cases()
                      if not args.case_ids or case["id"] in args.case_ids]
    adapters = args.adapter or list(ADAPTERS)
    records: list[dict[str, Any]] = []
    args.cache.mkdir(parents=True, exist_ok=True)

    for case in selected_cases:
        repo = ensure_repository(case, args.cache)
        parent = git(repo, "rev-parse", f"{case['changeCommit']}^").strip()
        with tempfile.TemporaryDirectory(prefix=f"pysonar-change-{case['id']}-", dir=args.cache) as temp:
            root = Path(temp) / "source"
            materialize(repo, parent, root)
            gold = gold_locations(repo, root, case, parent)
            line, column = query_location(root, case)
            for adapter_name in adapters:
                started = time.monotonic()
                error = None
                candidates: set[Location] = set()
                try:
                    candidates = ADAPTERS[adapter_name](
                        root, case, line, column, args.pysonar.resolve(), args.rope_path
                    )
                except Exception as caught:
                    error = f"{type(caught).__name__}: {caught}"
                elapsed = round(time.monotonic() - started, 3)
                record = {
                    "caseId": case["id"],
                    "repository": case["repository"],
                    "changeCommit": case["changeCommit"],
                    "parentCommit": parent,
                    "oldName": case["oldName"],
                    "newName": case["newName"],
                    "query": {"file": case["queryFile"], "line": line, "column": column},
                    "adapter": adapter_name,
                    "seconds": elapsed,
                    "error": error,
                    "gold": [list(value) for value in sorted(gold)],
                    "candidates": [list(value) for value in sorted(candidates)],
                    **score(candidates, gold),
                }
                records.append(record)
                print(f"{case['id']} {adapter_name}: f1={record['f1']:.3f} "
                      f"safe={record['safeComplete']} error={error or '-'}", flush=True)

    aggregate: dict[str, Any] = {}
    for adapter_name in adapters:
        rows = [row for row in records if row["adapter"] == adapter_name]
        tp = sum(row["truePositive"] for row in rows)
        fp = sum(row["falsePositive"] for row in rows)
        fn = sum(row["falseNegative"] for row in rows)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        aggregate[adapter_name] = {
            "cases": len(rows),
            "errors": sum(row["error"] is not None for row in rows),
            "safeComplete": sum(row["safeComplete"] for row in rows),
            "truePositive": tp,
            "falsePositive": fp,
            "falseNegative": fn,
            "microPrecision": round(precision, 6),
            "microRecall": round(recall, 6),
            "microF1": round(2 * precision * recall / (precision + recall), 6)
            if precision + recall else 0.0,
            "seconds": round(sum(row["seconds"] for row in rows), 3),
        }

    payload = {
        "schemaVersion": 1,
        "benchmark": "historical-change-safety",
        "caseCount": len(selected_cases),
        "adapters": adapters,
        "versions": {
            "pysonar": version([str(args.pysonar), "doctor", "--format", "json"]),
            "python": sys.version.splitlines()[0],
            "jedi": __import__("jedi").__version__,
            "rope": "1.14.0",
            "rg": version(["rg", "--version"]),
        },
        "method": {
            "gold": "executable identifier occurrences changed by the pinned upstream commit",
            "unit": "one-based file, line, and character",
            "safeComplete": "zero missed gold occurrences and zero non-gold candidate edits",
        },
        "aggregate": aggregate,
        "records": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
