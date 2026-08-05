#!/usr/bin/env python3
"""Merge raw benchmark result sets and emit a compact, auditable summary."""

from __future__ import annotations

import argparse
import json
import re
import statistics
from collections import defaultdict
from pathlib import Path


ANALYZER_RE = re.compile(r"\bpysonar\s+(doctor|plan|context|impact|check)\b")


def aggregate(rows: list[dict[str, object]]) -> dict[str, object]:
    usage = [row["usage"] for row in rows]
    input_tokens = sum(item.get("input_tokens", 0) for item in usage)
    cached_tokens = sum(item.get("cached_input_tokens", 0) for item in usage)
    output_tokens = sum(item.get("output_tokens", 0) for item in usage)
    totals = [item.get("input_tokens", 0) + item.get("output_tokens", 0) for item in usage]
    return {
        "passed": sum(bool(row["score"]["passed"]) for row in rows),
        "trials": len(rows),
        "seconds": round(sum(row["durationSeconds"] for row in rows), 3),
        "medianTrialSeconds": round(statistics.median(row["durationSeconds"] for row in rows), 3),
        "inputTokens": input_tokens,
        "cachedInputTokens": cached_tokens,
        "uncachedInputTokens": input_tokens - cached_tokens,
        "outputTokens": output_tokens,
        "totalTokens": sum(totals),
        "medianTrialTokens": statistics.median(totals),
        "commands": sum(len(row["commands"]) for row in rows),
        "commandOutputChars": sum(row.get("commandOutputChars", 0) for row in rows),
        "sourceReadOutputChars": sum(row.get("sourceReadOutputChars", 0) for row in rows),
        "pysonarSkillReads": sum(
            any("pysonar-code-intelligence/SKILL.md" in command for command in row["commands"])
            for row in rows
        ),
        "analyzerTrials": sum(bool(row["pysonarCommands"]) for row in rows),
        "pysonarUsageValid": sum(bool(row.get("pysonarUsageValid", True)) for row in rows),
    }


def trial_summary(row: dict[str, object]) -> dict[str, object]:
    usage = row["usage"]
    analyzer_commands = []
    for command in row["pysonarCommands"]:
        analyzer_commands.extend(ANALYZER_RE.findall(command))
    return {
        "taskId": row["taskId"],
        "condition": row["condition"],
        "repetition": row["repetition"],
        "passed": row["score"]["passed"],
        "seconds": row["durationSeconds"],
        "inputTokens": usage.get("input_tokens", 0),
        "cachedInputTokens": usage.get("cached_input_tokens", 0),
        "outputTokens": usage.get("output_tokens", 0),
        "totalTokens": usage.get("input_tokens", 0) + usage.get("output_tokens", 0),
        "commands": len(row["commands"]),
        "commandOutputChars": row.get("commandOutputChars", 0),
        "sourceReadOutputChars": row.get("sourceReadOutputChars", 0),
        "pysonarSkillRead": any(
            "pysonar-code-intelligence/SKILL.md" in command for command in row["commands"]
        ),
        "analyzerCommands": analyzer_commands,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--base-commit", required=True)
    parser.add_argument("--codex-cli", required=True)
    parser.add_argument("--date", required=True)
    args = parser.parse_args()

    merged = {}
    for path in args.inputs:
        for row in json.loads(path.read_text()):
            key = (row["taskId"], row["condition"], row["repetition"])
            merged[key] = row
    rows = sorted(merged.values(), key=lambda row: (
        row["taskId"], row["condition"], row["repetition"]
    ))
    conditions = sorted(
        {row["condition"] for row in rows}, key=lambda condition: condition != "control"
    )
    if "control" not in conditions or len(conditions) != 2:
        raise ValueError(f"expected control plus one treatment condition, got {conditions}")
    treatment_name = next(condition for condition in conditions if condition != "control")
    by_condition = {
        condition: aggregate([row for row in rows if row["condition"] == condition])
        for condition in conditions
    }
    by_task = defaultdict(dict)
    for task_id in sorted({row["taskId"] for row in rows}):
        for condition in conditions:
            selected = [
                row for row in rows
                if row["taskId"] == task_id and row["condition"] == condition
            ]
            by_task[task_id][condition] = aggregate(selected)
    control = by_condition["control"]
    treatment = by_condition[treatment_name]
    mode = rows[0].get("mode", "natural")
    output = {
        "date": args.date,
        "baseCommit": args.base_commit,
        "implementationState": "base commit plus the uncommitted changes documented by this result",
        "codexCli": args.codex_cli,
        "model": rows[0]["model"],
        "reasoningEffort": "medium",
        "scheduleSeed": rows[0].get("scheduleSeed"),
        "repetitions": max(row["repetition"] for row in rows),
        "taskCount": len(by_task),
        "promptConditions": (
            "byte-identical; only Skill and CLI availability differ"
            if mode == "natural"
            else "control forbids PySonar2; treatment requires a successful analyzer discovery call"
        ),
        "tokenDefinition": "input_tokens + output_tokens; cached input is a subset of input",
        "aggregate": by_condition,
        f"{treatment_name}MinusControl": {
            "totalTokens": treatment["totalTokens"] - control["totalTokens"],
            "totalTokensPercent": round(
                (treatment["totalTokens"] / control["totalTokens"] - 1) * 100, 3
            ),
            "seconds": round(treatment["seconds"] - control["seconds"], 3),
            "secondsPercent": round(
                (treatment["seconds"] / control["seconds"] - 1) * 100, 3
            ),
        },
        "tasks": by_task,
        "trials": [trial_summary(row) for row in rows],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
