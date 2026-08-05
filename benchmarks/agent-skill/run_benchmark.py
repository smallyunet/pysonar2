#!/usr/bin/env python3
"""Run isolated Codex Skill-vs-No-Skill trials and emit auditable JSON results."""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import shutil
import subprocess
import tempfile
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BENCHMARK_DIR = Path(__file__).resolve().parent
DEFAULT_MODEL = "gpt-5.6-sol"
CONDITIONS = ("control", "treatment")
MODES = ("natural", "forced-tool")
ANALYZER_COMMAND_RE = re.compile(
    r"(?<![A-Za-z0-9_.-])pysonar\s+(?:doctor|plan|context|impact|check)\b"
)
SOURCE_READ_COMMAND_RE = re.compile(r"(?:^|[;&|\s])(rg|sed|find|head|tail)(?:\s|$)")


def load_tasks() -> list[dict[str, str]]:
    return json.loads((BENCHMARK_DIR / "tasks.json").read_text())


def make_codex_home(root: Path, condition: str, mode: str) -> Path:
    home = root / f"codex-home-{condition}"
    home.mkdir(parents=True)
    auth = Path.home() / ".codex" / "auth.json"
    if not auth.exists():
        raise RuntimeError(f"Codex auth not found at {auth}")
    (home / "auth.json").symlink_to(auth)
    if mode == "natural" and condition == "treatment":
        skill_source = ROOT / "skills" / "pysonar-code-intelligence"
        skills = home / "skills"
        skills.mkdir()
        (skills / "pysonar-code-intelligence").symlink_to(skill_source)
    return home


def trial_prompt(task: dict[str, str], condition: str, mode: str) -> str:
    policy = ""
    if mode == "forced-tool" and condition == "control":
        policy = """
You must not use PySonar2 or any `pysonar` command. Use ordinary source-search and file-reading tools.
"""
    elif mode == "forced-tool" and condition == "treatment":
        pysonar_command = (
            f"pysonar plan --root . --symbol {task['symbol']} --intent change "
            "--max-results 8 --format compact-json"
        )
        policy = f"""
You must use PySonar2 before broad source inspection. First run exactly this discovery command:
`{pysonar_command}`
Use its result to guide the implementation.
Do not load or rely on a Skill to decide whether PySonar2 is needed. Do not run `pysonar doctor`
unless an analysis command fails, and do not run `pysonar check` unless project validation is unavailable.
"""
    return f"""You are completing one benchmark task in an isolated Python repository.

Task: {task['prompt']}
{policy}

Do not commit. Do not read outside this repository except for automatically available task instructions.
Finish with a concise summary and the tests you ran.
"""


def parse_events(stdout: str) -> dict[str, object]:
    usage: dict[str, int] = {}
    commands: list[str] = []
    successful_commands: list[str] = []
    command_output_chars = 0
    source_read_output_chars = 0
    final = ""
    event_count = 0
    completed_items = 0
    agent_messages = 0
    for line in stdout.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        event_count += 1
        if event.get("type") == "turn.completed":
            usage = event.get("usage", {})
        item = event.get("item", {})
        if event.get("type") == "item.completed":
            completed_items += 1
        if event.get("type") == "item.completed" and item.get("type") == "command_execution":
            command = item.get("command", "")
            output_length = len(item.get("aggregated_output", ""))
            commands.append(command)
            if item.get("exit_code") == 0:
                successful_commands.append(command)
            command_output_chars += output_length
            if SOURCE_READ_COMMAND_RE.search(command):
                source_read_output_chars += output_length
        if item.get("type") == "agent_message":
            final = item.get("text", final)
            if event.get("type") == "item.completed":
                agent_messages += 1
    pysonar_commands = [
        command for command in successful_commands if ANALYZER_COMMAND_RE.search(command)
    ]
    return {
        "usage": usage,
        "eventCount": event_count,
        "completedItems": completed_items,
        "agentMessages": agent_messages,
        "commands": commands,
        "commandOutputChars": command_output_chars,
        "sourceReadOutputChars": source_read_output_chars,
        "pysonarCommands": pysonar_commands,
        "finalMessage": final,
    }


def validate(task_id: str, worktree: Path) -> dict[str, object]:
    validator = BENCHMARK_DIR / "validators" / f"{task_id}.py"
    started = time.monotonic()
    result = subprocess.run(
        ["python3", str(validator), str(worktree)],
        text=True,
        capture_output=True,
        timeout=60,
    )
    return {
        "passed": result.returncode == 0,
        "exitCode": result.returncode,
        "durationSeconds": round(time.monotonic() - started, 3),
        "stdout": result.stdout.strip(),
        "stderr": result.stderr.strip(),
    }


