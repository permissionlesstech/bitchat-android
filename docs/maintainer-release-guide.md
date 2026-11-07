# Android maintainer release guide

This is the operational runbook for publishing a Bitchat Android release to
GitHub and Google Play. Follow it from top to bottom for every release.

The central rule is:

> GitHub Actions builds and attests the unsigned release. A maintainer signs
> those exact files locally. Nothing is rebuilt after the tag.

No keystore or password is stored in GitHub, GitHub Actions, the repository,
release notes, or workflow artifacts.

This runbook releases both the phone and Wear OS apps under the shared Play
application ID `com.bitchat.droid`. Wear releases use the independent version
code range beginning at `1000000001`; every phone and Wear artifact uploaded to
one Play listing must have a unique version code.

For the technical trust model and third-party verification instructions, see
[Reproducible builds](reproducible-builds.md).

## Release flow

| Stage | Where it happens | Result |
|---|---|---|
| Approve source | Pull requests and the release gate | One reviewed commit on `main` |
| Create tag | Maintainer machine | Signed `vX.Y.Z` tag |
| Build twice | GitHub Actions | Two identical unsigned APK/AAB builds |
| Promote build | GitHub Actions | Attested `verified-unsigned-release` artifact |
| Sign | Maintainer machine | Four installable APKs and two Play upload AABs |
| Test Play build | Play Console internal track | Play-generated APKs tested before public rollout |
| Prepare release | Maintainer machine and GitHub draft | Checksummed public release assets |
| Publish | GitHub Releases and Play Console | Public GitHub release and promoted Play rollout |

## What the signing identities mean

These are four separate signing identities:

1. **Git tag signature**: identifies the maintainer who approved the source
   commit. It is created locally by `git tag -s`.
2. **GitHub APK signature**: lets Android install and update the APKs published
   on GitHub. Use the existing GitHub release key. Its certificate SHA-256 must
   match `BITCHAT_GITHUB_RELEASE_CERT_SHA256` in `gradle.properties`.
3. **Play upload signature**: the maintainer signs both AABs with the Play
   upload key so Play will accept them.
4. **Play app signature**: Google generates device APKs and signs them with the
   separate Play app-signing key.

APK and AAB signatures are embedded in those files. Do not create or publish
detached `.sig` files. GitHub build-provenance attestations are stored by GitHub
and verified with `gh attestation verify`; they are not release asset files.

Never request, export, or use Google's Play app-signing private key during this
process.

## Prerequisites

### Access

The maintainer needs:

- permission to push a release tag and create GitHub Releases;
- a GitHub CLI login authorized for
  `permissionlesstech/bitchat-android`;
- Play Console permission to create and promote releases for
  `com.bitchat.droid`; and
- access to the project's release approval record.

Check the GitHub login:

```bash
gh auth status
```

### Local tools

Install:

