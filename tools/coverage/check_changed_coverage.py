#!/usr/bin/env python3
"""Enforce JaCoCo coverage for executable Kotlin/Java lines changed from a Git base."""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass


SOURCE_ROOTS = (
    "app/src/main/java/",
    "app/src/main/kotlin/",
)


@dataclass(frozen=True)
class CoverageLine:
    missed_instructions: int
    covered_instructions: int

    @property
    def covered(self) -> bool:
        return self.covered_instructions > 0


def parse_jacoco(xml_path: pathlib.Path) -> dict[tuple[str, int], CoverageLine]:
    root = ET.parse(xml_path).getroot()
    result: dict[tuple[str, int], CoverageLine] = {}
    for package in root.findall("package"):
        package_name = package.attrib["name"]
        for source_file in package.findall("sourcefile"):
            relative_path = f"{package_name}/{source_file.attrib['name']}"
            for line in source_file.findall("line"):
                result[(relative_path, int(line.attrib["nr"]))] = CoverageLine(
                    missed_instructions=int(line.attrib.get("mi", "0")),
                    covered_instructions=int(line.attrib.get("ci", "0")),
                )
    return result


def parse_changed_lines(diff_text: str) -> dict[str, set[int]]:
    changed: dict[str, set[int]] = {}
    current_path: str | None = None
    for raw_line in diff_text.splitlines():
        if raw_line.startswith("+++ b/"):
            current_path = raw_line[6:]
            continue
        if raw_line.startswith("+++ /dev/null"):
            current_path = None
            continue
        if not raw_line.startswith("@@") or current_path is None:
            continue
        match = re.search(r"\+(\d+)(?:,(\d+))?", raw_line)
        if match is None:
            continue
        start = int(match.group(1))
        count = int(match.group(2) or "1")
        if count > 0:
            changed.setdefault(current_path, set()).update(range(start, start + count))
    return changed


def jacoco_relative_path(repository_path: str) -> str | None:
    for root in SOURCE_ROOTS:
        if repository_path.startswith(root):
            return repository_path[len(root) :]
    return None


def git_diff_command(base: str) -> list[str]:
    return [
        "git",
        "diff",
        # Reformatting an executable line without changing its tokens must not turn an otherwise
        # covered change set into a coverage failure.
        "--ignore-all-space",
        "--unified=0",
        "--diff-filter=AM",
        base,
        "--",
        *SOURCE_ROOTS,
    ]


def git_diff(base: str) -> str:
    command = git_diff_command(base)
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout


def evaluate(
    changed: dict[str, set[int]],
    coverage: dict[tuple[str, int], CoverageLine],
) -> tuple[int, int, list[str]]:
    executable = 0
    covered = 0
    missed: list[str] = []
    for repository_path, line_numbers in sorted(changed.items()):
        source_path = jacoco_relative_path(repository_path)
        if source_path is None:
            continue
        for line_number in sorted(line_numbers):
            line = coverage.get((source_path, line_number))
            if line is None:
                continue
            executable += 1
            if line.covered:
                covered += 1
            else:
                missed.append(f"{repository_path}:{line_number}")
    return covered, executable, missed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", type=pathlib.Path, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--threshold", type=float, default=0.80)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 0.0 <= args.threshold <= 1.0:
        raise SystemExit("--threshold must be between 0 and 1")
    if not args.xml.is_file():
        raise SystemExit(f"JaCoCo XML report not found: {args.xml}")

    coverage = parse_jacoco(args.xml)
    changed = parse_changed_lines(git_diff(args.base))
    covered, executable, missed = evaluate(changed, coverage)
    ratio = 1.0 if executable == 0 else covered / executable

    print(
        f"Changed executable line coverage: {covered}/{executable} "
        f"({ratio:.1%}), required {args.threshold:.1%}"
    )
    if ratio >= args.threshold:
        return 0

    for location in missed[:50]:
        print(f"UNCOVERED {location}")
    if len(missed) > 50:
        print(f"... and {len(missed) - 50} more uncovered executable lines")
    return 1


if __name__ == "__main__":
    sys.exit(main())
