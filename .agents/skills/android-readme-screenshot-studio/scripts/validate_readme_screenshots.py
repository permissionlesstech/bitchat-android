#!/usr/bin/env python3
"""Validate high-resolution README screenshot assets without external packages."""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def fail(message: str) -> None:
    raise ValueError(message)


def png_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != PNG_SIGNATURE or header[12:16] != b"IHDR":
        fail(f"{path.name} is not a valid PNG with an IHDR header")
    width, height = struct.unpack(">II", header[16:24])
    if width <= 0 or height <= 0:
        fail(f"{path.name} has invalid dimensions: {width}x{height}")
    return width, height


def resolve_inside(root: Path, value: Path, label: str) -> tuple[Path, Path]:
    candidate = value if value.is_absolute() else root / value
    resolved = candidate.resolve()
    try:
        relative = resolved.relative_to(root)
    except ValueError as exc:
        raise ValueError(f"{label} must stay inside the repository") from exc
    if not resolved.is_file():
        fail(f"{label} does not exist: {relative.as_posix()}")
    return resolved, relative


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate README PNG references, dimensions, and repository-safe paths."
    )
    parser.add_argument("--repo-root", type=Path, default=Path("."))
    parser.add_argument("--readme", type=Path, default=Path("README.md"))
    parser.add_argument("--asset", action="append", type=Path, required=True)
    parser.add_argument("--min-width", type=int, default=1080)
    parser.add_argument("--min-height", type=int, default=1920)
    parser.add_argument("--require-same-size", action="store_true")
    args = parser.parse_args()

    root = args.repo_root.resolve()
    if not root.is_dir():
        fail("repository root does not exist")
    if args.min_width <= 0 or args.min_height <= 0:
        fail("minimum dimensions must be positive")

    readme_path, readme_relative = resolve_inside(root, args.readme, "README")
    readme_text = readme_path.read_text(encoding="utf-8")

    assets: list[dict[str, object]] = []
    seen: set[Path] = set()
    dimensions: set[tuple[int, int]] = set()

    for index, value in enumerate(args.asset):
        asset_path, relative = resolve_inside(root, value, f"asset[{index}]")
        if asset_path in seen:
            fail(f"duplicate asset: {relative.as_posix()}")
        seen.add(asset_path)

        if asset_path.suffix.lower() != ".png":
            fail(f"README screenshot must be a PNG: {relative.as_posix()}")
        if relative.parts[:2] != ("docs", "screenshots"):
            fail(
                "README screenshots must live under docs/screenshots: "
                f"{relative.as_posix()}"
            )

        reference = relative.as_posix()
        if reference not in readme_text and f"./{reference}" not in readme_text:
            fail(f"README does not reference asset: {reference}")

        width, height = png_dimensions(asset_path)
        if width < args.min_width or height < args.min_height:
            fail(
                f"{reference} is below the high-resolution minimum: "
                f"{width}x{height} < {args.min_width}x{args.min_height}"
            )
        dimensions.add((width, height))
        assets.append({"path": reference, "width": width, "height": height})

    if args.require_same_size and len(dimensions) != 1:
        rendered = ", ".join(f"{width}x{height}" for width, height in sorted(dimensions))
        fail(f"README screenshots do not share one size: {rendered}")

    print(
        json.dumps(
            {
                "status": "ok",
                "readme": readme_relative.as_posix(),
                "assets": assets,
                "same_size": len(dimensions) == 1,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)

