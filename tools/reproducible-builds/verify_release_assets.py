#!/usr/bin/env python3
"""Validate the complete public checksum inventory before trusting any assets."""

import hashlib
from pathlib import Path
import re
import sys

UNSIGNED = (
    "bitchat-android-arm64-unsigned.apk",
    "bitchat-android-armv7-unsigned.apk",
    "bitchat-android-release-unsigned.aab",
    "bitchat-android-universal-unsigned.apk",
    "bitchat-android-wear-release-unsigned.aab",
    "bitchat-android-wear-unsigned.apk",
    "bitchat-android-x86-unsigned.apk",
    "bitchat-android-x86_64-unsigned.apk",
)
SIGNED = (
    "bitchat-android-arm64.apk",
    "bitchat-android-universal.apk",
    "bitchat-android-wear.apk",
    "bitchat-android-x86_64.apk",
    "bitchat-android-play-upload.aab",
    "bitchat-android-wear-play-upload.aab",
)
EXPECTED = set(UNSIGNED + SIGNED + ("BITCHAT_BUILDINFO.json", "BITCHAT_SHA256SUMS.unsigned"))


def verify(directory: Path) -> None:
    entries = {}
    for line in (directory / "BITCHAT_SHA256SUMS").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64}) [ *]([^\r\n]+)", line)
        if not match:
            raise ValueError("invalid checksum record")
        digest, name = match.groups()
        # A fixed inventory also rejects absolute paths, ../, and duplicates.
        if name not in EXPECTED or name in entries:
            raise ValueError("unexpected or duplicate checksum filename")
        entries[name] = digest.lower()
    if entries.keys() != EXPECTED:
        raise ValueError("checksum manifest must cover every release artifact")
    for name, expected in entries.items():
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("release artifact missing or not a regular file")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise ValueError(f"checksum mismatch: {name}")


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("usage: verify_release_assets.py RELEASE_DIR")
        verify(Path(sys.argv[1]))
    except (OSError, UnicodeError, ValueError) as error:
        print(f"error: release verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
