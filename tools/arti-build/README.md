# Rebuilding Arti for Android

The four committed `libarti_android.so` files are built from the official Arti
source plus the JNI wrapper in this directory. The supported build path is the
pinned Linux container:

```bash
tools/arti-build/rebuild-in-container.sh
tools/arti-build/verify-checksums.sh
```

The container builds all four Android ABIs, verifies the exported JNI symbols
and rejects native binaries containing host-specific paths. It then rewrites
`tools/arti-build/SHA256SUMS`; both that manifest and the rebuilt libraries are
release inputs.

## Pinned inputs

- `TOOLCHAIN.env`: Arti tag and commit, stable build epoch, Rust, `cargo-ndk`,
  Android NDK, and immutable Debian snapshot
- `rust-toolchain.toml`: Rust release and Android targets
- `Cargo.lock`: complete Rust dependency graph
- `Dockerfile`: digest-pinned Rust base image and checksum-verified NDK archive
- `Cargo.toml` and `src/lib.rs`: JNI wrapper package and source

Rust source paths are remapped to stable virtual paths, incremental compilation
is disabled, and `SOURCE_DATE_EPOCH` is pinned to the value used for the
committed libraries.

## Updating Arti or the native toolchain

1. Update the tag, full source commit, stable build epoch, tool versions,
   archive name, and archive checksum in `TOOLCHAIN.env`.
2. Update `Cargo.toml` if Arti changed its crate features or wrapper version.
3. Regenerate `Cargo.lock` against the exact Arti checkout and review every
   dependency change:

   ```bash
   tools/arti-build/rebuild-in-container.sh --update-lockfile
   ```

4. Update the digest-pinned base image and `rust-toolchain.toml` when changing
   Rust.
5. Run `tools/arti-build/rebuild-in-container.sh` twice from clean generated
   state and confirm `tools/arti-build/SHA256SUMS` is unchanged.
6. Run the Android tests and the two-build release comparison described in
   [`docs/reproducible-builds.md`](../../docs/reproducible-builds.md).

Do not build release native libraries with an unpinned local NDK or Rust
toolchain. `build-arti.sh` deliberately rejects mismatched tool versions.

## JNI surface

The wrapper exports the native methods used by `ArtiNative`:

- `getVersion`
- `setLogCallback`
- `initialize`
- `startSocksProxy`
- `stop`

The build fails if any expected symbol is absent. The NDK `readelf` check also
reports ELF load-segment alignment for Android's 16 KiB page-size requirement.

## References

- [Arti source](https://gitlab.torproject.org/tpo/core/arti)
- [Cargo lockfiles](https://doc.rust-lang.org/cargo/faq.html#why-have-cargolock-in-version-control)
- [Rust source-path remapping](https://doc.rust-lang.org/rustc/remap-source-paths.html)
- [Install a specific Android NDK](https://developer.android.com/studio/projects/install-ndk)
