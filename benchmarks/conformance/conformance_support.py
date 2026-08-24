from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

BENCHMARK_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BENCHMARK_DIR.parents[1]


def run(command: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=True, **kwargs)


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



