#!/usr/bin/env python3
"""Run pinned, claim-bounded Python conformance evidence for PySonar2."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import tempfile
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


BENCHMARK_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BENCHMARK_DIR.parents[1]
DEFAULT_CACHE = Path("/private/tmp/pysonar-conformance-sources")
WORKSPACE_EXCLUDED_COMPONENTS = {
    ".git", ".venv", "node_modules", "target", "build", "dist", "__pycache__"
}


def run(command: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=True, **kwargs)


def load_manifest() -> dict[str, Any]:
    return json.loads((BENCHMARK_DIR / "suites.json").read_text())


def repository_name(url: str) -> str:
    return url.removesuffix(".git").rsplit("/", 1)[-1].lower()


def ensure_repository(suite: dict[str, Any], cache: Path) -> Path:
    destination = cache / repository_name(suite["url"])
    if not destination.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--filter=blob:none", "--no-checkout", suite["url"], str(destination)])
    try:
        run(["git", "-C", str(destination), "cat-file", "-e", f"{suite['commit']}^{{commit}}"])
    except subprocess.CalledProcessError:
        run(["git", "-C", str(destination), "fetch", "origin", suite["commit"]])
    current = run(["git", "-C", str(destination), "rev-parse", "HEAD"]).stdout.strip()
    if current != suite["commit"]:
        run(["git", "-C", str(destination), "checkout", "--detach", suite["commit"]])
    return destination


def analyze(binary: Path, root: Path, timeout: int = 600) -> dict[str, Any]:
    started = time.monotonic()
    command = [str(binary), "analyze", "--root", str(root), "--format", "json"]
    try:
        process = run(command, timeout=timeout)
        payload = json.loads(process.stdout)
        payload["processStatus"] = "completed"
    except subprocess.CalledProcessError as error:
        payload = {
            "processStatus": "failed",
            "exitCode": error.returncode,
            "stdout": error.stdout.strip(),
            "stderr": error.stderr.strip(),
        }
    except subprocess.TimeoutExpired as error:
        payload = {"processStatus": "timeout", "timeoutSeconds": error.timeout}
    payload["wallSeconds"] = round(time.monotonic() - started, 3)
    return payload


def count_files(root: Path, suffix: str) -> int:
    return sum(1 for path in root.rglob(f"*{suffix}") if path.is_file())


def excluded_source_paths(root: Path, suffix: str) -> list[str]:
    return sorted(
        path.relative_to(root).as_posix()
        for path in root.rglob(f"*{suffix}")
        if path.is_file() and any(part in WORKSPACE_EXCLUDED_COMPONENTS for part in path.relative_to(root).parts)
    )


def create_typeshed_mirror(source: Path, destination: Path) -> int:
    count = 0
    for subtree in ("stdlib", "stubs"):
        for path in (source / subtree).rglob("*.pyi"):
            relative = path.relative_to(source)
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            count += 1
    return count


def create_utf8_mirror(source: Path, destination: Path) -> tuple[int, list[str]]:
    copied = 0
    excluded: list[str] = []
    for path in source.rglob("*.py"):
        relative = path.relative_to(source)
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            excluded.append(relative.as_posix())
            continue
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        copied += 1
    return copied, excluded


def normalized_type(value: str | None, *, function_value: bool = False) -> str | None:
    if value is None:
        return None
    if value.startswith("async fn") or value.startswith("fn"):
        if function_value:
            return "callable"
        return value.rsplit(" -> ", 1)[-1]
    if value.startswith("instance "):
        return value.removeprefix("instance ").rsplit(".", 1)[-1]
    if value.startswith("class "):
        return value.removeprefix("class ").rsplit(".", 1)[-1]
    if value.startswith("list-elements["):
        return "list"
    for collection in ("list", "dict", "set", "tuple"):
        if value.startswith(f"{collection}["):
            return collection
    return value


def position_from_byte_offset(source: str, offset: int) -> tuple[int, int]:
    prefix = source.encode("utf-8")[:offset].decode("utf-8")
    line = prefix.count("\n") + 1
    tail = prefix.rsplit("\n", 1)[-1]
    character = len(tail.encode("utf-16-le")) // 2 + 1
    return line, character


def typeevalpy_score(binary: Path, root: Path) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    for gold_path in sorted(root.rglob("*_gt.json")):
        case_root = gold_path.parent
        for expected in json.loads(gold_path.read_text()):
            source = case_root / expected["file"]
            if not source.is_file():
                records.append({"gold": expected, "status": "missing-source"})
                continue
            command = [
                str(binary), "context", "--root", str(case_root), "--file", expected["file"],
                "--line", str(expected["line_number"]), "--character", str(expected["col_offset"]),
                "--max-results", "20", "--format", "json",
            ]
            try:
                payload = json.loads(run(command, timeout=30).stdout)
                actual = normalized_type(
                    payload.get("inferredType"),
                    function_value="function" not in expected,
                )
                expected_types = expected.get("type", [])
                status = "exact" if actual in expected_types else ("missing" if actual is None else "wrong")
                records.append({
                    "case": str(gold_path.relative_to(root)),
                    "line": expected["line_number"],
                    "character": expected["col_offset"],
                    "element": expected.get("variable") or expected.get("parameter") or expected.get("function"),
                    "expected": expected_types,
                    "rawActual": payload.get("inferredType"),
                    "actual": actual,
                    "applicable": payload.get("applicable"),
                    "status": status,
                })
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
                records.append({"gold": expected, "status": "error", "error": str(error)})
    counts = Counter(record["status"] for record in records)
    categories: dict[str, Counter[str]] = {}
    for record in records:
        case = record.get("case")
        if not case:
            continue
        parts = Path(case).parts
        category = (
            parts[1]
            if len(parts) > 1 and parts[0] in {"python_features", "analysis_sensitivities"}
            else "other"
        )
        categories.setdefault(category, Counter())[record["status"]] += 1
    total = len(records)
    return {
        "goldItems": total,
        "exact": counts["exact"],
        "wrong": counts["wrong"],
        "missing": counts["missing"],
        "errors": counts["error"] + counts["missing-source"],
        "exactRate": round(counts["exact"] / total, 6) if total else 0.0,
        "byCategory": {
            category: {
                "total": sum(values.values()),
                "exact": values["exact"],
                "wrong": values["wrong"],
                "missing": values["missing"],
                "errors": values["error"] + values["missing-source"],
            }
            for category, values in sorted(categories.items())
        },
        "records": records,
    }


def legacy_reference_score(binary: Path) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    for gold_path in sorted((PROJECT_ROOT / "tests").rglob("refs.json")):
        fixture = gold_path.parent
        for expected in json.loads(gold_path.read_text()):
            reference = expected["ref"]
            line, character = reference["line"], reference["col"]
            if line <= 0 or character <= 0:
                source = (fixture / reference["file"]).read_text()
                offset = reference["start"]
                if offset == 0:
                    match = re.search(rf"\b{re.escape(reference['name'])}\b", source)
                    if match is not None:
                        offset = len(source[:match.start()].encode("utf-8"))
                line, character = position_from_byte_offset(source, offset)
            command = [
                str(binary), "context", "--root", str(fixture), "--file", reference["file"],
                "--line", str(line), "--character", str(character),
                "--max-results", "100", "--format", "json",
            ]
            try:
                payload = json.loads(run(command, timeout=30).stdout)
                actual_destinations = {
                    (item["file"], item["startLine"], item["startCharacter"])
                    for item in payload.get("definitions", [])
                }
                expected_destinations = {
                    (item["file"], max(1, item["line"]), max(1, item["col"]))
                    for item in expected["dests"]
                }
                expected_types = sorted({item["type"] for item in expected["dests"]})
                actual_type = normalized_type(payload.get("inferredType"))
                records.append({
                    "fixture": str(fixture.relative_to(PROJECT_ROOT)),
                    "reference": reference,
                    "query": {"line": line, "character": character},
                    "expectedDestinations": sorted(expected_destinations),
                    "actualDestinations": sorted(actual_destinations),
                    "destinationExact": actual_destinations == expected_destinations,
                    "expectedTypes": expected_types,
                    "rawActualType": payload.get("inferredType"),
                    "actualType": actual_type,
                    "typeExact": actual_type in expected_types,
                })
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
                records.append({"fixture": str(fixture.relative_to(PROJECT_ROOT)), "reference": reference, "error": str(error)})
    valid = [record for record in records if "error" not in record]
    destination_exact = sum(bool(record["destinationExact"]) for record in valid)
    type_exact = sum(bool(record["typeExact"]) for record in valid)
    total = len(records)
    return {
        "referenceCases": total,
        "destinationExact": destination_exact,
        "destinationExactRate": round(destination_exact / total, 6) if total else 0.0,
        "typeExact": type_exact,
        "typeExactRate": round(type_exact / total, 6) if total else 0.0,
        "errors": total - len(valid),
        "records": records,
    }


def corpus_result(binary: Path, suite: dict[str, Any], repository: Path) -> dict[str, Any]:
    root = repository / suite["root"]
    source_count = count_files(root, suite["sourceExtension"])
    result: dict[str, Any] = {
        "id": suite["id"],
        "authority": suite["authority"],
        "repository": suite["url"],
        "commit": suite["commit"],
        "version": suite.get("version"),
        "assessment": suite["assessment"],
        "claimBoundary": suite["claimBoundary"],
        "sourceCount": source_count,
    }
    if suite["id"] == "typeshed":
        result["workspaceExcludedStubPaths"] = excluded_source_paths(repository, ".pyi")
        with tempfile.TemporaryDirectory(prefix="pysonar-typeshed-") as temp:
            mirror = Path(temp)
            result["mirroredStubCount"] = create_typeshed_mirror(repository, mirror)
            result["analysis"] = analyze(binary, mirror)
            result["nativeStubDiscoverySupported"] = (
                result["analysis"].get("fileCount") == result["mirroredStubCount"]
            )
            result["sourceTransform"] = (
                "Copied stdlib and third-party .pyi files unchanged into an isolated mirror."
            )
    else:
        result["analysis"] = analyze(binary, root)
        if suite["id"] == "cpython-regression-corpus":
            failures = result["analysis"].get("failedFiles", [])
            expected_invalid = [path for path in failures if "badsyntax" in path.lower()]
            result["expectedInvalidSyntaxFiles"] = expected_invalid
            result["unexpectedParseFailures"] = [
                path for path in failures if path not in expected_invalid
            ]
        if suite["id"] == "cpython-regression-corpus" and result["analysis"]["processStatus"] == "failed":
            with tempfile.TemporaryDirectory(prefix="pysonar-cpython-utf8-") as temp:
                mirror = Path(temp)
                copied, excluded = create_utf8_mirror(root, mirror)
                result["utf8Mirror"] = {
                    "copiedFiles": copied,
                    "excludedEncodingFiles": excluded,
                    "analysis": analyze(binary, mirror),
                    "sourceTransform": "Excluded only .py files that Python 3.10 could not decode as UTF-8; decoded contents were unchanged.",
                }
                failures = result["utf8Mirror"]["analysis"].get("failedFiles", [])
                expected_invalid = [path for path in failures if "badsyntax" in path.lower()]
                result["utf8Mirror"]["expectedInvalidSyntaxFiles"] = expected_invalid
                result["utf8Mirror"]["unexpectedParseFailures"] = [
                    path for path in failures if path not in expected_invalid
                ]
    if suite["id"] == "python-typing-conformance":
        result["officialDiagnosticConformanceScored"] = False
        result["reason"] = "Strict type checking is outside the current PySonar2 product scope."
    if suite["id"] == "typeevalpy-micro":
        result["typeInference"] = typeevalpy_score(binary, root)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, default=PROJECT_ROOT / "target/release/pysonar")
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--suite", action="append", dest="suite_ids")
    args = parser.parse_args()
    binary = args.binary.resolve()
    if not binary.is_file():
        parser.error(f"PySonar2 binary does not exist: {binary}")

    manifest = load_manifest()
    suites = manifest["suites"]
    if args.suite_ids:
        requested = set(args.suite_ids)
        suites = [suite for suite in suites if suite["id"] in requested]
        missing = requested - {suite["id"] for suite in suites}
        if missing:
            parser.error(f"Unknown suite(s): {', '.join(sorted(missing))}")

    repositories: dict[str, Path] = {}
    results = []
    for suite in suites:
        repositories.setdefault(suite["url"], ensure_repository(suite, args.cache))
        print(f"running {suite['id']}...", flush=True)
        results.append(corpus_result(binary, suite, repositories[suite["url"]]))

    print("running preserved-reference-gold...", flush=True)
    legacy = legacy_reference_score(binary)
    doctor = json.loads(run([str(binary), "doctor", "--format", "json"]).stdout)
    payload = {
        "schemaVersion": 1,
        "benchmark": "pysonar2-python-conformance",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "pysonar": doctor,
        "manifest": "benchmarks/conformance/suites.json",
        "suites": results,
        "preservedReferenceGold": legacy,
        "interpretation": {
            "parseCoverage": "Robustness and syntax acceptance only; not semantic correctness.",
            "typeInference": "Exact matches after documented PySonar2 display normalization.",
            "referenceGold": "Exact definition destinations and independently scored inferred types.",
            "unsupported": "Unsupported capabilities are reported, never counted as passes."
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
