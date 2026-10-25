#!/usr/bin/env python3
"""Exercise final APK/AAB admission, including missing and duplicate JNI inputs."""

from pathlib import Path
import subprocess
import tempfile
import unittest
import warnings
import zipfile


SCRIPT = Path(__file__).with_name("verify-packaging.sh")
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


class JnaPackagingTest(unittest.TestCase):
    def check_archive(self, filename, entries, expected):
        with tempfile.TemporaryDirectory() as directory:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(Path(directory) / filename, "w") as archive:
                    for entry in entries:
                        archive.writestr(entry, "synthetic library")
            result = subprocess.run(["bash", str(SCRIPT), directory], capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, expected, result.stdout + result.stderr)

    def test_accepts_phone_apk_and_bundle_and_abi_splits(self):
        for suffix, prefix in (("apk", "lib"), ("aab", "base/lib")):
            with self.subTest(suffix=suffix):
                self.check_archive(f"bitchat-android-release.{suffix}",
                                   [f"{prefix}/{abi}/libjnidispatch.so" for abi in ABIS], True)
        for label, abi in (("arm64", "arm64-v8a"), ("armv7", "armeabi-v7a"),
                           ("x86_64", "x86_64"), ("x86", "x86")):
            self.check_archive(f"bitchat-android-{label}-unsigned.apk",
                               [f"lib/{abi}/libjnidispatch.so"], True)

    def test_rejects_missing_or_duplicate_native_library(self):
        entries = [f"lib/{abi}/libjnidispatch.so" for abi in ABIS]
        self.check_archive("app-universal-debug.apk", entries[:-1], False)
        self.check_archive("app-universal-debug.apk", entries + entries[:1], False)
        self.check_archive("app-universal-debug.apk", entries[:-1] +
                           ["lib/mips/libjnidispatch.so"], False)

    def test_rejects_desktop_native_resources(self):
        entries = [f"lib/{abi}/libjnidispatch.so" for abi in ABIS]
        self.check_archive("app-universal-debug.apk", entries +
                           ["com/sun/jna/linux-x86-64/libjnidispatch.so"], False)

    def test_wear_contains_no_jna(self):
        self.check_archive("bitchat-android-wear-unsigned.apk", ["classes.dex"], True)
        self.check_archive("bitchat-android-wear-unsigned.apk",
                           ["lib/arm64-v8a/libjnidispatch.so"], False)


if __name__ == "__main__":
    unittest.main()
