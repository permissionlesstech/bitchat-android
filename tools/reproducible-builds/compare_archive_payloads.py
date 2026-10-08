#!/usr/bin/env python3
"""Compare ZIP payloads without extracting files or interpreting names as globs."""

import hashlib
import sys
import zipfile


def is_signing_metadata(name: str) -> bool:
    parts = name.split("/")
    if len(parts) != 2 or parts[0].upper() != "META-INF":
        return False
    leaf = parts[1].upper()
    return leaf == "MANIFEST.MF" or leaf.endswith((".SF", ".RSA", ".DSA", ".EC"))


def payload_manifest(path: str) -> dict[str, str]:
    result = {}
    seen = set()
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.filename in seen:
                raise ValueError("duplicate ZIP entry")
            seen.add(entry.filename)
            if entry.is_dir() or is_signing_metadata(entry.filename):
                continue
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            result[entry.filename] = digest.hexdigest()
    if not result:
        raise ValueError("archive has no payload entries")
    return result


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: compare-archive-payloads.sh FIRST.apk|aab SECOND.apk|aab", file=sys.stderr)
        return 1
    try:
        if payload_manifest(argv[0]) != payload_manifest(argv[1]):
            raise ValueError("signed/unsigned archive payloads differ")
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, NotImplementedError) as error:
        print(f"error: archive comparison failed: {error}", file=sys.stderr)
        return 1
    print("Archive entry names and uncompressed payload bytes match (signing metadata excluded).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
