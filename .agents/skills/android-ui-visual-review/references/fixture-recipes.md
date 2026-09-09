# Deterministic UI fixture recipes

Read current code before applying any recipe. These patterns intentionally use
the debug-only test-hook path and should be adapted to the APIs present at both
comparison SHAs.

## Fixture principles

1. Exercise the UI's real state classification. A self message must carry the
   peer identity that production code uses to decide `isSelf`; changing only the
   displayed sender nickname can produce a false layout.
2. Use the same logical records in both builds: stable IDs, sender IDs, content,
   ordering, and one fixed epoch.
3. Keep content synthetic and review-safe. Do not use real messages, contacts,
   peer IDs, channel memberships, device information, or locations.
4. Return structured success data and verify it before capturing.
5. Keep additions under `app/src/debug`; remove them after capture.

## Prefer the existing test hook

Inspect:

- `app/src/debug/java/com/bitchat/android/testhook/TestHookReceiver.kt`
- `app/src/debug/java/com/bitchat/android/testhook/TestHookDriver.kt`
- `app/src/debug/AndroidManifest.xml`

The receiver accepts:

```sh
adb -s "$ANDROID_REVIEW_SERIAL" shell am broadcast \
  -n com.bitchat.droid/com.bitchat.android.testhook.TestHookReceiver \
  -a com.bitchat.droid.TEST_HOOK \
  --es cmd "<command>" \
  --es id "<unique-result-id>"
```

Read the result rather than trusting broadcast delivery:

```sh
adb -s "$ANDROID_REVIEW_SERIAL" shell run-as com.bitchat.droid \
  cat "cache/testhook/results/<unique-result-id>.json"
```

Existing commands such as `set_nickname`, `broadcast_msg`, and state inspection
may already be sufficient.

## Public message fixture

When existing commands cannot create both received and self messages without a
second device, add a temporary `ui_fixture` command to `TestHookDriver`.

Adapt imports and constructor fields to the checked-out revision. The core shape
is:

```kotlin
private fun uiFixture(context: Context, intent: Intent): JSONObject {
    val mesh = mesh(context)
    val nickname = AppStateStore.nickname.value
        .ifBlank { DataManager(context).loadNickname() }
    val epochMs = intent.getLongExtra("fixture_epoch_ms", 1_800_000_000_000L)

    listOf(
        BitchatMessage(
            id = "ui-fixture-received-short",
            sender = "mara",
            content = "Are you seeing this?",
            timestamp = Date(epochMs),
            senderPeerID = "ui-fixture-peer",
        ),
        BitchatMessage(
            id = "ui-fixture-received-wrap",
            sender = "mara",
            content = "The mesh stays readable even when a message wraps onto a second line.",
            timestamp = Date(epochMs + 60_000),
            senderPeerID = "ui-fixture-peer",
        ),
        BitchatMessage(
            id = "ui-fixture-self-short",
            sender = nickname,
            content = "Yep — testing the message layout.",
            timestamp = Date(epochMs + 120_000),
            senderPeerID = mesh.myPeerID,
        ),
        BitchatMessage(
            id = "ui-fixture-self-wrap",
            sender = nickname,
            content = "Short and long bubbles should align consistently.",
            timestamp = Date(epochMs + 180_000),
            senderPeerID = mesh.myPeerID,
        ),
    ).forEach(AppStateStore::addPublicMessage)

    return ok("ui_fixture")
        .put("messages", 4)
        .put("fixture_epoch_ms", epochMs)
}
```

Why these details matter:

- received/self exercises both alignment branches
- short/wrapped content exercises intrinsic and capped width
- fixed IDs defeat accidental duplication
- a fixed epoch makes before/after timestamps comparable
- `mesh.myPeerID` exercises the real self-classification path

If `AppStateStore` moved packages or the message constructor changed, adapt only
the debug fixture. Do not modify production state logic to accommodate it.

App state is process-local. Launch the Activity before injection, inject after
the process is ready, and capture without force-stopping it.

## Private-message and delivery-status fixture

To inspect private-message bubbles or status placement:

- set the selected private peer in `AppStateStore`
- add messages using `addPrivateMessage`
- use the current local peer ID for self messages
- populate the delivery status explicitly when the changed component reads it
- mark read state consistently

Use a synthetic conversation ID such as `ui-fixture-private-peer`. Avoid writing
to persistent conversation storage unless persistence itself is being reviewed.
If persistence cannot be bypassed safely, use a unique deterministic fixture
conversation and disclose it in the report.

## Fresh default nickname

A saved preference hides generator changes. Add a temporary command that removes
only the nickname preference, invokes the revision's real generator, and updates
the in-memory store:

```kotlin
private fun resetNicknameFixture(context: Context): JSONObject {
    context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
        .edit()
        .remove("nickname")
        .commit()
    val nickname = DataManager(context).loadNickname()
    AppStateStore.setNickname(nickname)
    return ok("reset_nickname_fixture").put("nickname", nickname)
}
```

First inspect `DataManager` to confirm the preference file/key. Do not use this
recipe blindly after storage migrations.

Random suffixes are expected to differ. Compare the changed prefix/pattern, not
the digits. Do not seed or replace the production generator merely to make the
screenshot deterministic.

## Channel/header state

Responsive header screenshots need every conditional item present.

- Join a synthetic channel such as `#review` through the normal UI or existing
  debug state API.
- Confirm the joined count is visible at the control width before changing
  density.
- Keep the same nickname, channel, peer count, and location state in both builds.
- Capture a wide control and every threshold changed by the diff.

Do not use a real geohash/location. Synthetic channel state is enough for header
crowding unless the actual geohash label is the feature under review.

## Theme and dynamic color

- Force light and dark through `adb shell cmd uimode night no|yes`.
- Record the system wallpaper/palette only as anonymous environment context.
- Keep the same emulator data directory between builds so Material You input is
  identical.
- Capture both themes when theme code, containers, surfaces, or contrast changes.
- Restore the original mode after capture.

## Locale

Use the new in-app picker when that is the feature. Capture:

1. Settings before
2. Settings with the new row
3. expanded picker
4. one live language selection

Locale application may recreate the Activity and reset the selected tab. This is
expected; navigate again rather than assuming the selection failed. Restore
System default after capture.

## Onboarding and permissions

Complete onboarding/permissions once in the before build, then use `adb install
-r` for after when signatures and data schemas are compatible.

If state cannot be preserved, record and replay each action. Do not clear all app
data merely to change one preference. Never run `pm clear` on a physical device
without explicit authorization.

## Remove the fixture

Use a focused patch to remove only additions made for capture. Then check:

```sh
git diff -- app/src/debug
git status --short
```

The finished worktree may contain untracked artifacts only when artifacts were
intentionally stored there. It must not contain tracked fixture changes.

