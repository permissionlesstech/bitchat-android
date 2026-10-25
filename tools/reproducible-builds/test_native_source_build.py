#!/usr/bin/env python3
"""Exercise source provenance and native-build rejection paths without compiling."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
NATIVE_SCRIPT = Path("app/src/main/ndr-ffi/build-android.sh")
CONTAINER_SCRIPT = Path("tools/reproducible-builds/build-in-container.sh")
BINDING = Path("app/src/main/java/uniffi/ndr_ffi/ndr_ffi.kt")
SOURCE = Path("vendor/nostr-double-ratchet")
ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")


class NativeSourceBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repository"
        self.bin = Path(self.temporary.name) / "bin"
        self.bin.mkdir()
        self.root.mkdir()
        for relative in (NATIVE_SCRIPT, CONTAINER_SCRIPT,
                         Path("tools/arti-build/TOOLCHAIN.env")):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REPOSITORY / relative, target)
        self.put(SOURCE / "rust/crates/ndr-pairwise-ffi/Cargo.toml", "fixture\n")
        self.put(BINDING, "// generated\n")
        self.put(Path("app/src/main/ndr-ffi/SOURCE_REVISION"), "a" * 40 + "\n")
        self.put(Path("tools/reproducible-builds/container-local.properties"), "sdk.dir=/sdk\n")
        self.ndk = self.root / "ndk"
        self.put(Path("ndk/source.properties"), "Pkg.Revision = 27.3.13750724\n")
        self.tool("rustc", "print('rustc ' + os.getenv('TEST_RUST_VERSION', '1.95.0'))")
        self.tool("cargo-ndk", "pass")
        self.tool("cargo", '''
if sys.argv[1:] == ['--version']:
    print('cargo 1.95.0')
elif sys.argv[1:] == ['ndk', '--version']:
    print('cargo-ndk ' + os.getenv('TEST_CARGO_NDK_VERSION', '4.1.2'))
elif sys.argv[1] == 'ndk':
    output = Path(sys.argv[sys.argv.index('-o') + 1])
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'):
        library = output / abi / 'libndr_ffi.so'
        library.parent.mkdir(parents=True, exist_ok=True)
        library.write_text('source-built fixture')
elif sys.argv[1] == 'run':
    output = Path(sys.argv[sys.argv.index('--out-dir') + 1]) / 'uniffi/ndr_ffi/ndr_ffi.kt'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(os.getenv('TEST_BINDING', '// generated\\n'))
else:
    sys.exit('Unexpected cargo invocation')
''')
        self.tool("llvm-readelf", "print('LOAD ' + os.getenv('TEST_ALIGNMENT', '0x4000'))",
                  self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin")
        self.environment = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                                ANDROID_NDK_HOME=str(self.ndk), ANDROID_HOME="",
                                ANDROID_SDK_ROOT="", NOSTR_DOUBLE_RATCHET_DIR="",
                                BITCHAT_SOURCE_TREE_VERIFIED="1",
                                BITCHAT_NDR_SOURCE_REVISION="a" * 40)

    def put(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def tool(self, name, body, directory=None):
        directory = directory or self.bin
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / name
        path.write_text("#!/usr/bin/env python3\nimport os, sys\nfrom pathlib import Path\n" + body + "\n")
        path.chmod(0o755)

    def native(self, **overrides):
        result = subprocess.run(["bash", str(self.root / NATIVE_SCRIPT), "--check-bindings"],
                                env=dict(self.environment, **overrides), capture_output=True, text=True)
        return result

    def assert_rejected(self, diagnostic, **overrides):
        result = self.native(**overrides)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(diagnostic, result.stderr)
        self.assertFalse((self.root / "app/src/main/jniLibs").exists())

    def test_checked_build_publishes_all_abis(self):
        result = self.native()
        self.assertEqual(result.returncode, 0, result.stderr)
        for abi in ABIS:
            self.assertEqual((self.root / "app/src/main/jniLibs" / abi / "libndr_ffi.so").read_text(),
                             "source-built fixture")

    def test_stale_binding_does_not_publish_libraries_or_rewrite_binding(self):
        self.assert_rejected("binding is stale", TEST_BINDING="// stale\n")
        self.assertEqual((self.root / BINDING).read_text(), "// generated\n")

    def test_unverified_archive_is_rejected(self):
        self.assert_rejected("Unverified NDR source archive", BITCHAT_NDR_SOURCE_REVISION="b" * 40)

    def test_wrong_rust_version_is_rejected(self):
        self.assert_rejected("requires Rust/Cargo", TEST_RUST_VERSION="1.94.0")

    def test_wrong_cargo_ndk_version_is_rejected(self):
        self.assert_rejected("requires Rust/Cargo", TEST_CARGO_NDK_VERSION="4.1.1")

    def test_wrong_ndk_version_is_rejected(self):
        self.put(Path("ndk/source.properties"), "Pkg.Revision = 28.2.13676358\n")
        self.assert_rejected("Android NDK 27.3.13750724 not found")

    def test_non_16k_library_is_rejected(self):
        self.assert_rejected("non-16KiB LOAD alignment", TEST_ALIGNMENT="0x1000")

    def git(self, directory, *args, input=None):
        return subprocess.run(["git", "-C", str(directory), *args], input=input,
                              text=True, capture_output=True, check=True).stdout.strip()

    def record_fixture_tree(self, directory):
        self.git(directory, "init", "-q")
        self.git(directory, "add", ".")
        tree = self.git(directory, "write-tree")
        # Synthetic fixture objects; never change the developer's Git identity.
        commit = self.git(directory, "hash-object", "-w", "-t", "commit", "--stdin", input=(
            f"tree {tree}\n"
            "author Fixture <fixture@example.invalid> 1700000000 +0000\n"
            "committer Fixture <fixture@example.invalid> 1700000000 +0000\n\nFixture\n"))
        self.git(directory, "update-ref", "HEAD", commit)
        return commit

    def prepare_container_fixture(self, mismatch=False):
        self.put(SOURCE / "committed.txt", "committed source\n")
        revision = self.record_fixture_tree(self.root / SOURCE)
        self.put(Path("app/src/main/ndr-ffi/SOURCE_REVISION"),
                 ("b" * 40 if mismatch else revision) + "\n")
        self.put(Path(".gitignore"), ".reproducible-build/\n**/libndr_ffi.so\n**/libjnidispatch.so\n")
        self.record_fixture_tree(self.root)
        self.put(SOURCE / "committed.txt", "uncommitted source\n")
        self.put(Path("app/src/main/jniLibs/arm64-v8a/libndr_ffi.so"), "poisoned host binary")
        self.put(Path("app/src/main/jniLibs/arm64-v8a/libjnidispatch.so"), "poisoned host JNA binary")
        self.calls = Path(self.temporary.name) / "docker-calls"
        self.tool("docker", '''
args = sys.argv[1:]
with open(os.environ['TEST_DOCKER_CALLS'], 'a') as calls:
    calls.write(args[0] + '\\n')
if args[0] == 'run':
    mounts = [args[i + 1] for i, value in enumerate(args) if value == '--volume']
    root = Path(next(mount[:-11] for mount in mounts if mount.endswith(':/workspace')))
    assert (root / 'vendor/nostr-double-ratchet/committed.txt').read_text() == 'committed source\\n'
    native = 'app/src/main/ndr-ffi/build-android.sh' in args
    jna = 'tools/jna-build/build-android.sh' in args
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'):
        library = root / 'app/src/main/jniLibs' / abi / 'libndr_ffi.so'
        if native:
            assert not library.exists(), 'Imported host binary'
            assert '--check-bindings' in args
            assert 'CARGO_HOME=/tmp/ndr-cargo' in args
            assert 'HOME=/tmp/ndr-home' in args
            library.parent.mkdir(parents=True, exist_ok=True)
            library.write_text('container-built fixture')
        elif not jna:
            assert library.read_text() == 'container-built fixture'
        jna_library = library.with_name('libjnidispatch.so')
        if jna:
            assert not library.exists(), 'JNA must build before NDR'
            assert not jna_library.exists(), 'Imported host JNA binary'
            jna_library.parent.mkdir(parents=True, exist_ok=True)
            jna_library.write_text('source-built JNA fixture')
        else:
            assert jna_library.read_text() == 'source-built JNA fixture'
''')

    def container(self):
        return subprocess.run(["bash", str(self.root / CONTAINER_SCRIPT),
                               str(Path(self.temporary.name) / "output")],
                              env=dict(self.environment, BITCHAT_ALLOW_DIRTY="1",
                                       TEST_DOCKER_CALLS=str(self.calls)),
                              capture_output=True, text=True)

    def test_canonical_build_uses_gitlink_source_and_ignores_host_binaries(self):
        self.prepare_container_fixture()
        result = self.container()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls.read_text().splitlines(), ["build", "run", "run", "build", "run"])

    def test_gitlink_mismatch_fails_before_starting_containers(self):
        self.prepare_container_fixture(mismatch=True)
        result = self.container()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match the committed gitlink", result.stderr)
        self.assertFalse(self.calls.exists())


if __name__ == "__main__":
    unittest.main()