- Git;
- [GitHub CLI](https://cli.github.com/);
- the exact JDK in `tools/reproducible-builds/TOOLCHAIN.env`;
- Android SDK Build Tools 37.0.0, including `apksigner` and `zipalign`; and
- Docker with Linux/amd64 support if doing the optional independent rebuild.

Android Studio's SDK Manager can install the required Build Tools version.
Select **SDK Tools**, enable **Show Package Details**, and install 37.0.0.

Load the toolchain pins and check the local tools:

```bash
export JAVA_HOME=/secure/path/to/jdk-21
export ANDROID_SDK_ROOT=/secure/path/to/android-sdk
source tools/reproducible-builds/TOOLCHAIN.env
gh --version
"$JAVA_HOME/bin/java" -version
"$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/apksigner" version
"$ANDROID_SDK_ROOT/build-tools/$ANDROID_BUILD_TOOLS_VERSION/zipalign" -h
```

The JDK output must match `JAVA_VERSION`; the Android path must use
`ANDROID_BUILD_TOOLS_VERSION`.

### Local key material

Keep these in a password manager or encrypted offline storage:

- the existing GitHub APK release keystore, alias, store password, and key
  password;
- the Play upload keystore, alias, store password, and key password; and
- the maintainer's configured Git tag signing key.

The GitHub APK key and Play upload key may be different. Treat them as different
credentials even if the project's historical setup placed them in one
keystore.

Do not put a keystore inside the repository checkout. Do not place passwords on
a command line, in a shell profile, or in a release-notes file.

## 1. Prepare and approve the release commit

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`. For a Wear
   release, also update them in `wear/build.gradle.kts` using the reserved Wear
   version-code range. Every code must be unique across both form factors.
   The phone `versionName` must match the release tag without the leading `v`.
2. Merge all intended release changes into `main`.
3. Confirm required CI checks are green.
4. Complete the
   [physical-device and cross-client release gate](release-gate-runbook.md).
   Keep its privacy-reviewed evidence in the release approval record; do not
   attach raw device identifiers or logs to the public release.
5. Confirm the release notes and user-visible Play changelog are ready.

Start from a clean, current checkout:

```bash
git switch main
git pull --ff-only
git status --short
git log -1 --oneline
```

`git status --short` must print nothing.

Set shell variables for the rest of the release:

```bash
export REPOSITORY=permissionlesstech/bitchat-android
export TAG=vX.Y.Z
export VERSION_CODE=NN
export WEAR_VERSION_CODE=1000000001
export RELEASE_DIR="release-${TAG#v}"
```

Confirm the source version:

```bash
grep -nE 'versionCode|versionName' app/build.gradle.kts
test "$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)" = "${TAG#v}"
test "$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' app/build.gradle.kts)" = "$VERSION_CODE"
test "$(sed -n 's/.*versionCode = \([0-9_][0-9_]*\).*/\1/p' wear/build.gradle.kts | tr -d _)" = "$WEAR_VERSION_CODE"
```

Stop if either `test` command fails.

## 2. Create and push the release tag

Create a signed annotated tag on the approved commit:

```bash
git tag -s "$TAG" -m "Bitchat Android $TAG"
git tag -v "$TAG"
git push origin "$TAG"
```

Do not create the GitHub Release yet. Pushing the tag starts the `Release`
workflow, which checks out that exact tag.

Never move or replace a release tag after pushing it. If source must change,
increment `versionCode`, create a new version, and use a new tag.

## 3. Wait for the reproducible GitHub build

Find the `Release` workflow run:

```bash
gh run list \
  --repo "$REPOSITORY" \
  --workflow release.yml \
  --limit 20
```

Copy the run ID for `$TAG`, then:

```bash
export RUN_ID=123456789
gh run watch "$RUN_ID" --repo "$REPOSITORY" --exit-status
gh run view "$RUN_ID" --repo "$REPOSITORY"
```

The run is successful only when:

- both independent unsigned build jobs pass;
- the byte-for-byte comparison passes;
- provenance attestation succeeds; and
- `verified-unsigned-release` is uploaded.

If the tag already existed and the workflow must be dispatched manually, run
it against the tag ref:

```bash
gh workflow run release.yml \
  --repo "$REPOSITORY" \
  --ref "$TAG" \
  -f tag="$TAG"
```

The workflow rejects a dispatch from a different ref because that would produce
incorrect provenance.

### Accessing the artifacts

With the CLI:

```bash
test ! -e "$RELEASE_DIR"
gh run download "$RUN_ID" \
  --repo "$REPOSITORY" \
  --name verified-unsigned-release \
  --dir "$RELEASE_DIR"
```

In the GitHub web interface:

1. Open **Actions** in the repository.
2. Open the **Release** workflow run for `$TAG`.
3. Scroll to **Artifacts**.
4. Download **verified-unsigned-release**.

Do not sign `unsigned-release-a` or `unsigned-release-b`. Those are the two
replicas retained for diagnostics. Only `verified-unsigned-release` passed the
comparison gate. Workflow artifacts expire after 30 days, so finish the release
before then.

## 4. Verify the downloaded unsigned release

The directory must initially contain exactly these canonical files:

- `BUILDINFO.json`
- `SHA256SUMS.unsigned`
- `bitchat-android-arm64-unsigned.apk`
- `bitchat-android-armv7-unsigned.apk`
- `bitchat-android-universal-unsigned.apk`
- `bitchat-android-x86-unsigned.apk`
- `bitchat-android-x86_64-unsigned.apk`
- `bitchat-android-release-unsigned.aab`
- `bitchat-android-wear-unsigned.apk`
- `bitchat-android-wear-release-unsigned.aab`

Verify the checksum manifest:

```bash
(
  cd "$RELEASE_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c SHA256SUMS.unsigned
  else
    shasum -a 256 -c SHA256SUMS.unsigned
  fi
)
```

Verify that `BUILDINFO.json` identifies the tag commit:

```bash
TAG_COMMIT="$(git rev-list -n 1 "$TAG")"
ARTIFACT_COMMIT="$(
  sed -n 's/.*"sourceCommit": *"\([^"]*\)".*/\1/p' \
    "$RELEASE_DIR/BUILDINFO.json"
)"
test "$TAG_COMMIT" = "$ARTIFACT_COMMIT"
```

Verify GitHub's provenance for every unsigned APK and AAB:

```bash
for artifact in \
  "$RELEASE_DIR"/*-unsigned.apk \
  "$RELEASE_DIR"/*-unsigned.aab
do
  gh attestation verify "$artifact" --repo "$REPOSITORY"
done
```

Stop immediately if a checksum, commit, or attestation check fails.

## 5. Sign the GitHub APKs locally

Set the SDK and GitHub release-key locations. Load passwords from the password
manager without placing their values in shell history:

```bash
export ANDROID_SDK_ROOT=/secure/path/to/android-sdk
export BITCHAT_GITHUB_KEYSTORE=/secure/path/to/github-release.jks
export BITCHAT_GITHUB_KEY_ALIAS=release-key-alias

printf 'GitHub keystore password: '
IFS= read -r -s BITCHAT_GITHUB_KEYSTORE_PASSWORD
printf '\nGitHub key password: '
IFS= read -r -s BITCHAT_GITHUB_KEY_PASSWORD
printf '\n'
export BITCHAT_GITHUB_KEYSTORE_PASSWORD
export BITCHAT_GITHUB_KEY_PASSWORD
```

Run:

```bash
tools/reproducible-builds/sign-release.sh "$RELEASE_DIR"
```

The helper:

- re-verifies `SHA256SUMS.unsigned`;
- uses the pinned Android Build Tools;
- signs each selected APK twice and requires identical results;
- verifies the embedded APK signatures;
- rejects a key whose certificate fingerprint does not match the pinned
  GitHub release certificate; and
- writes `SHA256SUMS`.

It creates:

- `bitchat-android-arm64.apk`
- `bitchat-android-universal.apk`
- `bitchat-android-wear.apk`
- `bitchat-android-x86_64.apk`

The unsigned armv7 and x86 APKs remain available for reproducibility, but are
not published as signed install targets under the current release policy.

If the helper stops after creating any signed file, do not continue or overwrite
files manually. Start again in a new directory downloaded from the same
successful workflow run.

## 6. Sign the Google Play AABs locally

Set the pinned JDK and Play upload-key locations, then load the passwords:

```bash
export JAVA_HOME=/secure/path/to/jdk-21
export BITCHAT_PLAY_UPLOAD_KEYSTORE=/secure/path/to/play-upload.jks
export BITCHAT_PLAY_UPLOAD_KEY_ALIAS=upload-key-alias

printf 'Play upload keystore password: '
IFS= read -r -s BITCHAT_PLAY_KEYSTORE_PASSWORD
printf '\nPlay upload key password: '
IFS= read -r -s BITCHAT_PLAY_KEY_PASSWORD
printf '\n'
export BITCHAT_PLAY_KEYSTORE_PASSWORD
export BITCHAT_PLAY_KEY_PASSWORD
```

Run:

```bash
tools/reproducible-builds/sign-play-bundle.sh "$RELEASE_DIR"
```

The helper creates `bitchat-android-play-upload.aab` and
`bitchat-android-wear-play-upload.aab`, verifies their JAR signatures, proves
that every non-signature payload entry matches the corresponding canonical
unsigned AAB, and updates `SHA256SUMS`.

These are the only files to upload to Play Console:

```text
release-X.Y.Z/bitchat-android-play-upload.aab
release-X.Y.Z/bitchat-android-wear-play-upload.aab
```

Do not upload an APK or either `*-release-unsigned.aab` to Play. Do not open
Android Studio and rebuild the bundles.

Remove passwords from the environment after both signing steps:

```bash
unset BITCHAT_GITHUB_KEYSTORE_PASSWORD
unset BITCHAT_GITHUB_KEY_PASSWORD
unset BITCHAT_PLAY_KEYSTORE_PASSWORD
unset BITCHAT_PLAY_KEY_PASSWORD
```

## 7. Prepare all GitHub Release assets

Run:

```bash
tools/reproducible-builds/prepare-github-release.sh "$RELEASE_DIR"
```

The helper verifies all checksums and renames the public build information and
checksum manifests. It refuses missing or pre-existing release files.

The final GitHub Release must contain all 17 files below:

| Asset | Signed? | Why it is published |
|---|---:|---|
| `bitchat-android-arm64.apk` | APK release key | Primary direct-install APK |
| `bitchat-android-universal.apk` | APK release key | Fallback direct-install APK |
| `bitchat-android-x86_64.apk` | APK release key | x86_64 install APK |
| `bitchat-android-wear.apk` | APK release key | Wear OS direct-install APK |
| Five phone `bitchat-android-*-unsigned.apk` files | No | Reproducibility inputs for every phone ABI target |
| `bitchat-android-release-unsigned.aab` | No | Canonical reproducible Play input |
| `bitchat-android-play-upload.aab` | Play upload key | Exact bundle uploaded to Play |
| `bitchat-android-wear-unsigned.apk` | No | Canonical reproducible Wear install input |
| `bitchat-android-wear-release-unsigned.aab` | No | Canonical reproducible Wear Play input |
| `bitchat-android-wear-play-upload.aab` | Play upload key | Exact bundle uploaded to the Wear OS track |
| `BITCHAT_BUILDINFO.json` | GitHub attestation | Source commit and pinned toolchain |
| `BITCHAT_SHA256SUMS.unsigned` | GitHub attestation | Original canonical CI manifest |
| `BITCHAT_SHA256SUMS` | No detached signature | SHA-256 for every published asset |

Run the public checksum verification once more:

```bash
(
  cd "$RELEASE_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c BITCHAT_SHA256SUMS
  else
    shasum -a 256 -c BITCHAT_SHA256SUMS
  fi
)
```

Do not add keystores, certificate exports, passwords, raw release-gate logs,
local paths, or device/user identifiers to this directory.

## 8. Create release notes and a draft GitHub Release

Create a local release-notes file. At minimum it must contain:

```markdown
## Bitchat Android vX.Y.Z

- Version code: NN
- Wear version: 0.1.0 (code 1000000001)
- Source tag: vX.Y.Z
- GitHub APK signing certificate SHA-256: FINGERPRINT
- Play upload certificate SHA-256: FINGERPRINT
- Play app-signing certificate SHA-256: FINGERPRINT

Checksums, canonical unsigned APK/AAB inputs, the exact Play upload AABs, and
build information are attached. See `docs/reproducible-builds.md` for public
verification instructions.

## Changes

- User-visible change
```

The GitHub APK fingerprint comes from `gradle.properties`. Obtain both Play
certificate fingerprints from **Play Console > Setup > App integrity**. Publish
only the SHA-256 fingerprints, not certificate subject details or private-key
material.

Create a draft release and upload every prepared asset:

```bash
gh release create "$TAG" "$RELEASE_DIR"/* \
  --repo "$REPOSITORY" \
  --verify-tag \
  --draft \
  --title "Bitchat Android $TAG" \
  --notes-file "release-notes-$TAG.md"
```

`--verify-tag` prevents `gh` from silently creating a tag at the wrong commit.
GitHub automatically exposes source archives for the tag; do not upload separate
source ZIP or tar files.

Inspect the draft:

```bash
gh release view "$TAG" --repo "$REPOSITORY" --web
```

Keep it as a draft until the Play internal-track checks below pass.

## 9. Upload and test the AABs in Google Play

1. Open Play Console and select `com.bitchat.droid`.
2. Open **Test and release > Testing > Internal testing**.
3. Create a new release.
4. Upload exactly:
   `release-X.Y.Z/bitchat-android-play-upload.aab`.
5. Confirm Play accepts the upload signature and reports the expected package,
   `versionCode`, and `versionName`.
6. Add the user-visible Play release notes.
7. Resolve blocking Play checks, save the release, and start the internal
   rollout.
8. Install the build through the internal-test opt-in link on a representative
   physical device. Confirm startup, upgrade from the previous public release,
   networking, and the release-critical scenarios.
9. In **App bundle explorer**, select the uploaded version. If the account
   permits downloading the original AAB, download it and verify that its digest
   matches `bitchat-android-play-upload.aab` in `BITCHAT_SHA256SUMS`.
10. In **Setup > App integrity**, confirm the Play app-signing certificate
    SHA-256 is the value recorded in the GitHub release notes.

For the initial Wear release, open **Test and release > Advanced settings >
Form factors**, add Wear OS, upload the required Wear screenshot, and use the
dedicated **Wear OS only** test track. Upload exactly
`bitchat-android-wear-play-upload.aab`, confirm version code `1000000001`, and
complete the Wear OS opt-in and review flow. Promote the already-tested Wear
artifact on its dedicated track; do not add it to the mobile track.

Promote these same tested Play releases from their test tracks to their
respective production tracks. Do not rebuild or upload replacement AABs for
production. Use staged production rollouts when appropriate.

Google signs the device APKs with the Play app-signing key, so Play-delivered
APKs will not be byte-identical to the GitHub APKs. That is expected.

## 10. Publish GitHub and promote Play

After both Play test builds pass and the GitHub draft has all 17 assets:

```bash
gh release edit "$TAG" \
  --repo "$REPOSITORY" \
  --draft=false \
  --latest
```

Then complete or schedule the production promotion in Play Console. If managed
publishing is enabled, send the approved changes for review and publish them at
the coordinated release time.

Do not replace assets after the GitHub Release is public. If any published
binary is wrong, create a new version and release.

## 11. Verify the public release

From a clean checkout of the tag:

```bash
git checkout "$TAG"
tools/reproducible-builds/verify-github-release.sh "$TAG" --no-rebuild
```

For the strongest check, omit `--no-rebuild` and let the pinned container
rebuild and compare all unsigned artifacts:

```bash
tools/reproducible-builds/verify-github-release.sh "$TAG"
```

Also verify:

- the GitHub release is marked **Latest**;
- the four signed APKs install and show the expected version;
- the Play listing shows the intended production version and rollout state;
- a Play-installed build is signed by the app-signing certificate recorded in
  the release notes; and
- the final GitHub URL and Play status are added to the internal release
  approval record.

## Failure and retry rules

- **A GitHub build or comparison fails:** do not sign anything. Fix the source,
  increment the version, and create a new tag. Rerun the same tag only for an
  infrastructure-only failure that did not change source.
- **A checksum, provenance, or commit check fails:** stop. Do not publish.
- **A local signing helper fails:** use a fresh download directory. Do not
  overwrite partially created signed artifacts.
- **Play rejects the upload key:** stop and resolve the registered upload key in
  Play Console. Never substitute the app-signing key.
- **Play has accepted the version code but the binary must change:** increment
  `versionCode` and create a new release.
- **A GitHub draft is wrong:** keep it private while correcting it from a fresh,
  verified artifact directory.
- **A public release is wrong:** do not move the tag or replace assets. Publish
  a new version.

## Final checklist

- [ ] Version name and version code updated and approved
- [ ] Required CI and physical-device release gate passed
- [ ] Signed tag pushed from the approved commit
- [ ] `Release` workflow and two-build comparison passed
- [ ] `verified-unsigned-release` downloaded by run ID
- [ ] Checksums, source commit, and attestations verified
- [ ] GitHub APKs signed locally with the pinned certificate
- [ ] Phone and Wear Play AABs signed locally with the registered upload key
- [ ] `BITCHAT_SHA256SUMS` verifies all 17 release assets
- [ ] GitHub draft created with certificate fingerprints and all assets
- [ ] Exact signed phone and Wear AABs uploaded to and tested on their tracks
- [ ] GitHub Release published
- [ ] Same tested Play release promoted to production
- [ ] Public GitHub and Play verification completed

## Official references

- [GitHub CLI: download workflow artifacts](https://cli.github.com/manual/gh_run_download)
- [GitHub CLI: create a release](https://cli.github.com/manual/gh_release_create)
- [Google Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
- [Play Console App bundle explorer](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Package and distribute Wear OS apps](https://developer.android.com/training/wearables/packaging)
- [Manage form-factor releases on dedicated tracks](https://support.google.com/googleplay/android-developer/answer/13295490)
