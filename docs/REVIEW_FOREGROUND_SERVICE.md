**Summary**

I reviewed the background/foreground service branch against `main` focusing on mesh, relay, DMs, handshake and lifecycle. Overall the architecture moves the mesh into a persistent Foreground Service with a notification and centralizes a single `BluetoothMeshService` via a process‑wide holder. The BLE layer and power manager refactors look solid.

I found several lifecycle and policy gaps that can break behavior when the app is in the background or is killed, and a couple of leaks/over‑engineering hotspots. Below are concrete issues and recommendations with file references.

**Key Risks and Issues**

- Foreground toggle doesn’t control mesh start/stop
  - `MeshForegroundService.ensureMeshStarted()` starts the mesh regardless of the “background enabled” preference or notification permission.
  - Impact: Mesh can run without a visible foreground notification (policy risk) or continue running after the user disables background, causing hidden BLE activity.
  - Where:
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:195` (ensureMeshStarted only checks BT perms)
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:176` (loop updates notif but never stops mesh when disabled/perms missing)

- ACTION_STOP doesn’t stop the mesh
  - The STOP action stops only the foreground service and not the BLE mesh.
  - Impact: BLE continues to scan/advertise without a notification, violating FGS expectations and confusing users.
  - Where: `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:133` (STOP path does not call `meshService.stopServices()`)

- Service destroy doesn’t clean up the mesh
  - `onDestroy()` cancels a job but does not stop the mesh or clear the holder.
  - Impact: Potentially leaves the mesh running if the FGS is torn down (e.g., the system stops the service), and can lead to a stale singleton.
  - Where: `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:234`

- Possible foreground service policy violation window
  - In the periodic loop, if background is disabled or POST_NOTIFICATIONS is missing, the code stops the foreground state and cancels the notification, but it does not stop the mesh.
  - Impact: Mesh continues running in background with no notification. This is high risk for Play policy and Android OS behavior (more aggressive kills).
  - Where: `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:182-191`

- BluetoothPacketBroadcaster actor scope leak
  - `BluetoothPacketBroadcaster` creates its own `broadcasterScope` with a fresh `SupervisorJob()` instead of using the connection scope passed into the manager. It’s never cancelled in `stopServices()`.
  - Impact: Actor and jobs can outlive the connection manager/service, causing leaks and unexpected broadcast attempts.
  - Where:
    - `app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt:55` (ctor receives a scope)
    - `app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt:98` (creates new `broadcasterScope` ignoring the injected scope)
    - No corresponding cancellation on stop in `BluetoothConnectionManager.stopServices()`.

