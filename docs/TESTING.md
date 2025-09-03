# Testing and CI/CD Documentation

## Overview

This project includes a comprehensive testing and CI/CD setup following Android development best practices.

## Test Structure

### Unit Tests
- **Location**: `app/src/test/kotlin/`
- **Current Tests**:
  - `ColorTest.kt` - Tests username color generation consistency
  - `MainViewModelTest.kt` - Tests main view model functionality
  - `PeerManagerTest.kt` - Comprehensive tests for peer management

### Test Execution

#### Local Testing
```bash
# Run all unit tests
./gradlew test

# Run only debug unit tests
./gradlew testDebugUnitTest

# Run tests with coverage (if configured)
./gradlew testDebugUnitTestCoverage

# Run lint checks
./gradlew lintDebug

# Run all checks (tests + lint)
./gradlew check
```

#### IDE Integration
- Tests can be run directly from Android Studio
- Right-click on test files or methods to run individual tests
- Use the "Run All Tests" option to execute the complete test suite

## CI/CD Pipeline

### GitHub Actions Workflows

#### 1. Android CI (`android-build.yml`)
**Triggers**: Push/PR to main/develop branches

**Jobs**:
- **Test Job**: Runs unit tests and generates reports
- **Lint Job**: Runs Android lint checks
- **Build Job**: Builds debug and release APKs (depends on test + lint)

**Features**:
- Gradle caching for faster builds
- Test result reporting with `dorny/test-reporter`
- Artifact uploads for test results and APKs
- Parallel job execution for better performance

#### 2. Instrumented Tests (`android-instrumented-tests.yml`)
**Triggers**: Push/PR to main/develop branches, manual dispatch

**Features**:
- Tests on multiple Android API levels (26, 29, 33)
- Uses Android emulator for realistic testing
- Matrix strategy for comprehensive device coverage

#### 3. Quality Gate (`quality-gate.yml`)
**Triggers**: Push/PR to main/develop branches

**Purpose**: Single comprehensive check that can be used as a required status check

**Features**:
- Runs all checks (test + lint + build)
- Generates coverage reports (if configured)
- Comments test results on PRs
- Uploads comprehensive reports

### Best Practices Implemented

1. **Caching**: Gradle wrapper and packages are cached to speed up builds
2. **Parallel Jobs**: Tests, lint, and build run in parallel when possible
3. **Artifact Preservation**: Test results, lint reports, and APKs are saved
4. **Multiple API Testing**: Instrumented tests run on different Android versions
5. **Fail-Fast**: Build job only runs if tests and lint pass
6. **Comprehensive Reporting**: Test results are commented on PRs

### Setting Up Branch Protection

To enforce quality gates, configure branch protection rules:

1. Go to Settings → Branches in your GitHub repository
2. Add a rule for `main` and `develop` branches
3. Enable "Require status checks to pass before merging"
4. Select the following required checks:
   - `test`
   - `lint`  
   - `build`
   - Or use `quality-gate` as a single comprehensive check

### Adding More Tests

#### Unit Tests
1. Create new test files in `app/src/test/kotlin/com/bitchat/`
2. Follow the existing naming convention (`*Test.kt`)
3. Use JUnit 4 annotations (`@Test`, `@Before`, `@After`)

#### Instrumented Tests
1. Create test files in `app/src/androidTest/kotlin/com/bitchat/`
2. Use `@RunWith(AndroidJUnit4::class)` for Android-specific tests
3. Add Espresso dependencies for UI testing

### Code Coverage

To enable code coverage:

1. Add JaCoCo plugin to `build.gradle.kts`:
```kotlin
plugins {
    // ... existing plugins
    jacoco
}

android {
    // ... existing config
    buildTypes {
        debug {
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
        }
    }
}
```

2. Run coverage reports:
```bash
./gradlew testDebugUnitTestCoverage
```

### Performance Optimization

The CI pipeline is optimized for speed:
- **Gradle caching** reduces build times by ~50%
- **Parallel jobs** run simultaneously when dependencies allow
- **Incremental builds** only rebuild changed components
- **KVM acceleration** for faster emulator performance

### Monitoring and Alerts

- GitHub Actions will email on build failures
- PR comments show test results directly
- Artifacts are preserved for 90 days for debugging
- Status badges can be added to README.md

## Troubleshooting

### Common Issues

1. **Gradle Permission Denied**
   ```bash
   chmod +x gradlew
   ```

2. **Tests Fail Locally But Pass in CI**
   - Check for file system case sensitivity
   - Verify Android SDK versions match

3. **Slow CI Builds**
   - Gradle cache may be cold (first run after cache reset)
   - Check for concurrent builds exhausting runner resources

### Debug Commands

```bash
# Run tests with full output
./gradlew testDebugUnitTest --info

# Run specific test class
./gradlew testDebugUnitTest --tests "com.bitchat.PeerManagerTest"

# Clean and rebuild
./gradlew clean testDebugUnitTest
```