def run_trial(
    task: dict[str, str],
    condition: str,
    repetition: int,
    model: str,
    run_root: Path,
    results_dir: Path,
    allow_unsandboxed_child: bool,
    mode: str,
) -> dict[str, object]:
    trial_id = f"{task['id']}--{condition}--r{repetition}"
    worktree = run_root / "worktrees" / trial_id
    shutil.copytree(BENCHMARK_DIR / "fixtures" / task["id"], worktree)
    codex_home = make_codex_home(run_root / trial_id, condition, mode)
    env = os.environ.copy()
    env["CODEX_HOME"] = str(codex_home)
    if condition == "treatment":
        env["PATH"] = f"{ROOT / 'bin'}:{env['PATH']}"
    command = [
        "codex", "exec", "--ephemeral", "--ignore-user-config",
        "--skip-git-repo-check", "--json", "--color", "never",
        "--model", model, "-c", 'model_reasoning_effort="medium"',
    ]
    if allow_unsandboxed_child:
        # Codex Desktop already applies an outer sandbox. A nested macOS
        # sandbox-exec fails with sandbox_apply (exit 71), so the disposable
        # child run must not attempt to install another sandbox profile.
        command.append("--dangerously-bypass-approvals-and-sandbox")
    else:
        command.extend(("--sandbox", "workspace-write"))
    command.extend(("--cd", str(worktree), trial_prompt(task, condition, mode)))
    started = time.monotonic()
    process = subprocess.run(command, text=True, capture_output=True, env=env, timeout=900)
    duration = round(time.monotonic() - started, 3)
    parsed = parse_events(process.stdout)
    score = validate(task["id"], worktree)
    pysonar_usage_valid = (
        mode != "forced-tool"
        or (condition == "control" and not parsed["pysonarCommands"])
        or (condition == "treatment" and bool(parsed["pysonarCommands"]))
    )
    raw_path = results_dir / "raw" / f"{trial_id}.jsonl"
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    raw_path.write_text(process.stdout)
    stderr_path = results_dir / "raw" / f"{trial_id}.stderr.txt"
    stderr_path.write_text(process.stderr)
    return {
        "trialId": trial_id,
        "taskId": task["id"],
        "category": task["category"],
        "condition": condition,
        "mode": mode,
        "repetition": repetition,
        "model": model,
        "durationSeconds": duration,
        "agentExitCode": process.returncode,
        "score": score,
        "pysonarUsageValid": pysonar_usage_valid,
        **parsed,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--repetitions", type=int, default=1)
    parser.add_argument("--seed", type=int, default=20260803)
    parser.add_argument("--mode", choices=MODES, default="natural")
    parser.add_argument("--task", action="append", dest="tasks")
    parser.add_argument("--condition", action="append", choices=CONDITIONS)
    parser.add_argument("--results-dir", type=Path, required=True)
    parser.add_argument(
        "--allow-unsandboxed-child",
        action="store_true",
        help=(
            "disable the child Codex sandbox; use only when an outer sandbox "
            "already confines these disposable fixtures"
        ),
    )
    args = parser.parse_args()

    selected = [task for task in load_tasks() if not args.tasks or task["id"] in args.tasks]
    conditions = args.condition or list(CONDITIONS)
    args.results_dir.mkdir(parents=True, exist_ok=True)
    # Use /private/tmp explicitly on macOS. The process environment's TMPDIR points
    # into /var/folders, where nested Codex sandboxes cannot apply their profile.
    with tempfile.TemporaryDirectory(
        prefix="pysonar-agent-benchmark-", dir="/private/tmp"
    ) as temporary:
        run_root = Path(temporary)
        records = []
        schedule = [
            (task, condition, repetition)
            for repetition in range(1, args.repetitions + 1)
            for task in selected
            for condition in conditions
        ]
        random.Random(args.seed).shuffle(schedule)
        for task, condition, repetition in schedule:
            print(f"running {task['id']} / {condition} / r{repetition}", flush=True)
            record = run_trial(
                task,
                condition,
                repetition,
                args.model,
                run_root,
                args.results_dir,
                args.allow_unsandboxed_child,
                args.mode,
            )
            record["scheduleSeed"] = args.seed
            records.append(record)
            print(
                f"  pass={record['score']['passed']} duration={record['durationSeconds']}s "
                f"pysonar_calls={len(record['pysonarCommands'])} "
                f"usage_valid={record['pysonarUsageValid']}",
                flush=True,
            )
    output = args.results_dir / "results.json"
    output.write_text(json.dumps(records, indent=2) + "\n")
    print(f"wrote {output}")
    return 0 if all(
        record["score"]["passed"] and record["pysonarUsageValid"] for record in records
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())
