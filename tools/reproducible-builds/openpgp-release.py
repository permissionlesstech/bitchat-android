#!/usr/bin/env python3
"""Sign or verify the public checksum manifest with an explicitly pinned OpenPGP key."""

import argparse
import hashlib
import re
import subprocess
import tempfile
from pathlib import Path

MANIFEST = "BITCHAT_SHA256SUMS"
SIGNATURE = MANIFEST + ".asc"
PUBLIC_KEY = "BITCHAT_RELEASE_KEY.asc"


def fingerprint(value):
    value = value.upper()
    if not re.fullmatch(r"[0-9A-F]{40}|[0-9A-F]{64}", value):
        raise ValueError("a complete primary-key fingerprint is required")
    return value


def check_assets(directory):
    manifest = directory / MANIFEST
    if manifest.is_symlink() or not manifest.is_file():
        raise ValueError("missing regular checksum manifest")
    names = set()
    for line in manifest.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9_.-]*)", line)
        if not match:
            raise ValueError("invalid checksum entry")
        digest, name = match.groups()
        if name in names or name in {MANIFEST, SIGNATURE, PUBLIC_KEY}:
            raise ValueError("duplicate or reserved checksum entry")
        names.add(name)
        asset = directory / name
        if asset.is_symlink() or not asset.is_file():
            raise ValueError("missing regular release asset")
        hasher = hashlib.sha256()
        with asset.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                hasher.update(block)
        if hasher.hexdigest() != digest:
            raise ValueError("release asset checksum mismatch")
    if not names:
        raise ValueError("empty checksum manifest")
    actual = {item.name for item in directory.iterdir()}
    if actual - {MANIFEST, SIGNATURE, PUBLIC_KEY} != names:
        raise ValueError("release directory contains unlisted or missing assets")


def gpg(*args):
    result = subprocess.run(["gpg", "--batch", *map(str, args)], capture_output=True)
    if result.returncode:
        # GnuPG diagnostics may include private keyring paths or account identities.
        raise ValueError("OpenPGP operation failed")
    return result.stdout


def verify(directory, expected):
    for name in (SIGNATURE, PUBLIC_KEY):
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("missing regular OpenPGP metadata")
    verify_signature(directory, expected)
    check_assets(directory)


def verify_signature(directory, expected):
    # Never trust a key downloaded with the artifact without an independent pin.
    # Use a fresh keyring; neither the user's trust database nor keyservers are used.
    with tempfile.TemporaryDirectory(prefix="bitchat-gpg-") as home:
        Path(home).chmod(0o700)
        gpg("--homedir", home, "--import", directory / PUBLIC_KEY)
        status = gpg("--homedir", home, "--no-auto-key-retrieve", "--status-fd", "1",
                     "--verify", directory / SIGNATURE, directory / MANIFEST).decode()
        valid = [line.split() for line in status.splitlines()
                 if line.startswith("[GNUPG:] VALIDSIG ")]
        # VALIDSIG's last field is the primary fingerprint, including for subkeys.
        if len(valid) != 1 or valid[0][-1] != expected:
            raise ValueError("signature does not match the pinned primary key")
        if valid[0][9] not in {"8", "9", "10"}:  # SHA-256, SHA-384, SHA-512
            raise ValueError("checksum signature uses an unsupported digest")
        if any(line.startswith("[GNUPG:] " + kind) for line in status.splitlines()
               for kind in ("EXPKEYSIG", "EXPSIG", "REVKEYSIG")):
            raise ValueError("expired or revoked OpenPGP signature")


def sign(directory, expected):
    check_assets(directory)
    if any((directory / name).exists() or (directory / name).is_symlink()
           for name in (SIGNATURE, PUBLIC_KEY)):
        raise ValueError("OpenPGP metadata already exists")
    # Stage both files before publishing either. A signing error leaves no output.
    with tempfile.TemporaryDirectory(prefix="bitchat-sign-") as staging:
        signature = Path(staging) / SIGNATURE
        public_key = Path(staging) / PUBLIC_KEY
        public_key.write_bytes(gpg("--armor", "--export-options", "export-minimal",
                                   "--export", expected))
        if not public_key.stat().st_size:
            raise ValueError("public key not found")
        gpg("--armor", "--digest-algo", "SHA256", "--local-user", expected, "--output", signature,
            "--detach-sign", directory / MANIFEST)
        (Path(staging) / MANIFEST).write_bytes((directory / MANIFEST).read_bytes())
        verify_signature(Path(staging), expected)
        (directory / SIGNATURE).write_bytes(signature.read_bytes())
        (directory / PUBLIC_KEY).write_bytes(public_key.read_bytes())
    verify(directory, expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("sign", "verify"))
    parser.add_argument("release_directory", type=Path)
    parser.add_argument("primary_fingerprint", help="pin obtained through a trusted channel")
    args = parser.parse_args()
    try:
        expected = fingerprint(args.primary_fingerprint)
        directory = args.release_directory.resolve(strict=True)
        if not directory.is_dir():
            raise ValueError("release directory is required")
        (sign if args.operation == "sign" else verify)(directory, expected)
    except (ValueError, OSError, UnicodeError) as error:
        parser.exit(1, f"error: {error if isinstance(error, ValueError) else 'release files unavailable'}\n")
    print("OpenPGP signature and release checksums verified.")


if __name__ == "__main__":
    main()
