---
name: android-testing
description: Guidelines for JUnit4, Robolectric, Mockito, Coroutine testing, and multi-device physical mesh lab verification.
---

# Android Testing Skill

## Testing Guidelines
- Run unit tests with `./gradlew testDebugUnitTest`.
- Use JUnit 4, Robolectric, and Kotlin Coroutines Test Dispatchers (`StandardTestDispatcher`, `runTest`).
- Avoid non-deterministic sleeps (`Thread.sleep()`); use coroutine advance/test schedules.
- Mandatory physical device validation using Mesh Lab (`tools/release_gate/mesh_lab.py`) for transport, protocol, or mesh updates.
