from __future__ import annotations

import json
import re
import subprocess
from collections import Counter
from pathlib import Path
from typing import Any

from conformance_support import PROJECT_ROOT, normalized_type, position_from_byte_offset, run


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



