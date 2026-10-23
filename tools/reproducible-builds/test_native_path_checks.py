#!/usr/bin/env python3
"""Check native path rejection with synthetic binaries, without compiling."""

import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


REPOSITORY = Path(__file__).resolve().parents[2]
ARTI_CHECK = Path("tools/arti-build/verify-checksums.sh")
RELEASE_CHECK = Path("tools/reproducible-builds/verify-no-host-paths.sh")


class NativePathChecksTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="native-path-fixture-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for script in (ARTI_CHECK, RELEASE_CHECK):
            target = self.root / script
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / script, target)
        self.library = Path("app/src/main/jniLibs/arm64-v8a/libarti_android.so")
        (self.root / self.library).parent.mkdir(parents=True)
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()

    def check(self, payload, rejected):
        (self.root / self.library).write_bytes(payload)
        (self.root / "tools/arti-build/SHA256SUMS").write_text(
            f"{hashlib.sha256(payload).hexdigest()}  {self.library}\n"
        )
        with zipfile.ZipFile(self.artifacts / "fixture.apk", "w") as archive:
            archive.writestr("lib/arm64-v8a/libfixture.so", payload)
        for script, arguments in ((ARTI_CHECK, []), (RELEASE_CHECK, [str(self.artifacts)])):
            with self.subTest(script=str(script)):
                result = subprocess.run(
                    ["bash", str(self.root / script), *arguments],
                    capture_output=True, text=True, timeout=10
                )
                self.assertEqual(result.returncode, 1 if rejected else 0, result.stderr)
                if rejected:
                    self.assertIn("host-specific path detected", result.stderr)
                self.assertNotIn("private-fixture", result.stdout + result.stderr)

    def assert_path_rejected_at_both_ends(self, path):
        # An early match must not let grep's closed pipe turn a rejection into success.
        padding = b"plain-native-padding\0" * 100_000
        for payload in (path + b"\0" + padding, padding + path + b"\0"):
            with self.subTest(position="early" if payload.startswith(path) else "late"):
                self.check(payload, rejected=True)

    def test_linux_build_paths_are_rejected(self):
        self.assert_path_rejected_at_both_ends(b"/home/private-fixture/source.rs")

    def test_macos_build_paths_are_rejected(self):
        self.assert_path_rejected_at_both_ends(b"/Users/private-fixture/source.rs")

    def test_windows_build_paths_are_rejected(self):
        self.assert_path_rejected_at_both_ends(rb"C:\Users\private-fixture\source.rs")

    def test_escaped_windows_build_paths_are_rejected(self):
        self.assert_path_rejected_at_both_ends(rb"C:\\Users\\private-fixture\\source.rs")

    def test_remapped_native_paths_are_accepted(self):
        self.check(b"/usr/src/project/source.rs\0/usr/local/cargo/registry/source.rs\0", rejected=False)


if __name__ == "__main__":
    unittest.main()
