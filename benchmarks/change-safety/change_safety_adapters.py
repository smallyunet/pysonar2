from __future__ import annotations

import difflib
import io
import json
import re
import subprocess
import sys
import tokenize
from pathlib import Path
from typing import Any, Callable

Location = tuple[str, int, int]
AdapterOutput = tuple[set[Location], dict[str, Any]]


def run(command: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=True, **kwargs)


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


def normalize_path(root: Path, candidate: Path | str) -> str | None:
    path = Path(candidate).resolve()
    try:
        return path.relative_to(root.resolve()).as_posix()
    except ValueError:
        return None


def pysonar_adapter(root: Path, case: dict[str, str], line: int, column: int,
                    pysonar: Path, _: Path | None) -> AdapterOutput:
    result = run([
        str(pysonar), "impact", "--root", str(root), "--file", case["queryFile"],
        "--line", str(line), "--character", str(column), "--max-results", "10000",
        "--format", "json",
    ], timeout=300)
    payload = json.loads(result.stdout)
    locations = payload.get("definitions", []) + payload.get("references", [])
    candidates = {(item["file"], item["startLine"], item["startCharacter"])
                  for item in locations}
    metadata = {
        "coverageStatus": payload.get("coverageStatus"),
        "applicable": payload.get("applicable"),
        "confidence": payload.get("confidence"),
        "coverage": payload.get("coverage"),
        "unsupportedSemantics": payload.get("unsupportedSemantics", []),
        "limitations": payload.get("limitations", []),
    }
    return candidates, metadata


def jedi_adapter(root: Path, case: dict[str, str], line: int, column: int,
                 _: Path, __: Path | None) -> AdapterOutput:
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
    return result, {}


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
                 _: Path, tool_path: Path | None) -> AdapterOutput:
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
            return result, {}
        finally:
            project.close()
    finally:
        sys.path.remove(str(tool_path))


def rg_adapter(root: Path, case: dict[str, str], _: int, __: int,
               ___: Path, ____: Path | None) -> AdapterOutput:
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
    return result, {}


ADAPTERS: dict[str, Callable[..., AdapterOutput]] = {
    "pysonar": pysonar_adapter,
    "jedi": jedi_adapter,
    "rope": rope_adapter,
    "rg": rg_adapter,
}



