# What the refactor improved

A one-page list of what the Navigation 3, Hilt and modularization refactor made better, for
the maintainer. Each item names the phase that did it. The reasoning, the alternatives and the
costs are in [`refactor-decisions.md`](refactor-decisions.md); this page lists only the gains,
with the costs summarized at the end so the picture stays honest.

Kept up to date at the end of every phase.

## Bugs users could hit, now fixed

These existed in upstream before the refactor.

| What a user saw before | Now | Phase |
|---|---|---|
| The password prompt for a password-protected channel never appeared, and the next Back press was swallowed. | The prompt shows. | 4 |
| The mesh head count on location channels did not update live. | It updates live. | 5 |
| A private chat's title kept the old name after a fingerprint's name changed. | The title follows the name. | 7b |
| The Debug settings mesh graph kept redrawing forever. | It stops once the layout settles. | 7.5 |
| The watch colored a peer differently from the phone and iOS, and renaming a peer changed its color. | One color per identity on every client. | 8 |
| First launch flashed the chat screen for about half a second before the permission explanation. Try again flipped to chat and back. | Onboarding stays up until the next step is known. Returning users still see chat at once. | 9 (F8) |
| Rotating the phone in the first 1.5 s of startup ended on "Failed to initialize the app". | Startup carries on across the rotation. | 9 (F2) |
| Phones without Bluetooth hit an error screen on every launch. | They get the existing "Bluetooth not supported → Continue" step. | 9 (F4) |
| Declining the system battery dialog still moved on, and the screen came back next launch. | You stay on the battery step. | 9 (F7) |
| An interrupted permission dialog could mark onboarding done, then fail with "permissions were revoked". | Onboarding is marked done only once the permissions are granted. | 9 (F5) |
| After Bluetooth or location went off while the app was away, DM notifications might not reach the background service. | The mesh callbacks hand over on every pause and resume. | 9 (F9) |
| Turning Bluetooth back on could start the mesh twice and reopen the notification the app was launched from. | Startup runs once; the launch intent is handled once, rotation included. | 9 (F1) |
| Rarely, a spinner with no buttons after a permission dialog. | The step is recomputed whenever onboarding opens. | 9 (F3) |
| A skipped battery step came back when a new optional permission was asked; the Bluetooth skip was ignored on the same path. | Both skips are honoured. | 9 (F6) |
| Turning Bluetooth off during first launch jumped away from the permission explanation mid-dialog. | Radio changes wait until the permission step ends. | 9 (F11) |
| Debug opened or replaced the mesh service; after a panic clear it kept showing the old one. | It shows the current mesh, or says there is none, and never creates one. | 10a (B1) |
| Debug's relay graph stopped when scrolled off-screen, its legend nicknames never refreshed and colours changed on rotation. | It samples the whole time Debug is shown; nicknames refresh; colours are stable. | 10a (B2) |
| Debug kept polling and sampling while the app was in the background. | It pauses when Debug is not on screen. The graph shows a gap. | 10a (B3) |
| About's Tor status card did not follow the Tor switch. | It follows the switch and changes made elsewhere. | 10a (R1) |
| A location note's draft was cleared even when sending failed, and lost on rotation. | The draft is kept until the send succeeds and survives rotation. | 10a (F3) |
| Swiping a conversation to mark it read or delete it left the row stuck off to the side, and the Undo snackbar was missing on most deletes. | The row slides back and Undo shows on every delete. | 10b |
| Undo after deleting a conversation was lost on rotation or when a chat was opened. | It survives both, for up to two minutes. | 10b (F2) |
| The channels screen's Tor row ignored a change made in About. | It follows the switch. | 10b (R2) |
| Back handling registered a new callback on every recomposition. | One `BackHandler`. | Groundwork (#912) |

The Phase 9 rows link to their commits in "What Phase 9 fixed" in `refactor-decisions.md`.
Each real fix is one commit and can be reverted alone.

## Privacy

- The peer list no longer logs peer IDs or favourites on every redraw, in release builds too. A unit test fails if a peer-list log call interpolates a peer ID, favourite or nickname. 10b (F1)

## State that now survives rotation and process death

Before, these were lost when Android recreated the Activity or killed the process.

- The open screen or sheet, and the whole back stack beneath it.
- Unsent text in the composer, restored only into the conversation it was typed in.
- The selected tab on QR verification.
- A geohash pick made while the process was dead.

## Smaller user-facing improvements

- The peer list, private chat and location notes are full screens with a back arrow and
  predictive back, instead of bottom sheets (Phase 7.5).
- Peer list → private chat → Back returns to the list (Phase 7.5).
- Location notes opened from location channels go back to channels (Phase 7.5).
- The location notes "acquiring" state has a way back (Phase 7.5).
- Location channels no longer stops and restarts location sampling on rotation (Phase 5).

## Upstream bugs found along the way, not yet fixed

The refactor's tests and phone runs surfaced these. Each is a candidate for a small upstream PR,
kept out of the refactor so it changes no behaviour it did not have to. Details in
`refactor-decisions.md`, section 5.

- `NostrRelayManager.resetAllConnections` can throw mid-reset and leave relays disconnected.
- The first Nostr private message sent after Bluetooth is turned off can be lost with no error.
- A Nostr private message's sender can show as "anon" instead of the favourite's nickname.
- `BluetoothPacketBroadcaster` spins at about 100 failures a second while Bluetooth is off.
- The favourite star has no accessibility label.
- A geohash channel shows two different "N people" counts: the channel list and the chat
  screen's top bar count everyone seen in five minutes, silent heartbeats included, while the
  people list counts only people with a known name, so the two can differ by an order of
  magnitude.

## For contributors and the codebase

| | Before | After |
|---|---|---|
| Modules | One phone module; the watch compiled 113 of its files through a copy task with 29 globs | 19 library modules; both clients depend on the shared ones; the copy task is gone |
| `ChatViewModel` | 1744 lines, built the whole chat session | 269 lines; the session lives in an Activity-retained Hilt scope |
| Onboarding | About 300 lines of private functions in `MainActivity`, five helpers holding the Activity | One MVI view model in `:feature:onboarding`, one pure `nextStep` function, one startup gate |
| Dependency injection | Hand-built objects and static `getInstance()` calls | Hilt, with the graph checked at compile time |
| Navigation | Visibility flags in view models | Typed, serializable Navigation 3 routes on one back stack |
| Device tests | None | 64 in `:app` and `:feature:chat`, plus one suite per feature (settings 5, location 2, share 3): Back behaviour, every sheet opener, saved state, onboarding, screens |
| Screens (10a) | About, Debug, Location Notes and the APK section ran logic in composables and called singletons; Hotspot and the APK section had their own shapes | All on one shape: `uiState`, `onAction`, `events`; three ViewModels added (`AboutViewModel`, `DebugSettingsViewModel`, `LocationNotesViewModel`), three reshaped |
| Screens (10b) | The peer list and the location channels sheet were composables of 1648 and 1202 lines that read singletons while composing | Both on the same shape, with row models and state builders as tested pure functions; the largest composable file is 769 and 419 lines; the row building runs off the main thread |
| `TransportDiagnostics` | 23 members | 13 |
| Unit tests (10a) | 891 | 1076 |
| Unit tests (10b) | 1200 | 1336 |
| Device tests (10b) | chat 1, location 2 | chat 6, location 4 |
| Module rules | Unwritten | `FeatureDependencyRulesTest` fails when a feature depends on another feature, an `:api` module grows dependencies, or `:core:tor` gains one |
| Watch crypto | Tink 1.8.0 | Tink 1.23.0, the phone's version |
| Watch theme | Its own copy of the palette, peer-color hash and fonts | The shared `:core:designsystem` base |

Safety rails added on the way:
- The Nostr HTTP port fails closed: with nothing installed it throws instead of sending relay
  traffic around Tor. A unit test pins this.
- A Tor route change reaches the relays through a required member of that port, so a missing
  wiring cannot fail open.
- Onboarding's location and Bluetooth receivers register as not exported.
- Every persisted preference key stays byte-identical, pinned by a test, so upgrading users do
  not see first-launch onboarding again.

## What it cost

- Hilt, KSP, Navigation 3 and the Kotlin serialization plugin were added. The Gradle daemon's
  metaspace went from 512m to 1g.
- Release APKs stayed byte-identical across two clean builds at Phases 2 and 6, and the
  container double build matched at the end of Phase 7.5. The final container build runs once,
  at the end of the refactor.
- Device tests need a device or emulator; CI does not run them.
- Owed before handover: Mesh Lab on two Android phones, and a short phone checklist for the
  onboarding fixes (section 5 of `refactor-decisions.md`).