- Boot completed auto-start edge cases
  - Auto-start runs on BOOT_COMPLETED and LOCKED_BOOT_COMPLETED.
  - On Android 13+, if POST_NOTIFICATIONS isn’t granted, `MeshForegroundService.start()` won’t start (correct), but there’s no fallback scheduling (e.g., WorkManager) to retry foreground promotion once the user unlocks or grants permission later.
  - Impact: Users expecting auto-start may never get the mesh until they open the app once or grant notifications via a separate action.
  - Where:
    - `app/src/main/java/com/bitchat/android/service/BootCompletedReceiver.kt:10-19`
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:37-63` (strict start gating on notif permission)

- Multiple starters and race potential
  - Both `BitchatApplication` and `MainActivity` attempt to start the foreground service and/or get/create the mesh.
  - Impact: It works because the holder dedupes instances and `startServices()` is idempotent, but the responsibility boundaries are muddled and harder to reason about; risk of start during missing perms or unwanted background.
  - Where:
    - `app/src/main/java/com/bitchat/android/BitchatApplication.kt:42-46`
    - `app/src/main/java/com/bitchat/android/MainActivity.kt:47-67`

- Mesh can run without an attached UI delegate
  - Intentional, and DM notifications are handled via `serviceNotificationManager` if no UI delegate is present.
  - Risk: Good for background DMs, but be sure delivery acks/read receipts don’t depend on UI-only state.
  - Where:
    - `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt:391-411` (use of `serviceNotificationManager` when delegate is null)

- Android 14 FGS restrictions timing window
  - `startForegroundService()` requires calling `startForeground()` within ~5 seconds. Primary start path builds the notification right away, but the retry/promotion logic in the update loop could call `startForeground()` multiple times or after a delay.
  - Impact: While caught in try/catch, repeated `startForeground()` attempts aren’t ideal; more deterministic promotion improves reliability.
  - Where:
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:168-174` (initial call OK)
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:178-187` (repeated attempts in loop)

- Over-permissive defaults
  - Defaults enable background and auto-start. Without an explicit UX flow, users may unexpectedly run BLE activity until they disable background.
  - Recommendation: Default background OFF on fresh installs, and require explicit opt‑in.
  - Where:
    - `app/src/main/java/com/bitchat/android/service/MeshServicePreferences.kt:20-32`

- Minor: Notification permission “grant” handling depends on an in‑app broadcast
  - `NotificationPermissionChangedReceiver` listens for `ACTION_NOTIFICATION_PERMISSION_GRANTED` but depends on some component actually broadcasting it.
  - Impact: If no component sends the broadcast after permission grant, the FGS won’t auto‑promote.
  - Where:
    - `app/src/main/java/com/bitchat/android/service/NotificationPermissionChangedReceiver.kt:10-18`

**What Might Not Work Reliably In Background**

- Packet relay and scanning without a persistent FGS
  - If background preference is disabled or notification permission is missing, the mesh can still be started by `ensureMeshStarted()` and keep running without a foreground notification. Android may restrict scan/advertise in Doze, leading to reduced connectivity, delayed DMs, and poor relay propagation.

- “Stop service” paths
  - Using the STOP notification action does not stop the mesh; users think they stopped the app, but BLE stays active. This can cause unexpected background behavior and policy problems.

- Restart after process kill or reboot
  - Reboot auto-start requires notification permission; otherwise it silently does nothing. There’s no queued retry to promote the service after the user unlocks/changes permission without opening the app.

- Actor/broadcaster lifecycle
  - The broadcaster actor may outlive the connection manager/service and could keep jobs alive, causing unexpected logs or work after shutdown.

- Foreground service promotion timing/retries
  - Promotion attempts in the periodic loop could miss the 5s window or re‑invoke `startForeground()` unnecessarily.

**Recommendations**

- Enforce mesh lifecycle to foreground state
  - Start/stop mesh strictly based on foreground eligibility and user preference:
    - Only start mesh when both background is enabled and all required permissions are present.
    - Stop mesh immediately when background is disabled or notification permission is revoked.
  - Where/How:
    - Update `ensureMeshStarted()` to require `MeshServicePreferences.isBackgroundEnabled(true)` and `hasNotificationPermission()` in addition to BT perms before starting the mesh. Also add a symmetric `ensureMeshStopped()` when not eligible.
      - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:192`
    - In the periodic loop, if background is disabled or permissions are missing, also stop the mesh, not just the foreground/notification.
      - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:182-191`

- Fix STOP and onDestroy behavior
  - STOP action should stop the mesh and clear the holder.
  - `onDestroy()` should best‑effort stop mesh if this service is the owner.
  - Where/How:
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:133` — call `meshService?.stopServices()` and `MeshServiceHolder.clear()` before `stopSelf()`.
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:234` — on destroy, if foreground eligibility is off, call `meshService?.stopServices()`; consider a guard not to kill if another visible Activity is using it (see next).

- Introduce simple ownership/reference accounting for `MeshServiceHolder`
  - Avoid ambiguous ownership between Activity and FGS:
    - Track “UI attached” vs “FGS attached”; only stop underlying mesh when both detach, or when policy requires it (user disabled background).
  - Where/How:
    - Extend `MeshServiceHolder` with simple ref counts or flags to indicate active owners; add `attach(type)`/`detach(type)`.
      - `app/src/main/java/com/bitchat/android/service/MeshServiceHolder.kt`
    - In `MainActivity.onStart/onStop` attach/detach the UI owner; in `MeshForegroundService.onCreate/onDestroy` attach/detach the service owner.

- Use a single, parent scope to avoid leaks
  - Make `BluetoothPacketBroadcaster` use the injected connection scope instead of creating its own `SupervisorJob()`.
  - Where/How:
    - `app/src/main/java/com/bitchat/android/mesh/BluetoothPacketBroadcaster.kt:98` — replace `broadcasterScope` with the passed `connectionScope` and remove the extra job. Also ensure any actors/jobs are cancelled when `BluetoothConnectionManager.stopServices()` cancels its scope.

- Tighten foreground promotion logic
  - Avoid repeated `startForeground()` calls in the update loop. Promote once on start when eligible; afterwards only call `NotificationManagerCompat.notify()`.
  - Where/How:
    - `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:176-187` — keep a local `isInForeground` flag; only call `startForeground()` on the transition from not‑in‑FG to in‑FG. Use `notify()` for updates.

- Improve boot/permission retry behavior
  - When auto-start is enabled but notification permission is missing:
    - Consider scheduling a WorkManager task that re‑evaluates eligibility on unlock or app launch and then promotes the FGS, or rely on an in‑app signal sent by permission request flows.
  - Where/How:
    - `app/src/main/java/com/bitchat/android/service/BootCompletedReceiver.kt:10-19`
    - Ensure the onboarding/permission flow emits `ACTION_NOTIFICATION_PERMISSION_GRANTED` broadcast upon grant so `NotificationPermissionChangedReceiver` can promote immediately.

- Default background OFF on fresh installs
  - Reduce surprises and align with Play policy expectations by requiring explicit opt‑in to run in background.
  - Where/How:
    - Change defaults in `MeshServicePreferences`:
      - `app/src/main/java/com/bitchat/android/service/MeshServicePreferences.kt:20-32` — set default `false` for `BACKGROUND_ENABLED` and possibly `AUTO_START`.

- Optional: Consolidate service/mesh creation path
  - To reduce races and complexity:
    - Let the foreground service own `BluetoothMeshService` creation, and have the UI bind to it via the holder.
    - Remove the eager start from `BitchatApplication`, and let `MainActivity` only request start of the FGS (which in turn creates/adopts the mesh).
  - Where/How:
    - `app/src/main/java/com/bitchat/android/BitchatApplication.kt:42-46` — consider removing the proactive start or guard behind explicit background enabled + permission checks.

- Validate read receipt/DM flows without UI
  - Current design shows DM notifications via the service when `delegate == null`; that’s good.
  - Ensure delivery acks and handshake retries don’t rely on UI-only paths.
  - Where/How:
    - Review and, if needed, mirror “UI-triggered” handshake initiation logic inside `BluetoothMeshService` for background-only contexts.
      - `app/src/main/java/com/bitchat/android/mesh/BluetoothMeshService.kt:789, 873, 1086-1090`

**Minor Polish**

- Consider adding a user-visible state in the persistent notification when background is disabled or permissions are missing, so the user can tap to enable.
  - Where: `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:208-240`

- Consider scoping `updateJob` to `serviceJob` and guarding multiple starters.
  - Where: `app/src/main/java/com/bitchat/android/service/MeshForegroundService.kt:27, 158-196`

- Revisit scan/advertise settings in Doze with FGS active
  - PowerManager duty cycle is good; validate real‑world scan rates under Doze while FGS is active and adjust thresholds if relay connectivity drops.
  - Where: `app/src/main/java/com/bitchat/android/mesh/PowerManager.kt`

**Suggested Acceptance Criteria After Fixes**

- When background is ON and notifications are granted:
  - Mesh starts, persistent notification is visible, scanning/advertising work after swiping the app away.
- When background is OFF or notifications are revoked:
  - Foreground service and mesh both stop; no BLE activity remains.
- STOP action fully stops mesh and closes the foreground service.
- After reboot, if background is ON and notification permission is granted:
  - Foreground service starts and mesh runs. If not granted, it defers cleanly until the user grants.
- No coroutine/actor leaks after stop/restart cycles:
  - Logs cleanly shut down and no lingering “broadcaster actor” messages after stopping.

If you want, I can implement the specific code changes for:
- stopping mesh on STOP/onDestroy,
- gating `ensureMeshStarted()` and adding `ensureMeshStopped()`,
- fixing `BluetoothPacketBroadcaster` to use the injected scope,
- tightening `startForeground()` promotion logic.

