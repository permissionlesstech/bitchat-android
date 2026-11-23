# Contributing to BitChat Android

Thanks for your interest in contributing to BitChat for Android!

## Getting Started

1. Fork `permissionlesstech/bitchat-android` and clone your fork.
2. Create a topic branch from `main`:
   ```bash
   git checkout -b fix/short-description
   ```
3. Build and run JVM unit tests:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
   For Wear OS changes:
   ```bash
   ./gradlew :wear:testDebugUnitTest
   ```

## Pull Request Guidelines

- Keep PRs focused on a single bug fix or feature.
- Include unit tests for logic changes and regression fixes.
- For UI changes, attach before/after screenshots.
- Follow existing Kotlin style and keep protocol/wire changes compatible with the iOS client (`permissionlesstech/bitchat`).
- Reference any related issue in the PR description (e.g. `Fixes #123`).

## License

Contributions are licensed under the GNU General Public License v3.0 (GPL-3.0). See [LICENSE.md](LICENSE.md) for details.
