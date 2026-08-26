# Reproducible builds

Bitchat's canonical release build produces byte-for-byte reproducible unsigned
phone and Wear OS APKs and Android App Bundles (AABs). CI builds the release
twice in independent jobs and exposes a verified release artifact only when
every canonical byte matches.

Signing remains local: no keystore or signing password is stored in or exposed
to GitHub Actions. Anyone can reproduce the unsigned artifacts; maintainers
download the verified CI output, sign the selected GitHub APKs and Play upload
AABs locally, then manually publish those exact files.

Maintainers should use the complete
[Android maintainer release guide](maintainer-release-guide.md) for the
step-by-step release procedure, artifact inventory, local signing commands, and
GitHub/Google Play publication checklist.

## What is pinned

- Gradle wrapper version and distribution SHA-256
- dependency versions, strict Gradle dependency locks, and downloaded-artifact
  SHA-256 verification metadata
- exact Temurin JDK release and digest-pinned Linux builder image
- Android platform, Platform Tools, and Build Tools archives by filename and
  SHA-256, plus the accepted SDK license-text SHA-1 required to use them
- Kotlin/JVM toolchain and bytecode target
- Arti source tag and full commit, stable native build epoch, Rust, `cargo-ndk`,
  Android NDK, Cargo lockfile, digest-pinned Rust builder image, and immutable
  Debian package snapshot
- immutable full commit SHAs for every third-party GitHub Action

The build uses a clean source tree, an isolated Gradle user home, UTC, a stable
locale, `SOURCE_DATE_EPOCH` from the Git commit, no Gradle build or configuration
cache, fresh tasks, a non-incremental in-process Kotlin compiler, and R8's
deterministic-debugging mode. The R8 mode uses one compiler thread and disables
randomized input shuffling. The regular R8 ProGuard map remains embedded in each
AAB. Kotlin 2.4.10's optional Compose group-key mapping augmentation is disabled
because its duplicate-key selection depends on unspecified class-file iteration
order across clean builds. Native builds remap source paths and release
validation rejects host paths in packaged libraries. The container overlays a
canonical `local.properties`, so an ignored Android Studio file cannot redirect
Gradle to a host-specific SDK.

AGP's embedded VCS record is disabled because its Git discovery depends on the
host checkout layout. The canonical `BUILDINFO.json` and GitHub provenance
attestation record the host-verified commit instead.

The authoritative pins are:

- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/libs.versions.toml`
- `settings-gradle.lockfile`
- `app/gradle.lockfile`
- `wear/gradle.lockfile`
- `gradle/verification-metadata.xml`
- `tools/reproducible-builds/TOOLCHAIN.env`
- `tools/arti-build/TOOLCHAIN.env`
- `tools/arti-build/Cargo.lock`

## Reproduce a release locally

Requirements are Git, Docker with Linux/amd64 support, more than 8 GiB of memory
available to the Docker VM (16 GiB recommended), and enough free space for the
Android and Gradle images and dependencies. R8's single-threaded deterministic
mode can exceed an 8 GiB Docker memory limit while optimizing the phone app.

```bash
git clone https://github.com/permissionlesstech/bitchat-android.git
cd bitchat-android
git checkout vX.Y.Z
tools/reproducible-builds/build-in-container.sh \
  .reproducible-build/local-vX.Y.Z
```

The output contains:

- unsigned APKs for arm64, armv7, x86, x86_64, and universal installs
- `bitchat-android-release-unsigned.aab`
- `bitchat-android-wear-unsigned.apk`
- `bitchat-android-wear-release-unsigned.aab`
- `BUILDINFO.json`
- `SHA256SUMS.unsigned`

The output directory must not already contain files. The script rejects a dirty
checkout so the commit in `BUILDINFO.json` identifies all source inputs.

To test reproducibility yourself, build into two empty directories and compare:

```bash
BITCHAT_CONTAINER_GRADLE_HOME_NAME=gradle-home-first \
  tools/reproducible-builds/build-in-container.sh .reproducible-build/first
BITCHAT_CONTAINER_GRADLE_HOME_NAME=gradle-home-second \
  tools/reproducible-builds/build-in-container.sh .reproducible-build/second
tools/reproducible-builds/compare-release.sh \
  .reproducible-build/first \
  .reproducible-build/second
```

If a comparison fails and `diffoscope` is installed, the comparison script
automatically reports the first differing artifact.

## Verify a GitHub release

Install the GitHub CLI, authenticate it if necessary, check out the release tag,
and run:

```bash
git checkout vX.Y.Z
tools/reproducible-builds/verify-github-release.sh vX.Y.Z
```

That command:

1. downloads all release APKs, AABs, build information, and checksum files;
2. verifies the canonical unsigned build's GitHub artifact-attestation subjects
   against this repository;
3. verifies `BITCHAT_SHA256SUMS`;
4. checks that the local source commit is the release commit;
5. rebuilds in the pinned container; and
6. byte-compares every unsigned APK, both unsigned AABs, build information, and
   the unsigned checksum manifest.

To verify the published checksums and attestations without rebuilding:

```bash
tools/reproducible-builds/verify-github-release.sh vX.Y.Z --no-rebuild
```

For a manual signature check, use the exact `apksigner` from Android Build Tools
37.0.0:

```bash
apksigner verify --verbose --print-certs bitchat-android-universal.apk
```

Compare the reported signer certificate SHA-256 with
`BITCHAT_GITHUB_RELEASE_CERT_SHA256` in `gradle.properties`. A matching
certificate proves who signed the APK; the checksum, attestation, and local
unsigned rebuild establish which source and build produced it. A third party
cannot recreate the signed bytes without the private release key.

You can also prove that the signed APK contains the same archive entries and
uncompressed payload bytes as the reproduced unsigned APK:

```bash
tools/reproducible-builds/compare-archive-payloads.sh \
  .reproducible-build/local-vX.Y.Z/bitchat-android-universal-unsigned.apk \
  bitchat-android-universal.apk
