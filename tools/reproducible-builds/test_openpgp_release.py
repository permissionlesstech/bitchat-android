"""Offline release verification tests; keys and artifacts are synthetic and temporary."""

import hashlib
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("openpgp_release", Path(__file__).with_name("openpgp-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


@unittest.skipUnless(shutil.which("gpg"), "GnuPG is required")
class OpenPGPReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key_directory = tempfile.TemporaryDirectory()
        home = Path(cls.key_directory.name)
        home.chmod(0o700)
        cls.environment = patch.dict(os.environ, {"GNUPGHOME": str(home)})
        cls.environment.start()
        subprocess.run(["gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
                        "--quick-generate-key", "Synthetic Release Test <release@example.invalid>",
                        "ed25519", "sign", "0"], check=True, capture_output=True)
        listing = release.gpg("--with-colons", "--list-keys").decode()
        cls.pin = next(line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:"))

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["gpgconf", "--kill", "gpg-agent"], check=False, capture_output=True)
        cls.environment.stop()
        cls.key_directory.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.asset = self.directory / "synthetic.apk"
        self.asset.write_bytes(b"synthetic artifact, not an installable APK")
        self.manifest = self.directory / release.MANIFEST
        self.entry = hashlib.sha256(self.asset.read_bytes()).hexdigest() + "  synthetic.apk\n"
        self.manifest.write_text(self.entry)

    def sign(self):
        release.sign(self.directory, self.pin)

    def test_valid_release_and_lowercase_pin(self):
        self.sign()
        release.verify(self.directory, release.fingerprint(self.pin.lower()))

    def test_wrong_pin_is_rejected(self):
        self.sign()
        with self.assertRaisesRegex(ValueError, "pinned primary key"):
            release.verify(self.directory, "0" * 40)

    def test_altered_asset_is_rejected(self):
        self.sign()
        self.asset.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            release.verify(self.directory, self.pin)

    def test_altered_manifest_and_matching_asset_are_rejected(self):
        self.sign()
        self.asset.write_bytes(b"tampered")
        self.manifest.write_text(hashlib.sha256(self.asset.read_bytes()).hexdigest() + "  synthetic.apk\n")
        with self.assertRaisesRegex(ValueError, "OpenPGP operation failed"):
            release.verify(self.directory, self.pin)

    def test_extra_asset_is_rejected(self):
        self.sign()
        (self.directory / "extra.apk").write_bytes(b"extra")
        with self.assertRaisesRegex(ValueError, "unlisted"):
            release.verify(self.directory, self.pin)

    def test_existing_metadata_is_not_overwritten(self):
        self.sign()
        original = (self.directory / release.SIGNATURE).read_bytes()
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.sign()
        self.assertEqual(original, (self.directory / release.SIGNATURE).read_bytes())

    def test_invalid_manifest_is_rejected_before_signing(self):
        for text in ("", self.entry * 2, self.entry.replace("synthetic.apk", "../synthetic.apk")):
            self.manifest.write_text(text)
            with self.assertRaises(ValueError):
                self.sign()
            self.assertFalse((self.directory / release.SIGNATURE).exists())

    def test_symlink_asset_is_rejected(self):
        target = self.directory / "target"
        self.asset.rename(target)
        self.asset.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "regular release asset"):
            self.sign()

    def test_symlink_metadata_is_rejected(self):
        self.sign()
        public_key = self.directory / release.PUBLIC_KEY
        target = self.directory / "key-target"
        public_key.rename(target)
        public_key.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "regular OpenPGP metadata"):
            release.verify(self.directory, self.pin)

    def test_short_key_id_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "complete primary-key fingerprint"):
            release.fingerprint(self.pin[-16:])


if __name__ == "__main__":
    unittest.main()
