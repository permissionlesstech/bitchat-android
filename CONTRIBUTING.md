# Contributing to bitchat for Android

Search open and closed issues and pull requests before starting a fix. Link the
issue in your PR, explain the behavior before and after the change, and keep the
patch focused. For larger features or design changes, describe the proposed
scope in an issue before implementing it.

## Local setup and checks

Use JDK 21 and the Android SDK versions pinned in
[gradle/libs.versions.toml](gradle/libs.versions.toml). Configure your SDK through
Android Studio or an ignored `local.properties` file. Do not commit SDK paths,
keystores, credentials, or local configuration.

From the repository root:

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
./gradlew testDebugUnitTest lintDebug
```

For instrumented tests, connect a test device or emulator and run:

```sh
./gradlew connectedAndroidTest
```

Follow the [testing conventions](docs/testing-conventions.md). Add a small,
deterministic regression test for a bug fix. Use synthetic data and controlled
clocks and dispatchers; unit tests must not depend on public relays or real user
state. Report the checks actually run and any limitations in the PR.

## Phone and Wear OS changes

The phone client lives in `app/`; the Wear OS client lives in `wear/`. Shared
mesh and protocol code comes from `app/` through `syncSharedAppSources`. Update
the include list in `wear/build.gradle.kts` when needed; do not edit generated
`wear/build/sharedSrc` files or copy shared code into `wear/src/`.

Use Kotlin conventions, hoist Compose state, and keep blocking work off the
main thread. For UI changes, include before/after screenshots showing the
changed behavior with synthetic content. Review screenshots for private or
identifying information before posting them. For theme or layout changes,
check light and dark themes, accessibility, and the affected screen sizes.

## Mesh and protocol changes

Changes to discovery, routing, transports, Noise/crypto, identity, service
power, messaging, transfers, packets, or fragmentation require physical-device
Mesh Lab validation. An emulator or JVM test cannot establish radio behavior.
Follow the Mesh Lab appendix in the [release gate runbook](docs/release-gate-runbook.md).
Keep raw logs and device evidence local; describe only sanitized results in a
PR. State clearly if physical validation has not been completed.

Keep protocol/security changes fail-closed and compatible with the other
clients. Update the relevant specification and golden-vector tests. For
contract checks, run:

```sh
./gradlew clientRewriteContractTest
```

## Releases and dependencies

Follow [reproducible builds](docs/reproducible-builds.md) when changing build
inputs, dependency locks, or verification metadata. The
[maintainer release guide](docs/maintainer-release-guide.md) describes release
signing and publishing; signing keys and passwords must never be committed.

## Before opening a pull request

- Review the complete diff and remove unrelated changes and local metadata.
- Describe the problem, solution, risks, and validation results.
- Link relevant issues; use a closing keyword only when the PR fully fixes one.
- Include regression tests and UI evidence where applicable.
- Read [LICENSE.md](LICENSE.md) and preserve component license notices.