```

GitHub's manual equivalents are:

```bash
gh release download vX.Y.Z
sha256sum -c BITCHAT_SHA256SUMS
gh attestation verify bitchat-android-universal-unsigned.apk \
  --repo permissionlesstech/bitchat-android
```

## Verify a Google Play release

Google Play App Signing changes the verification boundary:

- maintainers upload signed phone and Wear AABs using the upload key;
- Google Play generates optimized, device-specific APK splits from those AABs;
- Google signs the delivered APKs with the app-signing key.

Consequently, a Play-delivered APK is not expected to be byte-identical to the
GitHub universal APK or to a locally built APK. Use this procedure instead:

1. In Play Console, open **Test and release > App bundle explorer**, select the
   release/version code, and download the original app bundle if that option is
   available to your account. Compare its unsigned payload with the matching
   reproduced phone or Wear AAB:

   ```bash
   tools/reproducible-builds/compare-archive-payloads.sh \
     .reproducible-build/local-vX.Y.Z/bitchat-android-release-unsigned.aab \
     downloaded-from-play.aab
   ```

   The helper excludes JAR-signing metadata and compares every other entry name
   and uncompressed byte.
2. In **Setup > App integrity**, record the SHA-256 fingerprint under **App
   signing key certificate**. This is different from the upload-key certificate
   and may be different from the GitHub release certificate.
3. In App bundle explorer, download the Play-generated universal APK or the APKs
   for a representative device. Verify each APK:

   ```bash
   apksigner verify --verbose --print-certs downloaded-from-play.apk
   ```

   The signer SHA-256 must equal the Play Console app-signing certificate.
4. Confirm package name `com.bitchat.droid`, version code, version name, and
   manifest/security configuration with Android's `apkanalyzer` or `aapt2`.
5. Recreate Google's split-generation behavior from the reproduced AAB with the
   same `bundletool` version and a saved device specification:

   ```bash
   bundletool build-apks \
     --bundle=bitchat-android-release-unsigned.aab \
     --output=local.apks \
     --device-spec=device.json
   ```

This last check validates bundle-to-APK behavior, but it is not a byte-equality
claim: Play's server-side `bundletool` version, optimization, and signing inputs
are controlled by Google. The strongest public Play verification requires
maintainers to retain the uploaded AAB, publish its digest and provenance, and
record the Play version code and app-signing certificate fingerprint alongside
the release.

The GitHub workflow builds and attests both canonical unsigned AABs. A
maintainer locally creates `bitchat-android-play-upload.aab` and
`bitchat-android-wear-play-upload.aab` from those exact files and uploads them
manually to Google Play.

## Maintainer release process

Follow the
[Android maintainer release guide](maintainer-release-guide.md). It is the
authoritative operational runbook from version preparation through the signed
tag, GitHub Actions artifact download, local APK/AAB signing, GitHub draft,
Play internal test, public rollout, and post-release verification.

The workflow has no signing secrets and never publishes a release by itself.

## Updating dependencies or toolchains

Dependency changes must update and review both the lock state and verification
metadata:

```bash
./gradlew testDebugUnitTest lintDebug resolveIdeRuntimeClasspathCopyLocks \
  --write-locks \
  --write-verification-metadata sha256
```

`resolveIdeRuntimeClasspathCopyLocks` records the transient runtime classpath
copies that Android Studio resolves during model import. Their selected versions
are persisted under the generated copy configuration names while the canonical
debug and release runtime classpaths remain strictly locked.

Generate release lock entries in separate invocations because split APK and AAB
intermediates cannot coexist:

```bash
./gradlew :app:clean :app:bundleRelease \
  --write-locks \
  --write-verification-metadata sha256
./gradlew :app:clean :app:assembleRelease \
  --write-locks \
  --write-verification-metadata sha256
```

Review every new repository, component, artifact name, version, and checksum.
Do not accept verification metadata generated after an unexplained checksum
failure.

The verification metadata deliberately trusts only IDE documentation and source
attachments (`*-javadoc.jar`, `*-sources.jar`, and Gradle's `*-src.zip`). Android
Studio resolves these outside the build dependency graph, and they are not build
inputs. Compiled artifacts and dependency metadata remain checksum-verified.

When changing Gradle, update the wrapper and independently verify the new
distribution SHA-256. When changing JDK or Android tools, update the exact
version, archive checksum, and base-image digest together. Native updates follow
[`tools/arti-build/README.md`](../tools/arti-build/README.md).

## References

- [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)
- [Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
- [Gradle wrapper checksum verification](https://docs.gradle.org/current/userguide/best_practices_security.html#use_the_gradle_wrapper_and_verify_the_wrapper_checksum)
- [GitHub Actions security hardening](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub artifact attestation verification](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)
- [Google Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Play Console App bundle explorer](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Android `bundletool`](https://developer.android.com/tools/bundletool)
- [Android `apksigner`](https://developer.android.com/tools/apksigner)
