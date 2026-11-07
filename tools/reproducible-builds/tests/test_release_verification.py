import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

TOOLS = Path(__file__).resolve().parents[1]


class ReleaseVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.tools = self.repo / "tools/reproducible-builds"
        shutil.copytree(TOOLS, self.tools, ignore=shutil.ignore_patterns("__pycache__", "tests"))
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        FIXTURE_ASSETS=str(self.assets), FIXTURE_CERT="a" * 64)
        (self.repo / "gradle.properties").write_text("BITCHAT_GITHUB_RELEASE_CERT_SHA256=" + "a" * 64 + "\n")
        self.env["ANDROID_SDK_ROOT"] = str(self.root / "sdk")
        version = next(line.split("=", 1)[1].strip('"') for line in
                       (self.tools / "TOOLCHAIN.env").read_text().splitlines()
                       if line.startswith("ANDROID_BUILD_TOOLS_VERSION="))
        signer = self.root / "sdk/build-tools" / version / "apksigner"
        signer.parent.mkdir(parents=True)
        self.executable(signer, '''#!/usr/bin/env python3
import os, sys
if os.environ.get("INVALID_SIGNATURE"):
    sys.exit(1)
print("Signer #1 certificate SHA-256 digest: " + os.environ["FIXTURE_CERT"])
''')
        self.executable(self.bin / "gh", '''#!/usr/bin/env python3
import os, pathlib, shutil, sys
args = sys.argv[1:]
if args[:2] == ["release", "download"]:
    dest = pathlib.Path(args[args.index("--dir") + 1])
    for path in pathlib.Path(os.environ["FIXTURE_ASSETS"]).iterdir():
        shutil.copy(path, dest / path.name)
elif args[:2] == ["attestation", "verify"]:
    required = ["--signer-workflow", "permissionlesstech/bitchat-android/.github/workflows/release.yml",
                "--source-ref", "refs/tags/v1.2.3", "--deny-self-hosted-runners"]
    if os.environ.get("UNTRUSTED_ATTESTATION") and all(item in args for item in required):
        sys.exit(1)
else:
    sys.exit(2)
''')
        for name in ["arm64", "armv7", "universal", "wear", "x86", "x86_64"]:
            self.archive(self.assets / f"bitchat-android-{name}-unsigned.apk", [("classes.dex", b"fixture")])
        for name in ["arm64", "universal", "wear", "x86_64"]:
            shutil.copy(self.assets / f"bitchat-android-{name}-unsigned.apk",
                        self.assets / f"bitchat-android-{name}.apk")
        for prefix in ["bitchat-android", "bitchat-android-wear"]:
            self.archive(self.assets / f"{prefix}-release-unsigned.aab", [("base/dex/classes.dex", b"fixture")])
            shutil.copy(self.assets / f"{prefix}-release-unsigned.aab", self.assets / f"{prefix}-play-upload.aab")
        (self.assets / "BITCHAT_BUILDINFO.json").write_text('{"sourceCommit": "synthetic"}\n')
        (self.assets / "BITCHAT_SHA256SUMS.unsigned").write_text("synthetic attested manifest\n")
        self.checksums()

    @staticmethod
    def executable(path, text):
        path.write_text(text)
        path.chmod(0o755)

    @staticmethod
    def archive(path, entries):
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in entries:
                archive.writestr(name, data)

    def checksums(self, omit=None):
        lines = []
        for path in sorted(self.assets.iterdir()):
            if path.name not in {"BITCHAT_SHA256SUMS", omit}:
                lines.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name)
        (self.assets / "BITCHAT_SHA256SUMS").write_text("\n".join(lines) + "\n")

    def verify(self):
        return subprocess.run([str(self.tools / "verify-github-release.sh"), "v1.2.3", "--no-rebuild"],
                              env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def compare(self, first, second):
        return subprocess.run([str(self.tools / "compare-archive-payloads.sh"), str(first), str(second)],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def test_complete_release_passes(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stdout.decode())

    def test_invalid_archives_fail_closed(self):
        a, b = self.root / "a.apk", self.root / "b.apk"
        a.write_bytes(b"not a ZIP")
        b.write_bytes(b"not a ZIP")
        self.assertNotEqual(self.compare(a, b).returncode, 0)
        self.assertNotEqual(self.compare(a, self.root / "missing.apk").returncode, 0)

    def test_literal_zip_names_compare_without_glob_or_line_interpretation(self):
        a, b = self.root / "a.apk", self.root / "b.apk"
        entries = [("assets/[x]", b"brackets"), ("assets/line\nbreak", b"newline")]
        self.archive(a, entries)
        self.archive(b, entries)
        result = self.compare(a, b)
        self.assertEqual(result.returncode, 0, result.stdout.decode())

    def test_nested_signature_like_files_are_payload(self):
        a, b = self.root / "a.apk", self.root / "b.apk"
        self.archive(a, [("classes.dex", b"same"), ("META-INF/data/payload.RSA", b"first")])
        self.archive(b, [("classes.dex", b"same"), ("META-INF/data/payload.RSA", b"changed")])
        self.assertNotEqual(self.compare(a, b).returncode, 0)

    def test_actual_jar_signing_metadata_is_excluded(self):
        a, b = self.root / "a.apk", self.root / "b.apk"
        self.archive(a, [("classes.dex", b"same")])
        self.archive(b, [("classes.dex", b"same"), ("META-INF/CERT.RSA", b"signature"),
                         ("META-INF/CERT.SF", b"digest"), ("META-INF/MANIFEST.MF", b"manifest")])
        self.assertEqual(self.compare(a, b).returncode, 0)

    def test_duplicate_zip_names_are_rejected(self):
        import warnings
        a = self.root / "a.apk"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            self.archive(a, [("classes.dex", b"first"), ("classes.dex", b"second")])
        self.assertNotEqual(self.compare(a, a).returncode, 0)

    def test_checksum_inventory_cannot_omit_signed_assets(self):
        self.checksums(omit="bitchat-android-universal.apk")
        self.assertNotEqual(self.verify().returncode, 0)

    def test_checksum_inventory_rejects_duplicate_and_traversing_paths(self):
        manifest = self.assets / "BITCHAT_SHA256SUMS"
        original = manifest.read_text()
        for extra in [original.splitlines()[0], "0" * 64 + "  ../outside"]:
            manifest.write_text(original + extra + "\n")
            self.assertNotEqual(self.verify().returncode, 0)

    def test_missing_signed_asset_is_rejected(self):
        (self.assets / "bitchat-android-universal.apk").unlink()
        self.checksums()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_wrong_apk_signer_is_rejected(self):
        self.env["FIXTURE_CERT"] = "b" * 64
        self.assertNotEqual(self.verify().returncode, 0)

    def test_invalid_apk_signature_is_rejected(self):
        self.env["INVALID_SIGNATURE"] = "1"
        self.assertNotEqual(self.verify().returncode, 0)

    def test_signed_apk_must_match_attested_payload(self):
        self.archive(self.assets / "bitchat-android-universal.apk", [("classes.dex", b"modified")])
        self.checksums()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_signed_aab_must_match_attested_payload(self):
        self.archive(self.assets / "bitchat-android-play-upload.aab", [("base/dex/classes.dex", b"modified")])
        self.checksums()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_other_workflow_or_ref_attestation_is_rejected(self):
        self.env["UNTRUSTED_ATTESTATION"] = "1"
        self.assertNotEqual(self.verify().returncode, 0)


if __name__ == "__main__":
    unittest.main()
