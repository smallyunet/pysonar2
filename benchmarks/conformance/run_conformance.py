#!/usr/bin/env python3
"""Run pinned, claim-bounded Python conformance evidence for PySonar2."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

MODULE_DIR = Path(__file__).resolve().parent
if str(MODULE_DIR) not in sys.path:
    sys.path.insert(0, str(MODULE_DIR))

from conformance_scoring import legacy_reference_score, typeevalpy_score
from conformance_support import BENCHMARK_DIR, PROJECT_ROOT, normalized_type, position_from_byte_offset, run


DEFAULT_CACHE = Path("/private/tmp/pysonar-conformance-sources")
WORKSPACE_EXCLUDED_COMPONENTS = {
    ".git", ".venv", "node_modules", "target", "build", "dist", "__pycache__"
}


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


def write_results(payload: dict[str, Any], output: Path) -> None:
    detail_dir = output.with_suffix("")
    detail_dir.mkdir(parents=True, exist_ok=True)
    for suite in payload["suites"]:
        inference = suite.get("typeInference")
        if inference is None or "records" not in inference:
            continue
        records_path = detail_dir / f"{suite['id']}-records.jsonl"
        records = inference.pop("records")
        records_path.write_text(
            "".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records)
        )
        inference["recordsFile"] = records_path.relative_to(output.parent).as_posix()
    legacy = payload["preservedReferenceGold"]
    legacy_path = detail_dir / "preserved-reference-records.jsonl"
    records = legacy.pop("records")
    legacy_path.write_text(
        "".join(json.dumps(record, separators=(",", ":")) + "\n" for record in records)
    )
    legacy["recordsFile"] = legacy_path.relative_to(output.parent).as_posix()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, indent=2) + "\n")


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
    write_results(payload, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
