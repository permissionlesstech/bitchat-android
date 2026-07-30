# Diff-to-screen analysis playbook

Use this reference to turn a code diff into a minimal but complete screenshot
matrix.

## Resolve the correct comparison

For a PR, “before” is the merge-base of the fetched PR head and its configured
base branch. It is not necessarily the PR's current `baseRefOid`, the local
`main`, or the commit immediately preceding the head.

For a branch or commit, confirm the intended base. Compute the merge-base after
fetching both sides.

Record both SHAs before any checkout. Build each SHA rather than attempting to
reverse selected files on a single build.

## Triage the diff in layers

Start broad:

```sh
git diff --stat "$BEFORE_SHA" "$AFTER_SHA"
git diff --name-status "$BEFORE_SHA" "$AFTER_SHA"
git diff "$BEFORE_SHA" "$AFTER_SHA" -- app/src/main app/src/debug
```

Then classify changed files.

| Diff area | Likely visual impact |
|---|---|
| `ui/*.kt`, composables, modifiers, layouts | Direct screen/layout change |
| `ui/theme/*`, colors, shapes, typography | Cross-screen theme change |
| `res/values*`, drawables, mipmaps | Text, locale, icon, launcher, or palette |
| Manifest locale/theme/activity metadata | System or activity presentation |
| `DataManager`, preferences, defaults | Fresh-install/default-state change |
| `AppStateStore`, ViewModel/state flows | UI changes only after specific state |
| Service/transport/backend only | Usually no static UI delta; trace exposed state |
| `src/debug` or Android tests | Fixture/test mechanism, not production UI |

Do not stop at filenames. Search every changed public symbol and resource:

```sh
rg -n "<ChangedSymbol|resource_name>" app/src
```

Trace in both directions:

- Who calls or renders this code?
- Which state branch selects it?
- What user action reaches it?
- What permissions, onboarding, peers, channels, messages, theme, locale, or
  width are required?
- Does the change affect an empty screen, only populated state, or both?

## Repository UI surface map

These are orientation points, not a substitute for inspecting the current
revision:

| Surface | Starting points |
|---|---|
| App launch/navigation/permissions | `MainActivity.kt`, `ui/ChatScreen.kt` |
| Top bar, nickname, peer/channel/location controls | `ui/ChatHeader.kt` |
| Message rows, bubbles, timestamps, media | `ui/MessageComponents.kt` |
| App state consumed by Compose | `services/AppStateStore.kt`, ViewModels |
| Default nickname/preferences | `ui/DataManager.kt` |
| About and Settings | `ui/AboutSheet.kt` |
| Location/geohash/channel controls | `ui/LocationChannelsSheet.kt` |
| Hotspot UI | `hotspot/HotspotActivity.kt` |
| Dynamic/fallback colors and shapes | `ui/theme/Theme.kt`, `ThemePreference.kt` |
| Debug ADB hooks | `src/debug/.../testhook/TestHookReceiver.kt`, `TestHookDriver.kt` |

Files and packages can move. Use `rg --files` and symbol search to re-establish
the current map at the target commits.

## Identify indirect UI changes

Some visual changes appear far away from the edited function:

- A nickname generator change is visible only after deleting or bypassing a
  saved nickname.
- A Material You change appears only on Android 12+ and depends on the emulator
  wallpaper/system palette.
- Locale selection may recreate the Activity and return to a different tab.
- A launcher background is not visible inside the running Activity.
- Message delivery status may appear only for self-authored private messages.
- A width policy may be invisible at the default device width.
- A backend ownership fix may produce no screenshot difference at all.

Write these as explicit hypotheses before capture. Each hypothesis needs either
a matrix row or a documented reason it cannot be shown statically.

## Build the capture matrix

Keep the matrix small enough to review but large enough to hit every changed
branch.

| Field | What to record |
|---|---|
| Surface | Human-readable screen/component |
| Entry path | Actions from launch to the target |
| Fixture | Messages, peer, channel, setting, or empty state |
| Platform | Android API feature needed, such as dynamic color |
| Width | Logical dp breakpoint |
| Theme | System/light/dark |
| Locale | System/default or selected locale |
| Expected before | Specific visual contract |
| Expected after | Specific visual contract |
| Control | State expected not to change, when useful |

Examples:

- Header crowding: 411 dp control, 380 dp middle breakpoint, 320 dp compact
  breakpoint, with a joined channel so the count is actually present.
- Message bubbles: identical short/long received and self messages in light and
  dark modes.
- Nickname prefix: fresh generated nickname in each build; explain that random
  digits differ.
- Language picker: Settings before, Settings after, expanded menu, and one live
  selection.
- Backend-only hotspot fix: the nearest hotspot surface before/after, explicitly
  expecting equality.

## Distinguish visual proof from behavioral proof

A static screenshot can prove rendering, layout, labels, selected state, and
visible recreation. It cannot prove:

- foreign Wi-Fi group ownership
- BLE/Wi-Fi discovery or delivery
- Noise/identity correctness
- race avoidance
- background lifecycle behavior
- accessibility announcement content without an accessibility inspection

Name those limitations. Use relevant unit/instrumented tests or the repository's
Mesh Lab workflow separately.

## Compare carefully

Treat these as expected noise unless the PR changes them:

- status-bar clock
- battery/network indicator
- random nickname suffix
- dynamic palette when system wallpaper differs
- asynchronous peer counts
- animation frame

Stabilize or disclose the noise. Never describe it as a PR effect.

