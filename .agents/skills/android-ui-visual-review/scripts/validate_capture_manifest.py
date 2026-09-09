#!/usr/bin/env python3
"""Validate a visual-review capture manifest and its PNG evidence."""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def fail(message: str) -> None:
    raise ValueError(message)


def relative_file(root: Path, value: object, field: str) -> Path:
    if not isinstance(value, str) or not value:
        fail(f"{field} must be a non-empty relative path")
    candidate = Path(value)
    if candidate.is_absolute() or ".." in candidate.parts:
        fail(f"{field} must stay relative to the artifact directory: {value!r}")
    resolved = (root / candidate).resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as exc:
        raise ValueError(f"{field} escapes the artifact directory: {value!r}") from exc
    if not resolved.is_file():
        fail(f"{field} does not exist: {value}")
    return resolved


def png_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != PNG_SIGNATURE or header[12:16] != b"IHDR":
        fail(f"not a valid PNG with an IHDR header: {path.name}")
    width, height = struct.unpack(">II", header[16:24])
    if width <= 0 or height <= 0:
        fail(f"invalid PNG dimensions in {path.name}: {width}x{height}")
    return width, height


def require_string(data: dict[str, object], key: str) -> str:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip():
        fail(f"{key} must be a non-empty string")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()

    manifest_path = args.manifest.resolve()
    if not manifest_path.is_file():
        fail(f"manifest not found: {manifest_path}")
    root = manifest_path.parent

    with manifest_path.open(encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        fail("manifest root must be an object")

    require_string(data, "target")
    before_sha = require_string(data, "before_sha")
    after_sha = require_string(data, "after_sha")
    if not SHA_RE.fullmatch(before_sha) or not SHA_RE.fullmatch(after_sha):
        fail("before_sha and after_sha must be lowercase 40-character Git SHAs")
    if before_sha == after_sha:
        fail("before_sha and after_sha must differ")

    environment = data.get("environment")
    if not isinstance(environment, dict):
        fail("environment must be an object")
    for key in ("android_release", "android_sdk", "security_patch", "resolution_px"):
        require_string(environment, key)
    density = environment.get("default_density_dpi")
    if not isinstance(density, int) or density <= 0:
        fail("environment.default_density_dpi must be a positive integer")

    captures = data.get("captures")
    if not isinstance(captures, list) or not captures:
        fail("captures must contain at least one before/after pair")

    used_paths: set[Path] = set()
    pair_summaries: list[dict[str, object]] = []
    seen_ids: set[str] = set()

    for index, item in enumerate(captures):
        if not isinstance(item, dict):
            fail(f"captures[{index}] must be an object")
        capture_id = require_string(item, "id")
        if capture_id in seen_ids:
            fail(f"duplicate capture id: {capture_id}")
        seen_ids.add(capture_id)
        require_string(item, "surface")
        require_string(item, "state")
        require_string(item, "expected_change")

        before_path = relative_file(root, item.get("before"), f"captures[{index}].before")
        after_path = relative_file(root, item.get("after"), f"captures[{index}].after")
        for path in (before_path, after_path):
            if path in used_paths:
                fail(f"screenshot reused by multiple manifest entries: {path.name}")
            used_paths.add(path)

        before_size = png_dimensions(before_path)
        after_size = png_dimensions(after_path)
        if before_size != after_size:
            fail(
                f"{capture_id} dimensions differ: before={before_size[0]}x{before_size[1]}, "
                f"after={after_size[0]}x{after_size[1]}"
            )
        pair_summaries.append(
            {
                "id": capture_id,
                "width": before_size[0],
                "height": before_size[1],
            }
        )

    extras = data.get("extras", [])
    if not isinstance(extras, list):
        fail("extras must be an array")
    for index, item in enumerate(extras):
        if not isinstance(item, dict):
            fail(f"extras[{index}] must be an object")
        extra_id = require_string(item, "id")
        if extra_id in seen_ids:
            fail(f"duplicate capture/extra id: {extra_id}")
        seen_ids.add(extra_id)
        require_string(item, "role")
        path = relative_file(root, item.get("file"), f"extras[{index}].file")
        if path in used_paths:
            fail(f"screenshot reused by multiple manifest entries: {path.name}")
        used_paths.add(path)
        png_dimensions(path)

    limitations = data.get("limitations", [])
    if not isinstance(limitations, list) or not all(
        isinstance(item, str) and item.strip() for item in limitations
    ):
        fail("limitations must be an array of non-empty strings")

    result = {
        "status": "ok",
        "pairs": len(captures),
        "extras": len(extras),
        "png_files": len(used_paths),
        "dimensions": pair_summaries,
    }
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)

