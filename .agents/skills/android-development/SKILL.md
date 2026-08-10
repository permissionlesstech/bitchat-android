---
name: android-development
description: Best practices, architecture, build commands, and debugging techniques for Android app development in Kotlin.
---

# Android Development Skill

## Architecture & Guidelines
- Follow modern Android architecture guidelines (UI layer, Domain layer, Data layer).
- State management with Jetpack Compose state hoisting, StateFlow, and ViewModel.
- Asynchronous I/O via Kotlin Coroutines and Flow; never block the main UI thread.
- Handle lifecycle events cleanly and manage resources in Android Services/Foreground Services properly.

## Build & Debugging
- Use `./gradlew :app:assembleDebug` to build the phone app.
- Check `./gradlew lintDebug` for static analysis and linting.
- Inspect logs via `adb logcat` filtered by application process.
