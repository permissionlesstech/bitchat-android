# Refactor decision log: Navigation 3, Hilt and modules

## 1. Purpose and how to read this

This log records what the Navigation 3, Hilt and modularization refactor decided, why, and
what it left open. It is written for the maintainer, who will receive the whole refactor once,
at the end. For only the gains, on one page, read
[`refactor-benefits.md`](refactor-benefits.md). Read section 2 for the summary. Read section 3 only for the phase you want to
question. Sections 5 and 6 list what is still owed.

In the decision tables, **(D)** marks a decision the developer made. **(R)** marks a ruling made
during execution. **(P)** marks a decision the phase's plan made, with its reason. An unmarked
row means the sources do not say who made it. "Not recorded" means the sources give no reason
or alternative.

## 2. Summary for the maintainer

### What the refactor delivers

- **Navigation 3.** Every screen and sheet is a typed, serializable route on one back stack.
  The back stack survives process death. Back behaviour is covered by device tests. The
  maintainer asked for Navigation 3. Hilt and the module split were the developer's proposal.
- **Hilt DI.** ViewModels are injected. The chat session (`ChatState`, the managers, the session
  classes) is built in its own Hilt scope, inside the identity's, instead of inside `ChatViewModel`.
  `ChatViewModel` went from 1744 lines (start of Phase 4 Part 2) to 269 (end of Phase 7a).
  Each screen has its own ViewModel where it owns state.
- **Modules.** The phone app was one module and the watch compiled 113 of its files through a
  copy task. There are now 19 library modules:
  - `:core:domain`, `:core:navigation`, `:core:mesh`, `:core:nostr`, `:core:chat`, `:core:tor`,
    `:core:readiness`
  - `:core:designsystem` (shared with Wear) and `:core:designsystem:phone`
  - `:feature:share`, `:feature:settings`, `:feature:location`, `:feature:chat`,
    `:feature:onboarding`, each with an `:api` module

  Wear depends on `:core:domain`, `:core:mesh` and `:core:designsystem`. The copy task is gone.
- **Onboarding.** It left `MainActivity` (Phase 9). One view model drives its steps, and one app
  gate decides between onboarding and chat. Eleven onboarding bugs were fixed or removed.
- **One screen shape (Phase 10a, 10b).** Every screen with state has a ViewModel with one UI
  state and one action entry point, and a stateless screen that can be previewed and tested
  without Hilt.
- **No `getInstance()` singletons, and an identity scope (Phase 10c).** Every one is injected.
  What belongs to one identity lives in a Hilt identity scope that can end, so a panic throws the
  identity away and builds a new one, and then wipes the sandbox except a short keep-list.
- **Device tests.** The repo had no instrumented tests when Phase 4 began. There are now 122:
  104 in `:app`, 6 in `:feature:chat`, 5 in `:feature:settings`, 4 in `:feature:location` and
  3 in `:feature:share`. They run on a connected device or emulator, not in CI.

### What users will notice

Little outside panic. Every user-visible change is listed below.

Visual and navigation changes (Phase 7.5):
- The peer list, the private chat and location notes are full screens instead of bottom sheets.
- Every full screen closes with a leading back arrow instead of a trailing close button. This
  covers About, Debug settings, QR verification and location channels too.
- Pushed screens slide horizontally. The onboarding-to-chat swap fades. Slides mirror in RTL.
- Opening a private chat from the peer list pushes it, so Back returns to the list. Every other
  opener returns to chat, as before.
- Location notes opened from location channels go back to channels, then to chat.
- The location notes "acquiring" state gained the back arrow it lacked.

State that now survives Activity recreation or process death (Phases 4 and 5):
- The open screen or sheet, and the back stack beneath it.
- Unsent text in the composer. It is restored only into the conversation it was typed in.
- The selected tab on QR verification.
- A geohash pick made while the process was killed.

Bug fixes and small behaviour changes:
- The password prompt for a password-protected channel now shows. Before, it never appeared,
  and the next Back press was swallowed (Phase 4).
- The mesh head count on location channels updates live (Phase 5).
- The private chat's title follows a fingerprint's name change (Phase 7b).
- The Debug settings mesh graph stops redrawing once it settles (Phase 7.5).
- Wear colors each peer by identity, as the phone and iOS do. Renaming a peer no longer changes
  its color on the watch (Phase 8).

Onboarding (Phase 9). The full list, with commits, is in "What Phase 9 fixed" in section 3:
- First launch no longer flashes the chat screen before the permission explanation. Try again
  stays on onboarding instead of flipping to chat for half a second.
- Rotating the phone during startup no longer ends on "Failed to initialize the app".
- Declining the system battery dialog keeps you on the battery step.
- Phones without Bluetooth get the "Bluetooth not supported → Continue" step instead of an error.
- A skipped battery step no longer comes back when a new optional permission is asked. Turning
  Bluetooth off while granting permissions no longer jumps to the Bluetooth step.

Screens (Phases 10a and 10b):
- Debug shows the current mesh and never creates one; its relay graph samples the whole time
  Debug is shown, keeps stable colours and refreshes nicknames; it stops polling while not on
  screen.
- About's Tor status, and the channels screen's Tor row, follow the Tor switch.
- A location note's draft is kept until it sends, and survives rotation.
- A swiped conversation row slides back, and Undo shows on every delete and survives rotation
  and opening a chat, for up to two minutes.

Panic (Phase 10c; 10c-1 and 10c-2a changed no behaviour):
- Panic stays in the same process and Activity, closes an open private chat or sheet, and
  returns to the main chat on a new identity.
- The screen goes blank before the slow stages of the wipe, so no message shows during it.
- Sends still mining when panic starts are not published, and swiping the app away mid-panic no
  longer stops the wipe.
- Everything outside a short keep-list of settings is wiped, along with every Keystore key, the
  clipboard, notifications and conversation shortcuts. Settings and onboarding stay; the hotspot
  name and the last peer-alert time reset.
- A wipe that cannot confirm restarts the app (the splash shows), and the next launch finishes
  it. This should never happen on a working phone.
- Panic takes longer: 0.8 s on a real phone (1.0 to 2.9 s on an emulator), against about 0.4 s on an emulator before 10c-3.

### What contributors will notice

- **Modules.** `AGENTS.md` describes each module and its job. Package names did not change in
  any move, so imports still read as before.
- **Dependency rules, enforced by `FeatureDependencyRulesTest`:**
  - A feature depends on another feature's `:api` only, never on the feature.
  - An `:api` module depends on no project module other than `:core:navigation` and
    `:core:domain`. External Compose runtime/ui libraries are allowed.
  - `:core:tor` depends on no project module.
  - `:core:designsystem` has no Material3 and no other project module.
- **How to add a screen:**
  1. Declare a `@Serializable` route implementing `NavKey` in the feature's `:api` module.
  2. Register its entry in the feature's `@Provides @IntoSet EntryProviderInstaller`
     (`ChatEntriesModule`, `LocationEntriesModule`, `SettingsEntriesModule`,
     `OnboardingEntriesModule`). `MainActivity` registers no destinations itself.
  3. Add the route to the list in `EveryRouteHasAnEntryTest`. The list is hand-written, so a
     route left off it is not checked.
  4. Make it a sheet (`SheetSceneStrategy.sheet()`) only if it is a leaf. A sheet never opens
     on top of another sheet. A device test walks every opener to enforce this.
  5. Put its strings in the feature's own `res/`, in every locale.
- **Ports.** Lower modules never reach into `:app`. They declare a port and the app binds it
  (for example `NostrHttpClients`, `WifiAwarePeers`, `TransportToggles`,
  `NotificationTargets`, `HotspotRadioLease`, `TransportDiagnostics`, `OnboardingHost`,
  `WifiAwareAvailability`). A port that guards
  privacy fails closed when nothing is bound.
- **New module checklist.** Generate the module's lockfile and verification metadata with real
  tasks (`--write-locks --write-verification-metadata sha256`) before its first compile. Apply
  the `bitchat.android.*` convention plugins. A convention plugin must pin every build setting
  `app/build.gradle.kts` pins, `buildToolsVersion` included.
- **Shared test doubles** live in `core/mesh/src/testFixtures/`.

### What it costs

Facts only where the sources measured them.

- **Build.** The Gradle daemon's metaspace went from 512m to 1g, because KSP and Hilt exhausted
  the old ceiling during a combined test and lint run. Build time was not recorded.
- **Dependencies added.** Hilt/Dagger and KSP; Navigation 3; the Kotlin serialization compiler
  plugin (the runtime was already on the classpath through Navigation 3). Test-only:
  espresso-core 3.7.0 and androidx.test.ext:junit 1.3.0, declared explicitly, and
  ui-test-manifest as a debug-only dependency.
- **Wear dependency changes.** Tink 1.8.0 → 1.23.0, the phone's version (Phase 6). Wear now
  compiles against the same Compose BOM it already ran on (1.9.0 → 1.11.4, Phase 8).
- **Size.** Phase 8 changed the release APK by −64 bytes of dex and +1 byte of baseline
  profile. The native Tor libraries are byte-identical. The size change of the whole refactor
  against upstream was not recorded.
- **Reproducibility.** Release APKs were byte-identical across two clean builds at Phase 2 and
  at Phase 6. The fork CI's container double build and byte compare passed at the end of Phase
  7.5. The container build was deferred to the end of the refactor. It passed at the final
  code: on PR #972 (d663482a), CI built the release twice in the container
  (`tools/reproducible-builds/build-in-container.sh`) and the bytes compared equal.
- **Panic time.** 1.0 to 2.9 s on an emulator, against about 0.4 s in 10c-2b. The developer
  accepted it. On a real phone it took 0.79 s, of which the wipe was 30 ms.
- **Tests.** 1773 JVM tests (3 skipped) across the phone, the core modules, the features and
  Wear, after the Phase 11 audit and the Codex fixes. 126 device tests: 108 in `:app`, 6 in `:feature:chat`, 5 in
  `:feature:settings`, 4 in `:feature:location`, 3 in `:feature:share`.

## 3. Decisions, by phase

### Groundwork: upstream PRs #912 and #913

**What changed.** `BackHandler` replaced an `OnBackPressedCallback` that was registered on
every recomposition (#912). Onboarding and chat went behind a Navigation 3 back stack, Hilt and
KSP were added, and ViewModels were injected (#913). Both PRs are contained in this branch.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Onboarding is one destination, not one per step. | Its steps follow permission results and adapter state, not user navigation. Going back a step was never supported. Per-step routes would invent a history that does not exist. | Eight onboarding routes. | The state machine stayed in `MainActivity` until Phase 9. |
| The onboarding-to-chat crossing replaces the stack (`resetTo`). | Back must not re-enter onboarding. | Not recorded. | Phase 7.5 found this lost the chat stack; fixed by saving and restoring it (see 7.5). |
| Mesh bindings in Hilt are unscoped. | `MeshServiceHolder` is the source of truth, is shared with the foreground service, and is replaced after a panic clear. A singleton binding would hand out a stale service. | `@Singleton` bindings. | Every injection re-reads the holder. |
| Navigation types stay in `:app` until a second module needs them. | A `:core:navigation` module needed convention plugins first. | Not recorded. | Moved in Phase 2. |
| Gradle metaspace raised to 1g. | KSP and Hilt exhausted 512m. The failure shows up as an unrelated OutOfMemoryError and can truncate `gradle/verification-metadata.xml`. | Not recorded. | JVM arguments are not build inputs, so reproducibility is unaffected. |
| Hilt as the DI framework. | The design notes cite the reproducible-build requirement; Hilt resolves the graph at compile time. The detailed comparison was not recorded. | Koin (used earlier in this codebase). | KSP and Dagger code generation add build time and metaspace (row above). |

### Phase 2: convention plugins, `:core:domain`, `:core:navigation`

**What changed.** A `build-logic` included build with three convention plugins. `:core:domain`
holds the shared models. `:core:navigation` holds `Navigator`, the `NavDisplay` host and the
installer type. Back at the root destination was fixed.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) Work horizontally, one layer at a time. | Vertical slices put more kinds of change in one diff and force rework. | Vertical slicing. | Later layers depend on earlier ones being right. |
| `:core:domain` is the first consumer of the convention plugins. | Proves the plugins on a real module instead of landing config with no caller. | Not recorded. | — |
| Package names never change on a move. | `:app` needs no import changes; diffs read as renames. | Not recorded. | Packages no longer tell you which module a file is in. |
| A smart cast broken by a move is fixed with a local `val`. | Kotlin will not smart-cast a property from another module. A local keeps the null-safety. | `!!`. | — |
| The library convention plugin pins `buildToolsVersion`. | Libraries took AGP's default 36.0.0. The reproducible-build container ships only 37.0.0. | Not recorded. | Every future plugin needs the same audit against `app/build.gradle.kts`. |
| The chat screen owns a `BackHandler`, composed after `NavDisplay` and gated on `pendingBackAction`. | `NavDisplay` disables its own Back handler at stack depth one, so overlays never unwound and Back closed the app. Among enabled handlers the last composed wins. | Relying on `NavDisplay`'s `onBack` with an `interceptBack` hook. | Shrinks as overlays become routes. |
| The back-unwind order is a pure function, `pendingBackAction`. | `ChatViewModel` could not be built in a unit test at the time. | Testing `handleBackPressed` directly. | — |
| Groovy and Ant verification-metadata entries applied by hand. | Only Android Studio sync resolves them; no command line reaches them. | Not recorded. | Android Studio sync itself was not verified. |

### Phase 3: DI cleanup

**What changed.** `ChatViewModel` stopped reaching process-wide singletons through static
`getInstance()` calls. DI modules went from 1 to 4.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Bridge the singletons into Hilt with `@Provides`; keep them as `getInstance(context)` singletons. | `SeenMessageStore` was compiled into `:wear`, which has no Hilt or KSP. | Converting them to `@Inject` classes. | — |
| Provide only singletons that injectable code already reached statically. | Bindings nothing consumes would be speculative. Converting the rest cascades. | Converting the whole inventory. | About 17 `object` singletons in `services/` and `ui/` were left as they were. |
| Inject them as `dagger.Lazy`. | Each was resolved at the point of use. Eager injection would construct them earlier. | Eager injection. | — |
| `MessageRouter` is unscoped and injected as `Provider<MessageRouter>`. | `getInstance` re-points the router at the current mesh. A cached binding would skip that after a panic clear replaces the mesh. | A scoped binding. The deeper fix, a mesh service with a stable identity, reaches into the mesh layer shared with Wear. | The deeper fix landed in 10c-2a-2b: a holder in the identity scope. |
| `DebugSettingsManager` and `ConversationListPreferences` injected after all. | Re-examined: the first reason given for excluding them was wrong. | Leaving them out. | — |

### Phase 4: sheets become destinations

**What changed.** The first instrumented test harness. The back stack survives process death.
About, Debug settings, verification and location channels became routes. Location notes, chat
user, peer list, private chat and security verification became sheet destinations. A live bug
in the password prompt was fixed first.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Destination type follows a rule: internal navigation, stacking or activity-for-result → route; a leaf → bottom-sheet scene. | A rule instead of judging by size. | Choosing by size. | — |
| A conversion is a destination plus a ViewModel only where the screen owns state. | Not recorded. | Not recorded. | — |
| `ChatViewModel` stays alive and shrinks as screens leave it. | Not recorded. | Not recorded. | — |
| Screen ViewModels inject managers, never `ChatViewModel`. | Keeps the conversions independent of each other. | Not recorded. | Needed the Phase 5 session graph first. |
| About converts first. | It touches no chat state and has internal tabs, so it exercises the route case without risking the chat path. | Not recorded. | — |
| (D) Add instrumented tests that drive the real `MainActivity`, with no `HiltTestApplication` and no custom runner. | Unit tests cannot reach Back dispatch: Robolectric's maximum SDK is below the app's target SDK. | Robolectric Compose tests. | Device-only. CI has no emulator. Accepted, not overlooked. |
| Declare espresso-core and androidx.test.ext:junit explicitly. | The inherited espresso-core 3.5.0 crashes every device test on API 35 and later. | Inheriting transitive versions. | — |
| Save the back stack with `rememberSaveable` into a Bundle, through Navigation 3's `NavKeySerializer`. | Two sheets already survived recreation through ViewModel flags; converting them without this would lose that. | JSON encoding (not on the classpath, not needed). | Adds only the serialization compiler plugin. |
| Wire the password prompt dialog instead of deleting its flag. | The whole flow existed except one boolean. | Removing the flag. | — |
| The chat user route names the message by id, not the message. | A key is saved to a Bundle; a message cannot be one, and its text should not be in saved state. | Carrying the message. | After process death the sheet offers the user actions without the message. |
| A saved draft is keyed by its conversation. | A plain saveable restore put private text into the public composer. | Keying on the selected peer. | — |
| App-foreground tracking moves above navigation. | Foreground belongs to the process. A route replacing chat stopped live voice. | Keeping it in `ChatScreen`. | — |
| The download ViewModel is scoped to the Activity. | Entry scoping would cancel an in-flight download when About is popped. | Entry scope. | — |
| Peer list, private chat and security verification convert together. | They are coupled through `ChatState`. Converting the list alone hides the private chat on the notification path. | One at a time. | Messaging path changed; owed Mesh Lab. |
| `Navigator.openPrivateChat` is the only way to open a private chat. | One chat replaces another instead of stacking. | Not recorded. | — |

### Phase 5: the chat session graph moves into DI

**What changed.** `ChatState` and the managers are built in an Activity-retained Hilt scope.
Session classes (`MessageSender`, `PrivateChatSession`, `ComposerMedia`, `ContactFavorites`,
`ConversationDirectory`, `GeohashSession`, `PanicClear`, `ChatMeshDelegate`) left
`ChatViewModel`. Screens got their own ViewModels. `ChatViewModel`: 1744 → 647 lines.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| The session scope is a main-thread supervisor, cancelled when the retained component clears. | That is what `viewModelScope` was. The component clears from the same `ViewModelStore`, so no lifetime changes. | Not recorded. | — |
| Managers are provided, not `@Inject`-annotated. | They keep their plain constructors, which their unit tests call. | Annotating them. | — |
| `ChatSessionMesh` holds the mesh and is replaced only by a panic clear. | The foreground service clears `MeshServiceHolder` on stop. Re-reading it would create a mesh that was never started. | Re-reading the holder. | — |
| `GeohashSession` keeps a never-cancelled scope. | Tying it to the session would cancel a geohash message still sending when the Activity finishes. That is a messaging change for its own commit. | The session scope. | Closed in 10c-2b: the session's end cancels the scope, and a send runs on the identity's scope. |
| Session classes expose a start call instead of registering in their constructors. | The favourites listener must be registered after its service initialises, or it fails silently. | Registering on construction. | — |
| Code moves unchanged and in order (send path, panic clear, conversation list). | Not recorded. | Not recorded. | Mesh delegate wiring moved; owed Mesh Lab. |
| Location channels ends sampling in `onCleared`. | Recreating the Activity no longer stops and restarts sampling. | `DisposableEffect`. | Small behaviour change, toward what the screen meant. |
| The QR string is built once per nickname. | Each build signs a new nonce, which would change the code under the other side's camera. | Rebuilding on every state emission. | — |

### Phase 6: the shared mesh stack becomes `:core:mesh`

**What changed.** 113 files moved into `:core:mesh` by `git mv`. Both apps depend on it. The
watch's copy task and its 29 globs are gone. Wear also depends on `:core:domain`.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Replace the watch's same-name stand-in classes with explicit hooks (`BluetoothPermissionManager(requireLocation)`, `TransportToggles`). | Shared code could not stand alone while each client compiled its own class under the same name. | Same-name classes per client. | — |
| Delete the unused `CyberpunkVisualizer`. | Nothing called it, and it was the only Compose code in the shared set. | Not recorded. | — |
| (D) Cross-module test doubles live in `core/mesh/src/testFixtures`. | One copy of the in-memory cipher and the JVM `android.util` shims, shared by both modules' tests. | Not recorded. | — |
| `:core:mesh` uses the phone's cryptography bundle. | Its tests run on the Tink version the phone ships. | Not recorded. | The watch moves from Tink 1.8.0 to 1.23.0. |
| `clientRewriteContractTest` depends on every module holding protocol tests. | The protocol and Noise vectors it promises must still run. | Not recorded. | Each new module with protocol tests must be added. |
| The Robolectric repository override moves to the root build. | Relocated Robolectric tests hit an endpoint that returns 403 to cold CI runners. | Not recorded. | — |

### Phase 7: chat decomposition, the Nostr cycle, `:core:nostr` and `:core:chat`

**What changed.** 7a/7b: `ChatViewModel` and the private chat each expose one UI state and take
actions. 7c: the cycle between Nostr and the chat session was broken in place. 7d: `:core:nostr`
and `:core:chat` were extracted.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) Break the cycle into two modules: `:core:chat` → `:core:nostr` → `:core:mesh`. | Nostr never touches `ChatState` or a manager. | One module that keeps the cycle. | — |
| (D) `ChatUiState` is grouped into timeline, header, composer and dialog sub-states. | Children get only their sub-state, so a header change does not re-run the timeline. | A flat state; keeping the separate flows. | — |
| (D) One-shot effects return to the screen as `ChatEvent` over a channel. | Not recorded. | Callbacks inside actions. | — |
| (P) Nostr delivers private messages through a suspend `PrivateMessageInbox` port. | The delivery ack is sent only after the message is admitted. A fire-and-forget event cannot wait for that. | An event flow. | — |
| (P) The Nostr HTTP hook fails closed. | A default plain client would send relay traffic around Tor. | A fallback client. | A missing install throws. A unit test pins this. |
| (P) Hooks follow the `TransportToggles` pattern: installed at app start for a Kotlin `object`, a Hilt binding for an injected consumer. | Not recorded. | Not recorded. | — |
| (P) Session start-up moves to a retained `ChatSessionStartup`. | The ViewModel can then be scoped to its navigation entry without restarting the session. | Keeping it in the ViewModel. | `start()` must stay idempotent. |
| (P) No behaviour change in 7c/7d; call order and threading kept. | Not recorded. | Not recorded. | Messaging touched; owed Mesh Lab and a manual Nostr check. |
| (D) The 32 notification and verification strings and `ic_notification` move into `:core:chat`, in every locale. | Not recorded. | Keeping `NotificationManager` in `:app`. | The notification title reads the app label from `ApplicationInfo`. |
| Lock state is written with real build tasks, not `dependencies`. | `dependencies` misses AGP's `androidApis` configuration. | Not recorded. | — |

### Phase 7.5: destinations take the form of their content

**What changed.** The peer list, private chat and location notes became screens. Leading back
arrows, slide transitions, and the rule that a sheet never sits on a sheet. Decided by the
developer after a design review.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Peer list, private chat and location notes become screens. | A conversation list, a full conversation and a feed with input are screens on Android. | Keeping them as sheets. | User-visible. Needs before/after screenshots in the PR. |
| Location channels stays a screen. | It is a parent, not a leaf: text input, a picker launched for a result, a trip to system settings. | A sheet. | — |
| Security verification and chat user stay sheets. | Fixed, input-free leaves. | Not recorded. | — |
| A sheet never opens on another sheet. | Predictive back cannot render a sheet as the page behind. The notification path broke this. | Not recorded. | Documented on `SheetSceneStrategy`; a device test walks every opener. |
| (D) Peer list → private chat pushes; Back returns to the list. | List to detail. | Replacing down to chat. | Every other opener still returns to chat. |
| Horizontal slide for pushed screens, fade only for the onboarding-to-chat swap, from the existing motion tokens. | Matches the predictive-back gesture. No new magic numbers. | Not recorded. | — |
| A leading back arrow on every full screen. | System Back and predictive back do the same thing. | The trailing close button. | — |
| Renames come first, as their own commits. | Keeps behaviour diffs reviewable. | Not recorded. | — |
| Live voice visibility is a claim held by the screen on show (`LiveVoiceVisibility`). | Chat leaves composition under a full screen, so its effect stopped running. | Chat's composition effect. | Watch keeps the single-slot calls. |
| Channels pushes notes; Back goes notes → channels → chat. | Both are screens now; found in phone testing. | `replaceCurrent`, a carry-over from sheets. | — |
| The chat stack is saved when onboarding takes over and restored after. | Turning Bluetooth off mid-session reset the stack and lost the open conversation. Found in phone testing. | Keeping the plain reset. | Back from onboarding still cannot reach chat. |

### Phase 8: feature modules

**What changed.** New modules `:core:designsystem`, `:core:designsystem:phone`, `:core:tor`,
and `:feature:{share,settings,location,chat}`, each with an `:api` module. Features register
their destinations through Hilt. Wear adopted the shared design system base.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) A module boundary only where the imports already show one. | No separation for its own sake. | Not recorded. | The chat cluster stays one module; its peer list reuses the message list. |
| (D) Generic components go to the design system; domain-aware chat rendering moves with `:feature:chat`. | Not recorded. | A `:core:ui` module. | — |
| (D) Strings move with their feature, in every locale. A string two modules use goes to their lowest common module. | Not recorded. | Duplicating strings. | — |
| (D) Routes live in per-feature `:api` modules. Features never depend on features. | Not recorded. | Routes in `:app`. | Enforced by `FeatureDependencyRulesTest`. |
| (D) Tor gets its own module, `:core:tor`. | Not recorded. | A top-level `:tor`. | Arti native libraries moved; bytes identical, `verify-checksums.sh` passes. |
| (D) APK sharing and the hotspot are one feature, `:feature:share`. | Not recorded. | Not recorded. | — |
| (D) The design system is split: a shared base (palette, motion, peer colors, font; no Material3) used by phone and Wear, and a phone part with Material3. | Wear keeps its own Wear Material3 theme, built from the base tokens. | Wear not adopting the design system. | Wear's duplicate palette, peer-color hash and fonts are deleted. |
| (D) Wear builds against the same Compose BOM as the phone (1.11.4). | The shared base put the BOM on Wear's runtime while Wear compiled against 1.9.0. | Not recorded. | — |
| (D) Wear colors peers by `PeerIdentity.stableKey`, as phone and iOS do. `PeerIdentity` moves to `:core:domain`. | One peer color everywhere; renaming no longer recolors. | Wear's nickname + peer ID hash. | The only user-visible change of Phase 8. Phone colors pinned by `PeerColorsTest`. |
| (D) `:core:tor` depends on no project module. Route changes go out on `ArtiTorManager.routeChanges`, carried by a required member of the fail-closed `NostrHttpClients` port. `NostrRelayManager` resets its own sockets. | Tor stays a leaf; a missing wiring cannot fail open. | Tor calls Nostr directly (wrong direction); Nostr depends on Tor (couples it to Arti); an app-registered hook (fails open if missing). | The relay reset is now asynchronous. `OkHttpProvider.reset` still runs first, so no new socket uses the old route. A failed reset is contained and logged. |
| (D) Push Phase 8 once, at the end. | The maintainer gets one handover after Phases 8–11. | Per-task pushes. | Fork CI has not built Phase 8 yet. |
| (R) `:app` hands `versionName`, `isDebug` and the release-certificate pin to `:feature:share` as `AppBuildInfo`, provided by Hilt. | A library cannot read the app's `BuildConfig`. The pin keeps one source. | Re-declaring the pin in a library. | `ApkDownloadWorker` reads it through a Hilt entry point, because WorkManager builds workers by class name. |
| (R) `:api` modules may use external Compose runtime/ui libraries. | `ShareAppSection` needs `@Composable` and `Modifier`. | Not recorded. | — |
| (R) Debug reads Wi-Fi Aware and the mesh service through `TransportDiagnostics` / `MeshDiagnostics`, declared in `:feature:settings`, bound in `:app`, with plain fields. | The settings feature must not see `:app` types. | Moving `BluetoothMeshService` out of `:app` (out of scope). | Wide: about 20 members. Debug still opens the mesh service once per visit. Phase 10 is the natural point to slim it. |
| (R) Upstream's `PerformanceTestRunner` is not taken. | Its bare `Application` stops every Hilt `MainActivity` device test from launching. | Taking it. | Its test, `ParticipantListInstrumentedTest`, moved to `:feature:chat`'s device tests. A library test APK runs under a plain `Application`, which gives the same isolation without a custom runner. |
| (R) Features read the back stack through `Navigator`; no feature injects `AppNavigator`. | `Navigator` gained a read-only `backStack`. Mutation still goes through its methods; seeding and restoring stay with the host. | The chat feature injecting the concrete `AppNavigator`. | — |
| (D) Mesh Lab deferred. | No second Android phone. | Not recorded. | Owed before handover. |

Phase 8 also left lint unchanged in substance. The `MissingTranslation` count fell from 242 to
175 only because `:feature:share` has no translations at all. Its 67 strings were already
untranslated, and lint does not report a module with only `values/`.

### Phase 9: onboarding

**What changed.** Onboarding was untangled in place, then moved. Its state machine left
`MainActivity`. `:core:readiness` reads Bluetooth, location, permission and battery status for
both onboarding and the app. `AppStartupGate` in `:app` decides between onboarding and chat, and
runs app startup. `OnboardingViewModel` drives the six step screens in MVI form. Everything
except the gate then moved to `:feature:onboarding` and `:feature:onboarding:api`. The five
status managers, `OnboardingCoordinator` and `MainViewModel` are gone. Characterization tests
locked the old paths first. The (D) rows come from the phase spec.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) Untangle everything first, then move. | About 300 lines of the state machine were private functions in `MainActivity`, its state sat in `MainViewModel`, and five helpers held the Activity. Moving that as it was would move the tangle. | Moving first. | 16 commits. Restructure commits change no behaviour beyond F1, F3, F6 and F11. |
| (D) Full MVI now: one `OnboardingUiState`, actions in, one-shot effects out. | Phase 10 has nothing left to do for onboarding. | Not recorded. | — |
| (D) Tests first, phone at the end. | Characterization tests lock today's paths before anything moves. Emulator device tests fake readiness, so no test reads a real location. | Not recorded. | One fresh-install phone run is owed (section 5). |
| (D) System dialogs launch from the feature's own composition. No helper holds an Activity. | The status managers held the Activity to open dialogs. | Not recorded. | The five launchers are registered once, in the route composable, not per step: a launcher's result is keyed through `rememberSaveable`, and a step screen leaves composition when the step changes with its dialog open. |
| (D) Fix the bugs and list them, in two kinds. Bugs the restructure removes are not reproduced first. Real fixes are test-first, one commit each. | Reproducing a bug the new design makes impossible costs effort and proves nothing. | Not recorded. | Each real fix can be reverted alone. See "What Phase 9 fixed". |
| (D) Onboarding is a separate flow that ends. Its view model is scoped to the `OnboardingRoute` entry. | Nothing about it survives into chat. | Not recorded. | — |
| (D) Device readiness lives in a module both use, `:core:readiness`. | Onboarding reads it to pick a step. The app gate reads it to send the user back to onboarding. | Not recorded. | Wi-Fi Aware support comes in through `WifiAwareAvailability`, a port `:app` binds. Its receivers now register with `RECEIVER_NOT_EXPORTED` (Q21). No behaviour change: both location actions are protected system broadcasts. |
| (D) One destination, steps inside. | Back behaves as before. | Per-step routes (rejected again, see Groundwork). | — |
| (D) Review mode: hybrid, as in Phase 8. | Not recorded. | Not recorded. | Each task: unit/lint/APK gate, the emulator device suite, a read-only Codex review. One whole-branch reviewer at the end. |
| (D) The spec and plan are written in one session and run in a fresh one. Push once, at the end, after asking. | Not recorded. | Not recorded. | — |
| (D) The reproducible container build is deferred to the end of the refactor, after Phase 11. | Fork CI cannot run: GitHub answers "Actions has been disabled" with every setting enabled. | Per phase. | The last container build was at the end of Phase 7.5. |
| (R) `DeviceReadiness` is bound in `:app`, once per build type: the system reader in release, an overridable one in debug (R1). | Device tests fake readiness through the debug-only `ReadinessOverride`. A binding inside `:core:readiness` could not be swapped per build type. | Binding it in `:core:readiness`. | Release sources never name the override; checked by grep. |
| (R) The session Bluetooth skip is `BluetoothSkipSession`, Activity-retained, in `:core:readiness` (R2). | It must survive rotation but not the Activity finishing or process death, as before. The view model that reads it moved to a feature, which cannot see `:app`. | Keeping it in `:app`. | — |
| (R) The spec's "denial → error → Try again" device test became "a missing required permission shows the explanation" (R3). | A denial needs a tap on a system dialog, which the plan forbids. The error and Try again path is covered by JVM tests. | The spec's test. | — |
| (R) `DeviceReadiness` exposes `snapshot()` and a `systemEvents` flow, not a `StateFlow` with `refresh()`. `nextStep` lives in `:core:readiness` (R4). | The app gate needs `nextStep`, and a gate in `:app` cannot depend on a feature. The spec left the shapes to the plan. | The spec's `StateFlow` + `refresh()`; `nextStep` in the feature. | — |
| (P) The next step is one pure function, `nextStep(readiness, phase, skipped)`. | It holds both orders in one place: first launch (permissions → background location → Bluetooth → location → battery) and returning (Bluetooth → location → battery → permissions → background location). It is unit-tested exhaustively. | The old split across `check*`/`proceed*` functions. | — |
| (P) The view model handles one action at a time, through a queue. | The parallel proceed chains (Q2) cannot exist. | Not recorded. | The 200 ms and 100 ms delays now hold the queue. A tap or radio change arriving during them is handled after. |
| (R) The `OnboardingHost` port, in `:feature:onboarding:api` and bound to `AppStartupGate`, ended with five members: `initError`, `onboardingFinished()`, `suspend retry(): Boolean`, `takeBluetoothTurnedOff()`, `locationServicesEnabled()`. Primitives only. | `takeBluetoothTurnedOff` keeps Bluetooth-off-in-chat (T12) showing the Bluetooth step, as before. `locationServicesEnabled` lets the view model start Wi-Fi Aware, which lives in `:app`. `retry` suspends and says whether onboarding is still needed, so Try again stays on onboarding (F8). | The spec's three members. | Two members beyond the spec. Asked of the final reviewer. |
| (R) App startup runs on the gate's Activity-retained scope, through an `AppInitializer` port `:app` implements (F2). | A rotation neither cancels nor repeats it. | `MainActivity`'s `lifecycleScope`. | The mesh delegate still attaches before the transports start. The foreground-service start and the launch intent now run at READY, about 500 ms later than before. Startup libraries get the application context instead of the Activity. |
| (R) Startup timing changed slightly (Task 5). | The gate decides directly from readiness. | Not recorded. | A returning user who is ready skips the old 200 ms step. When onboarding is needed, its step appears at 500 ms instead of about 700 ms. A returning user sees chat throughout. |
| (P) Every delay is kept: the 500 ms check, 200 ms, 100 ms, and the 1000 ms and 500 ms in startup. | Keep them unless a fix removes one. None did. | Not recorded. | — |
| (R) `LocalOnboardingContentPadding`, a `CompositionLocal` in `:feature:onboarding:api`, hands `MainActivity`'s Scaffold padding to the onboarding entry. | The entry now registers from the feature. The local passes the same padding object, so the layout is the same. | Reading window insets inside the feature (Material3 lint flags the unused Scaffold padding); replacing the Scaffold. | Insets checked by eye on the emulator. Owed on a phone. |
| (D) Every persisted key and preferences file name stays byte-identical. | An upgraded user must not see first-launch onboarding again. | — | `DeviceReadinessKeysTest` writes the names as literal strings. |
| The 60 onboarding-only strings move with the feature, in all 34 locales. Three shared strings stay in `:core:designsystem:phone`. | Phase 8's string rule. | — | Per-locale totals equal the baseline. 13 existing `MissingTranslation` entries now show under `:feature:onboarding` instead of `:app`. |
| `FeatureDependencyRulesTest` checks that `:core:readiness` depends on no feature and not on `:app`. | Keeps readiness below both its users. | — | — |

Removed as dead code, with no behaviour change: the unreachable "Permission" branches (Q3), the
unused `onRetry`, `skipFlow` and coordinator functions (Q11), a truncated log message (Q12), and
the permission cards computed during composition (Q22).

#### What Phase 9 fixed

"Removed by the restructure" means the new design makes the bug impossible; it was not
reproduced on the old code first. "Real fix" means a test-first commit that changes one
behaviour.

| # | Before (what a user saw) | After | Commit | Kind |
|---|---|---|---|---|
| F1 | Turning Bluetooth on could start the mesh more than once and reopen the chat from the notification the app was launched with. Turning Bluetooth off during startup could be ignored. | Startup runs once per entry, and the launch intent is handled once, across rotation too (the marker is saved with the instance state; found in the final review). A Bluetooth or location drop during startup sends you to the matching step when startup ends. | 7cee6395, 3c9fe630 | Removed by the restructure; rotation half a real fix |
| F2 | Rotating the phone in the first 1.5 s of startup ended on "Failed to initialize the app". | Startup carries on across the rotation, with no error. | 19d45389 | Real fix. Phone check owed |
| F3 | Rarely, a stuck spinner with no buttons after a permission dialog, when the Activity was recreated during a 100 ms or 200 ms delay. | The step is recomputed from readiness whenever onboarding opens, and the delays survive recreation. | 9236e212 | Removed by the restructure |
| F4 | Phones without Bluetooth started on an error screen on every launch. | They get the existing "Bluetooth not supported → Continue" step. | aae226d3 | Real fix |
| F5 | An interrupted permission dialog could mark onboarding done, then fail with "permissions were revoked". | Onboarding is marked done only once the required permissions are granted. Otherwise it returns to the permission explanation. | ecf088a4 | Real fix |
| F6 | The battery screen came back after you skipped it, when a new optional permission was asked. The Bluetooth skip was ignored on the same path. | Both skips are honoured after the permission step. | 9236e212 | Removed by the restructure |
| F7 | Declining the system battery dialog still moved on, and the screen came back on the next launch. | The status is re-read after the dialog. Still optimized means you stay on the battery step. | 94407a08 | Real fix |
| F8 | Chat flashed for about 0.5–0.7 s before the first-launch explanation. Try again flipped to chat for 0.5 s. | First launch and Try again stay on onboarding, with its spinner, until the step is known. A returning user still sees chat at once. | edea11f0 | Real fix |
| F9 | After Bluetooth or location was turned off while the app was away, the foreground service might not take over DM notifications. | The mesh delegate is detached on every pause, and reattached on resume during startup or once ready. | d69c770b, 86f88e16 | Real fix. Phone check owed |
| F10 | Suspected: the battery settings intents might not resolve on API 30+ without a `<queries>` element. | Nothing changed. The battery request intent resolves on the emulator at API 37. OEM builds were not tried. | — | Not reproduced |
| F11 | Turning Bluetooth off during first launch jumped from the permission explanation or dialog to the Bluetooth step. | Radio changes during the permission step are applied when it ends. | 9236e212 | Removed by the restructure |

## 4. Upstream merges during the refactor

| Merge | Upstream content | Where it went |
|---|---|---|
| Phase 2 start | 26 commits. | Merged clean. |
| End of Phase 7 | Only the weekly relay-data bot commits. | Merged clean. |
| End of Phase 7.5 | Wear ongoing activity, icons, Wear 0.1.4. | One conflict, in `gradle/verification-metadata.xml`. Resolution checked by hand. |
| During Phase 8: 2.0.3 / Wear 0.1.5 (#977, #979, #981) | Chat workload bounds under heavy activity; geohash presence UI stalls. | See below. |
| Phase 9 | None. Upstream had not moved. | — |
| After Phase 9 (#985, #986, #989, #991) | Cashu display amounts; missing geohash channel participants; onboarding margins and colors; a Basque translation. | See below. |
| After Phase 10a: 2.1.0 / Wear 0.1.6 (65 commits, to `eed88190`) | Spam filter; panic wipe confirmation and outcome; geohash block by pubkey; payload caps; hex, Bech32, GCS and verification-QR input hardening; fragment injection guard; backup exclusions; release scanner fix; Wear UI polish and app menu. | See below. |

The 2.0.3 merge re-homed each change to where its subject now lives:
- `:core:mesh`: the batched identity cache, packet processor and gossip sync load bounds, the
  relay manager, public timeline retention in `AppStateStore`, and the new
  `PeerConversationReconciler` (public, because `:core:chat` calls it).
- `:core:nostr`: `GeohashRepository` coalesces presence refreshes on an owner scope.
- `:core:chat`: `ChatViewModel`'s `updateReactiveStates` change went to `ChatSessionStartup`.
  `GeohashViewModel`'s change went to `GeohashSession`. `MeshDelegateHandler` got the conflated
  peer reconciliation. Upstream's sheet-peer block was not taken, because Navigation 3 removed
  that state.
- `:feature:chat`: the chat UI and participant list changes.
- New tests sit with their subjects. Upstream's instrumented participant-list test later moved
  to `:feature:chat`'s device tests.

The merge after Phase 9 re-homed:
- `:core:nostr`: the new `GeohashParticipantStore`, ported onto this branch's
  `GeohashRepository`, which publishes its own flows instead of writing `ChatState`.
  `NostrBackgroundRuntime.participants` is public, because `:core:chat` reads it.
- `:core:chat`: `GeohashSession` and `NostrBackgroundEventProcessor` share that store. Upstream's
  `GeohashParticipantTrackingTest` moved here, since it drives the processor; its one
  assertion on `sectionGeohashPeople` (internal to `:feature:chat`) became an assertion on
  `teleportedGeo`, the input that function sections by.
- `:feature:onboarding`: the margin and color changes merged without conflict.
- The Basque `strings.xml` was split across the eight modules that own its strings. Its
  `fingerprint_no_peer` was dropped: that string was deleted as unused in Phase 4.

The 2.1.0 merge re-homed:
- `:core:mesh`: the new `spam` package (`SpamFilter`, its preferences and tuning). Its only
  dependencies are the `:core:domain` model and `SharedPreferences`, and every caller (Nostr,
  chat, settings, the app's admission and the watch) already sees `:core:mesh`. The watch gets
  it through its `:core:mesh` dependency instead of upstream's shared-source include, and keeps
  the default (on), as upstream intends. `PacketPayloadLimits` and its tests, and upstream's
  `HexStringTest`, `VerificationServiceTest` and `PacketPayloadLimitsWireContractTest` also
  went here (the last two read `internal` helpers).
- `:core:nostr`: the spam check in `GeohashMessageHandler`. Upstream's spam and nickname-lookup
  tests now collect from the handler's `addChannelMessage` and build `GeohashRepository` with a
  nickname lambda instead of `ChatState`.
- `:core:chat`: the panic outcome (every wipe stage reports, success is announced only when all
  confirm, the spam filter's memory is wiped) went into `PanicClear`; `GeohashSession.panicReset`
  returns whether the identity was wiped. The panic outcome strings live in `:core:chat`.
  Upstream's blocked-geohash-sender test was rebuilt on `ChatPrivateMessageInbox`, next to this
  branch's refused-inbox test.
- `:feature:chat`: the panic confirmation dialog (a `rememberSaveable` flag in `ChatScreen`, not
  ViewModel state; a candidate for Phase 10b), the block-by-pubkey in `ChatUserViewModel`, the
  network sheet's QR tint, and `MessagesListFollowTest` (with Compose test on the JVM classpath).
  `QRCodeAnalyzer` is public, because upstream's release-build scanner test in `:app` constructs it.
- `:feature:settings`: the spam filter switch goes through the About MVI path
  (`AboutSettingsStore.spamFilterEnabled`, `AboutAction.SpamFilterToggled`, AA17), with a test.
  The debug section moved with the debug sheet.
- `:app`: the instrumentation runner stays `AndroidJUnitRunner` unless `-PreleaseUiTests=true`
  selects upstream's `ReleaseScannerTestRunner`. Upstream's `PerformanceTestRunner` is still not
  taken (decision R).
- The 35 locale files: the new strings went to the module that owns each one.
- Wear: upstream's reworked screens were taken whole, then this branch's edits re-applied (theme
  from `com.bitchat.android.ui.theme`, peer colours keyed by `PeerIdentity.stableKey`).
- Upstream's verify-sheet reopen flag was already gone: Navigation 3 stacks the sheet.

PRs #912 and #913 were open upstream at last record, and this branch contains both. If they
merge first, expect a Back-handling conflict in `MainActivity`. Keep this branch's handler
(`pendingBackAction`).

## 5. Known gaps and follow-ups

### Pre-existing upstream bugs found, not fixed

- **Fixed upstream since (#1007, merged in the sync after 10c-3):** `MeshForegroundService.start` now catches the refusal.
  `BootCompletedReceiver` called `MeshForegroundService.start` with no guard, so the system refusing
  the foreground start throws `ForegroundServiceStartNotAllowedException` and crashes the process.
  Seen once on an emulator during Phase 10c-2a-2b, some time after a boot, and not reproduced. What
  delivered the broadcast that late is not established. A normal reboot auto-started correctly.
- `NostrRelayManager.resetAllConnections` iterates `relaysList` unsynchronized. A concurrent add
  or remove can throw `ConcurrentModificationException` mid-reset. `disconnect()` has already
  closed every socket, so the effect is relays left disconnected, not sockets on the old route.
  A candidate small upstream PR.
- The first Nostr private message sent after Bluetooth is turned off can be lost with no error.
- A Nostr private message's sender shows as "anon" instead of the favourite's nickname
  (`displayNameForNostrPubkeyUI`).
- `BluetoothPacketBroadcaster` spins at about 100 failures a second while Bluetooth is off.
- The favourite star has no accessibility label.
- Two geohash counts carry the same "N people" label but count different sets.
  The chat top bar (`ChatHeader`) shows `geohashPeople.size`, which includes heartbeat-only
  identities with no nickname; the channel list's counts are similar. (`MeshPeerListScreen`
  computed `geohashPeople.size` but never showed it; Phase 10b removed that dead count.) `GeohashPeopleList` titles its list with
  `displayedPeople.size`, which drops bare `anon` identities, so only people who chatted count.
  Found in a phone check after the post-Phase 9 upstream merge; upstream behaves the same. A fix
  is a labelling decision for upstream (for example "N online · M active"), not a refactor
  change.
- The location channels notes card returns to "Loading notes…" every time a jittering GPS fix crosses a building cell, because each cell change resubscribes the nearby notes. A follow-up candidate for the notes work (seen in Phase 10b's phone check; not a 10b change).
- Turning live location off can leave a relay socket open (upstream 2.2.0, globe). The globe
  opens its relays through `NostrRelayManager.connectToRelays`, which adds them to the one shared
  `nonLiveRelayUrls` set. If a live-location channel later routes to the same relay, it is never
  tagged as live; when the globe closes, `releaseRelays` keeps it because it is routed, so it
  stays non-live. Revoking live location then closes the channel's subscriptions but not that
  socket. Separately, `GeohashPickerActivity` opens the globe on the user's coarsest location
  channel and `GeohashActivityMonitor.focusOn` then opens the relays nearest that region, outside
  the live-location gate, so the relay left open can be one chosen from the user's region.
  Confirmed on an emulator with fake sockets and synthetic relays; it needs the globe to touch the
  relay before the live channel does. The fix for the second part is small (focus only after the
  user moves the globe); the first needs per-owner relay tracking. Not fixed here, to keep the
  refactor behaviour-preserving.
- Reverse geocoding bypasses Tor on devices with Google Play Services (issue #1023).
  `GeocoderFactory` picks the platform `Geocoder` whenever one is present, ignoring the Tor
  setting. Play Services then sends the lookup from its own process over the real network: the
  exact coordinates (the raw live fix, or a bookmark's cell centre), the package name
  `com.bitchat.droid`, and the device's Google Services Android ID in the `x-goog-spatula` header.
  Confirmed on a rooted emulator by intercepting only the geocoding host, with synthetic bookmarks
  and no device location. The fix is to use OpenStreetMap over Tor when Tor is on, and to geocode
  the cell centre, never the raw fix. Left for a separate change.
- The iOS client files Nostr private messages in a separate thread.
- Old public messages come back after a panic. A nearby peer's gossip sync sends them to the new identity, and they appeared on the emulators about two seconds after the wipe, on the base branch too. Panic cannot erase what other devices hold, but someone under duress sees them return. Not fixed, and worth a decision.
- Each panic leaves the other device holding about one more connection until it reaches its 8-slot limit. Seen on emulators over simulated Bluetooth, with the same counts on the base branch. Reconnecting took 3 s for the first four panics and about 30 s after that, with one miss of 150 s on the refactor branch. That single miss is not enough to call it a difference. Not known on real radios.
- A small private file sometimes never arrives. All fragments reach the receiver, but `FragmentManager` logs that it cannot decode the reassembled packet, while the sender reports success, so nothing tells the person. Mesh Lab's `media_private` hit this on about a quarter of its runs with 1 KB files, in 3 of 11 runs on this branch and 2 of 10 on the base branch, always on emulators. The logged failures (one on this branch, two on the base branch) match it. Root cause not established. The protocol and fragment code are unchanged here.
- `MessageSendingTest.aCommandRunsInsteadOfBeingSent` assumes no peer is in range, so it fails on any device that can see one. This is the likely cause of the failure earlier gates called a flake.
- `FragmentManagerTest > test reassembly` flakes. Root cause not established. Candidates: an
  unseeded `Random`, and a cleanup loop that nothing cancels.

Found in the Phase 10b phone check and fixed there: a swiped conversation row never slid back, and the Undo snackbar was missing on most deletes as a result.

The upstream bug "recreating the Activity in the first seconds of launch lands on the error
screen" was fixed in Phase 9 (F2).

### Onboarding quirks left as they are (Phase 9)

Each would change onboarding UX or startup, which Phase 9 ruled out of scope.

- **The mesh starts before onboarding (Q15).** For a returning user with Bluetooth permissions,
  the foreground service started in `onStart` starts the transports before the checks run or
  the delegate is attached. The `onCreate` call to `startMeshForegroundServiceBestEffort` can
  never succeed. Changing it touches foreground-service power and transport start, so it needs
  Mesh Lab.
- **Skips cannot be undone (Q16).** The battery and background-location skips are permanent,
  with no UI to undo them. The Bluetooth skip is lost on process death.
- **The partial-denial warning is built but never shown (Q17).**
- **"Check again" on the background-location step is silent (Q18)** when the permission is still
  not granted.
- **Open settings from the error step does not re-check on return (Q19).** The user must press
  Try again.
- **The "location not available" screen has no buttons (Q11).**
- **Bluetooth off while in chat (T12) ignores the Bluetooth session skip and the debug
  `ble_enabled` switch.** The resume re-check (T42) honours both. Kept as it was, and locked by
  `readyThenBluetoothOff_goesToOnboarding`. Regression found in the final review and fixed in
  2bf48d29: if Bluetooth came back on in the background before onboarding opened, the stale
  flag stranded the Bluetooth step on a spinner. The flag now counts only while Bluetooth is off.
- **`handleBluetoothDisabled` keeps a `NOT_SUPPORTED` → error branch.** F4 removed the path at
  open. This one is reachable only through a Bluetooth-off event on a device with no adapter.

### Test gaps

- The project cannot host Compose unit tests: Robolectric's maximum SDK is below the target SDK.
  Back dispatch and screens are covered only by device tests, which CI does not run.
- `VerificationDestinationTest` flakes about once in eight full runs. Pre-existing timing.
- `EveryRouteHasAnEntryTest` builds the installers by hand, so it does not catch a feature
  missing from the Hilt graph or a route missing from its list.
- The device-suite step must rebuild the test APK from a clean outputs directory. A failed
  androidTest compile once let a stale test APK pass.
- Pause → resume during startup (INITIALIZING) has no automated test. The condition that
  reattaches the mesh delegate lives only in `MainActivity`.
- In the onboarding device tests, real radio events still merge with the faked ones. A real
  Bluetooth or location toggle mid-test could interfere.

### Smells left on purpose

- `WifiAwarePeers` carries a non-peer `enabled` flag.
- `ic_notification` lives in `:core:domain`, the lowest module chat and share both see.
- `TransportDiagnostics` has about 20 members. The real fix is moving `BluetoothMeshService`
  out of `:app`.
- `AppStartupGate.forcePhase` and `ChatSessionMesh.replace` are test seams in main sources,
  used only by tests (see "Phase 11: audit").
- Two binding modules with near names: `ReadinessBindingsModule` (main, Wi-Fi Aware only) and
  `ReadinessBindingModule` (debug and release, `DeviceReadiness`).

### Deferred items

- Parked from Phase 7.5: the mesh graph's settle rule, voice read-marking while backgrounded,
  the Wear `DmScreen` unconditional clear, and a narrower pop for the verify deep link.
- Revert the CI trigger line for the `refactor` branch when it merges.

### Owed before handover

The real-device checks are the developer's, run once at the end:
- **Mesh Lab on two physical Android phones.** Phases 4–7 moved the private chat path, the mesh
  delegate wiring, the send path and the transport files. Phase 8 moved Tor. Phase 9 moved app
  startup and where the mesh delegate attaches and detaches. Phase 10c moved every mesh class
  into the identity scope.
- **The watch on a device**, including the visual check of Phase 8's design system change.
- **Voice notes** between real phones.
- **The rest of the Phase 9 phone run:** Bluetooth off and on in chat restores the open chat and
  reopens nothing (F1); a DM sent from a second device while the app is in the background
  arrives as a notification (F9); the onboarding screens' insets. F8, F7 and recreation during
  startup passed on a real phone (see "Real-phone checks" in Phase 11).
- **Manual checks on a real device:** a public message, the peer list, the user sheet, and the
  geohash picker and globe. These are blocked on the emulator by the rule against using
  location data.

Also owed:
- **Before/after screenshots** in the handover PR for the Phase 7.5 screens and the Wear peer
  colors, with synthetic data only.

A manual cross-platform messaging check on physical devices passed at the end of Phase 7.5:
Bluetooth public and private messages with read receipts, a mutual favourite, and Nostr private
messages both ways with Bluetooth off. That covers the code up to that point, not Phases 8
or 9.

## 6. Phases 10–11

Phase 9 moved to section 3 when it finished. What it left open is in section 5.

### Phase 10: MVI on every screen (10a, 10b, 10c-1, 10c-2a-1, 10c-2a-2a, 10c-2a-2b, 10c-2b and 10c-3 done)

#### MVI shape

Every screen follows `ChatUserViewModel`'s shape.

- The ViewModel exposes `val uiState: StateFlow<XUiState>` and `fun onAction(action: XAction)`. Only if it has one-shot work does it add `val events: Flow<XEvent>`, built from `Channel<XEvent>(Channel.BUFFERED).receiveAsFlow()`.
- `XAction` and `XEvent` are `sealed interface`s; argument-free members are `data object`.
- `XRoot` gets the ViewModel (`hiltViewModel()` default parameter), collects with `collectAsStateWithLifecycle()`, observes events with `ObserveAsEvents` (in `:core:designsystem:phone`) and owns launchers, intents, clipboard and lifecycle effects. `XScreen(uiState, onAction, modifier)` is pure.
- Navigation leaves through events, which the Root turns into the lambdas the Nav3 entry passes.
- A ViewModel takes its dependencies in its constructor; singletons are reached through a feature-side port.
- Screen tests run in the feature's own `src/androidTest`, with `createComposeRule()` and no Hilt.


Planned scope: every screen exposes one UI state and takes actions, as the chat screen and
private chat already do. Phase 10 is in three parts. **10a** (done, branch
`phase10a/mvi-small-screens`): About, Debug, Location Notes, Hotspot, the APK share section,
Onboarding's naming, and a slimmer `TransportDiagnostics`. **10b** (done, branch
`phase10b/mvi-peers-channels`): the peer list and the location channels screen. **10c** (own spec, four runs): the project's singletons become Hilt
classes. **10c-1** (done, branch `phase10c1/hilt-tor-nostr`): `:core:tor` and `:core:nostr`.

#### Phase 10a decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| One shape for every screen: `uiState`, `onAction`, and `events` only where there is one-shot work (see "MVI shape"). | The maintainer reads one pattern; logic that hid in composables is now unit-tested code. | Keeping each screen's own shape. | Three more ViewModels to maintain. |
| Root plus a stateless Screen, with light and dark previews built from fake UI states. | Screens can be tested and previewed without Hilt, singletons or `LocalContext` lookups. | Screens that take the ViewModel. | Preview fakes live in `src/main`, marked `internal`. |
| Characterization tests first, then the move, then ViewModel tests; one device test per screen. | The restructure commits change no behaviour; a failing test shows where one did. | Writing the tests after the move. | The Debug ViewModel tests were written after the code, not red first. |
| Screen tests live in each feature's own `src/androidTest`. `AGENTS.md` now says `:app` keeps only the app-wiring device tests. | `XScreen`, `XUiState` and the preview fakes stay `internal`. | Keeping every device test in `:app`. | `gate.sh` has one install-and-instrument block per feature APK. |
| `ObserveAsEvents` moved to `:core:designsystem:phone`, same package. | Settings, location and share all need it; no import changed. | One copy per feature. | It collects only while STARTED, so an event sent while the screen is stopped waits (this removes Q-K2). |
| Singletons are reached through feature-side ports with adapters mostly in the features (only `TransportDiagnostics` and `BackgroundMeshSetting` are in `:app`), or a `@Provides` that returns the existing instance. No singleton class changed. | ViewModels take everything in their constructor and tests can fake it. | Converting the singletons now (that is 10c). | The adapters must keep working when 10c swaps `getInstance()` for injection. |
| Hotspot stays a separate Activity, with `HotspotRoot` and a stateless `HotspotScreen`. The APK path comes from `SavedStateHandle`. | It is launched by intent with an APK path and has its own task semantics. | Making it a Nav3 route. | `HotspotScreen` takes two extra defaulted parameters (`showPermissionRationale`, `onCopy`) so the clipboard and permission request stay in the Root. |
| Hotspot test seams first: factories for `HotspotManager` and `ApkWebServer`, the 10 s fallback driven by `ShadowLooper`. | The teardown lease logic was untestable. | Testing through the real radio classes. | None. |
| `TransportDiagnostics` and `MeshDiagnostics` slimmed from 23 to 13 members: one Wi-Fi Aware status flow, one discovered-peers map, one `snapshot()`, `setGattServerRunning` and `setGattClientRunning`. The old total is 23, not the 24 the survey counted. | The wide port was the thing Phase 8 flagged. The mesh calls underneath are the same calls. | Moving `BluetoothMeshService` out of `:app`. | `MeshSnapshot.assemble` is a public function in the port file so the app and the tests build the snapshot the same way. |
| `DebugSettingsManager` mirrors stay; the 1 Hz poll still writes them and the lists still read them. | Refresh timing is unchanged (QD12, QD13 listed). | Reading the port's flows directly in the lists. | Two copies of the same data. |
| The relay graph's mode and series moved into `DebugSettingsViewModel` (`RelayGraphModeSelected`, kept in `SavedStateHandle` like the old `rememberSaveable`). | The sampler needs the mode on every tick under B2. | Mode stays UI-local, as the plan said. | `GraphMode` and `RelayGraphSeries` are now public. |
| `CombinedStateFlow` in `app/.../di/` derives a StateFlow without a scope. | The adapter has no scope, and `stateIn` could serve a stale first value. | `stateIn` with an app scope. | It opts into `ExperimentalForInheritanceCoroutinesApi`; a coroutines bump may change it. |
| Effects tied to the screen being shown stay in the Root: Debug's `LifecycleStartEffect`, and Location Notes' `activate`, `reveal`, `deactivate` pair. | Those effects are tied to the screen being shown, not to ViewModel lifetime. | Moving them into the ViewModel. | Q-N8 (flicker) stays. |
| `LocationNotesManager.send()` is now `suspend` and returns a result. The notes screen is its only caller. | F3 needs a real success or failure; the old code returned silently on several paths. | A boolean on the existing call. | A `:core:nostr` API change with no relay or wire change, so no Mesh Lab. |
| Debug stays reachable in release builds. | The developer's choice: it is left in for people to use. | Hiding it. | None; intentional. |

#### What Phase 10a changed

Behaviour changes, each its own commit and revertable alone (except F3, below).

| ID | Before | After |
|---|---|---|
| B1 | Opening Debug could create or replace the mesh service; after a panic clear it kept showing the old one. | Debug follows the current mesh and never creates one; with none running it says so. Removes QD1, QD2. |
| B2 | The relay graph stopped when scrolled off-screen; legend nicknames never refreshed; colours changed after rotation. | It samples the whole time Debug is shown. Nicknames refresh; colours are stable. Removes QD3, QD4, QD17. |
| B3 | Debug kept polling, sampling and counting relay stats in the background. | All three pause when Debug is not on screen and resume on return; the graph shows a gap. |
| R1 | About's Tor status card did not follow the Tor switch (QA1). | It follows the switch, and a change made elsewhere shows up (QA2 goes with it). |
| F3 | A note's draft was cleared even when sending failed, and lost on rotation (Q-N3). | The draft is kept until `send()` succeeds, and survives rotation. A second tap during a send is ignored; the draft is cleared on success only if unchanged. |

Removed by construction (not reproduced first): Q-K2 (events now wait while the screen is
stopped), Q-K4 (file reads moved to `Dispatchers.IO`), QA7 (version name and the locale parse
moved off Main), QD5 (duplicate address-map read; the legend uses the latest poll snapshot, up
to 1 s old), QD11 (a poll exception no longer crashes), Q-H10 (generic composable names are now
private), Q-N1 and Q-N2 (dead parameters dropped).

Notes a reader should know:
- **Q-K4 leftover.** `getPartialDownloadProgress()` in `startDownload` stays on Main; moving it
  changes the visible status ordering. One small file stat remains.
- **QA7 first frame.** About shows an empty version and the "System default" language label for
  a few ms, until the IO read lands. The version label is in the hero above the tabs, so an empty version shows briefly on every About open. Fix, if it
  shows: seed the tag synchronously.
- **QA5.** `PoWPreferenceManager.init` moved into `AboutViewModel`'s init rather than being
  dropped: `AppInitializer` runs from the startup gate, so it is not provably first. Idempotent.
- **Q-N16 (new).** Location Notes' `locationEnabled` starts false, so the gate sees "disabled"
  and then the real value on every open. Reproduced, not fixed.
- **F3 split.** The draft surviving rotation landed in the Location Notes restructure commit
  (`d91f50fd`), not in F3's commit, so reverting F3 alone keeps that half.
- **Collection state.** Between B2 and B3 the Debug Root collected with
  `minActiveState = CREATED`, so the relay card kept sampling in the background as before; B3
  reverted it to the default.
- **Legend map** can lag the poll by up to 1 s (QD5).

Listed, not fixed, per screen:
- **APK share section:** Q-K1 stale toast, Q-K3 share dialog inside `AnimatedVisibility`, Q-K8
  Activity cast and Activity-scoped ViewModel, Q-K9 double tap. K10's success path is not
  JVM-covered.
- **Onboarding:** names only; no behaviour change.
- **Hotspot:** Q-H1 `STOP_HOTSPOT` is dead code, Q-H4 auto-start rule, Q-H5 clipboard API, Q-H6
  tab is a plain `remember`, Q-H12 double `Intro` in retry.
- **About:** QA6 background and live-voice read once per visit, QA8 language tick matching,
  QA9 hard-coded English, QA10 PoW writes on every step, QA13 picker state local. A5's
  `locales_config` parse has no JVM test (no Android resources).
- **Debug:** QD6 series not reset on mode switch, QD7 stale last-second rate, QD9 sliders save on
  every step, QD12 and QD13 mirrors, QD19 hard-coded English, QD20 empty `if` blocks. A test
  leaves a stale per-peer map in the `DebugSettingsManager` singleton, which has no reset.
- **Location Notes:** Q-N3 (now fixed by F3), Q-N4 and Q-N12 time text, Q-N5 "Enable Location"
  never asks for permission, Q-N8 flicker, Q-N10 name truncation, Q-N11 send enabled with no
  relays. N15 (`LocationNotesButton`, in `:feature:chat`) is not covered.

Tests: JVM 891 to 1076 (3 skipped). Device: app 63, chat 1, settings 5, location 2, share 3
(the three feature suites were 1 each at the start). New: `AboutViewModel`,
`DebugSettingsViewModel`, `LocationNotesViewModel`; reshaped: `HotspotViewModel`,
`ApkDownloadViewModel`, `OnboardingViewModel` (rename). The Mesh Lab was not run: no protocol,
transport or foreground-service change.

#### Follow-ups from Phase 10a

- The chat screens stay unsplit and keep their singleton calls in Phase 10 (narrowed by the developer):
  `ChatScreen.kt`, `PrivateChatScreen.kt`, `ChatHeader.kt`, `LocationNotesButton.kt`,
  `MessageComponents.kt`, `media/AudioMessageItem.kt`, `MessageInteractionUtils.kt`,
  `GeohashPickerActivity.kt` (no ViewModel) and `MeshGraph.kt` (a rendering-only
  `DebugSettingsManager` read). 10c removes most of the `getInstance()` calls.
- 10b: done; see "Phase 10b" below.
- 10c: all project singletons become Hilt classes; Hilt in `:core:mesh`, `:core:nostr`,
  `:core:tor`; `:wear` adopts Hilt and KSP. Needs Mesh Lab on two phones and its own spec.
- Owed: the spec's "End of 10a" phone checklist.

### Phase 10b: the peer list and the location channels screen

#### Phase 10b decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Both screens follow the 10a shape (see "MVI shape"). `MeshPeerListRoot` and `LocationChannelsRoot` hold the ViewModel; `MeshPeerListScreen` and `LocationChannelsScreen` take `uiState` and `onAction`. `LocationChannelsSheet.kt` was renamed `LocationChannelsScreen.kt`. | One pattern for the maintainer; the screens are previewable and testable without Hilt. | Keeping the old file name. | Stale mentions of `LocationChannelsSheet` remain in `docs/GeohashPresenceSpec.md`, the visual-review playbook and a `ChatUserSheet.kt` comment. |
| Rows are immutable row models built by pure functions (`ConversationRowUi`, `MeshPersonRowUi`, `ChannelRowUi`, `GeohashPersonRowUi`, `ChannelTitle`). Composables get rows and a lambda, never the ViewModel or a manager. | The 22 pass-through members and six helpers on the old ViewModel existed only so composables could reach singletons while composing. A row model makes every display rule a unit test. | Passing the ViewModel down to the rows. | Some row fields are redundant (`MeshPersonRowUi.key` equals the peer ID; `showHashSuffix` and `suffix`). Tests assert them, so they stayed. |
| `PeerListLookups` is a public port (bound with `@Binds` in `PeerListLookupsModule`, installed in `ViewModelComponent`) for what the old composables read through `LocalContext`: identity, favourites, verification. `RealPeerListLookups` builds `SecureIdentityStateManager` eagerly, once. | The screen no longer reads data from `LocalContext`. The old lazy build swallowed a constructor failure behind a catch; eager keeps it loud. | A lazy lookup; Hilt-binding each manager. | An eager Keystore init when the ViewModel is created, also in geohash mode. |
| The peer list ViewModel combines 21 source flows in groups of five, then `flowOn(Dispatchers.Default)` and `stateIn(WhileSubscribed(5_000))`, with a synchronous first value. The row building, sorting and lookups that ran on every recomposition now run off the main thread. | Those were the per-recomposition cost. The synchronous first value avoids a flash of "no conversations". | Building rows in `remember` blocks. | `FavoritesPersistenceService.favorites` is an unsynchronised map that is now also read on `Default` (pre-existing; 10c). `myPeerID` is read on `Default` too, in both ViewModels. |
| The search query lives in `SavedStateHandle` (`peer_list_query`) and round-trips through the state flow. It is merged after `flowOn`, so typing never waits for the row rebuild. | It survives rotation and process death. | A `rememberSaveable` in the Root. | The round trip is plan-mandated. Checked on a physical phone with fast keyboard taps: no lost characters. |
| The location sampling effect moved into `LocationChannelsViewModel` (an `init` collector; `onCleared` ends sampling). The other location effects stay in the Root with the same keys and call order: the `LifecycleResumeEffect` (refresh on resume, end on pause), the nearby-notes `DisposableEffect`, the per-bookmark `resolveNameIfNeeded` effect. | Only sampling is independent of composition. The other three are tied to the screen being shown, as in 10a. | Moving all four into the ViewModel. | Sampling starts at ViewModel creation and keeps running while Notes is on top; before, it was keyed off composition. Q-L13's trigger (the unused `appLocationEnabled` key that forces a reconcile) is kept. |
| Channel titles are data (`ChannelTitle`: title and optional count), not a string with a `\u001F` separator. | L11: the old code packed and split a string to carry two values. | Keeping the pack and split. | None; rendered text is identical. |
| Bookmark flags on nearby and teleported rows come from the combined `bookmarks` list snapshot, compared case-insensitively as the old store lookup did, not from `GeohashBookmarksStore.isBookmarked` read on `Default`. | It removes an off-main read of a set the main thread mutates. The visible result is the same except for a race. | Keeping the store lookup as a lambda. | Q-L5 no longer holds at screen level; its pinning test was renamed `bookmarkFlags_comeFromTheLookupPerGeohash`. |
| 10a's ports gain members instead of a second port: `NotesLocationSource` (selected channel, services flags, `select*`, live refresh) and `NearbyNotesGate.revealed`. New ports `ChannelBookmarks` and `TorRouting`, bound in `LocationChannelsPortsModule`. The chat-session side is the internal `ChannelsSession`. | The ports exist and 10c will swap their adapters. | A parallel port over the same manager. | Action and event details deviate from the plan, because Q-L1 kept the custom geohash field Root-local: the typed text rides on `TeleportClicked`, `MapPickerClicked` and `CustomGeohashRejected`; `OpenMapPicker` is non-null; `teleportEnabled` is derived in the Root; the effect keys travel in the state. Expect API churn in 10c. |
| Undo after deleting a conversation moved into the ViewModel (F2). Pending deletions are kept for 120 s (`UNDO_WINDOW_MS`, Android's longest "Time to take action" setting), then dropped. | F2 says Undo survives rotation and opening a chat, not that it is offered an hour later. A list-lifetime Undo is an unlisted visible change, keeps deleted messages in memory and lets a late restore overwrite a newer draft. 120 s means a visible Undo never silently does nothing. | Undo for the lifetime of the list entry; a 10 s window. | Coming back to the list within 2 minutes re-offers Undo once. |
| No panic fence on `restoreDeletedConversation`. | Panic starts only from `ChatRoute`, and the list ViewModel is cleared when the list is popped; the window is also bounded. | A fence now. | Logged for 10c (the `MessageRouter` panic-clear design): a future panic entry point above the list could restore wiped content within the window. |
| The three problems found in the phone check were fixed inside 10b, each its own commit (see "What Phase 10b changed"). | The developer chose to fix now. Two were upstream bugs, one a Codex finding on F2. | Listing them. | None. |

Process rulings (how the phase was run, not product behaviour):
- Each task ran the local test gate (JVM tests, lint, then each module's device suite on an emulator).
- The gate used whichever emulator was listed. A larger emulator image dropped offline mid-suite once and failed one settings test deterministically; the clean image passed. Cost if wrong: a flake from a dirtier image; re-run on the clean one.
- Tasks 4 and 8 (a person checking each screen on a physical phone) are real stop points, because the spec requires that check before the next screen.
- JVM tests live in `feature/*/src/test/java/...`, the modules' existing source directory, not `src/test/kotlin`.
- Per task the implementer commits first, then the gate and Codex run on the commit; fixes are follow-up commits. A red gate costs an extra fix commit, not an amend.
- The Task 2 review finding (six dead ViewModel helpers, one duplicating a rule) was left to Task 3, which rewrote the ViewModel anyway. One intermediate commit carried the duplicate.
- The Undo clock and a strengthened `deleteThenNewCollector_undoStillRestores` (it now cancels mid-delete) were folded into F2 by fixup. Only unpushed SHAs changed. F2 is now two commits: `0100be67` and the later Undo-window fix `31750b0d`; revert them together.
- Task 5 (a pure extraction) ran while the Task 4 phone check was under way; Task 6, the MVI move that would copy any flaw, waited until it passed.
- An "Undo did not restore" seen once on the phone is closed as a test artifact. That tap came in a separate tool call 10 to 20 s after the delete, past the Long snackbar timeout, and Material3 ignores `performAction` once `dismiss()` has resumed. Four same-command taps restored. A rare silent miss would be pre-existing Material3 behaviour.
- The "Loading notes…" repetition was investigated as a possible Task 6 regression and ruled out (see the notes below).

#### What Phase 10b changed

| ID | Before | After |
|---|---|---|
| F1 | `PeopleSection` logged peer IDs and favourites on every redraw, in release builds too (Q-P11). | The line is gone. `NoPeerDataInLogsTest` fails if a peer-list `Log` call interpolates a peer ID, favourite or nickname. |
| F2 | Undo after deleting a conversation lived in the Root's coroutine and was lost on rotation or when a chat was opened. | It lives in the ViewModel and survives both, for 120 s from when Undo is first offered. |
| R2 | The channels screen's Tor row read the preference once and ignored changes made elsewhere (Q-L11). | It follows `TorPreferenceManager.modeFlow`, so a change made in About shows up. |
| Restructure | Two composables of 1648 and 1202 lines held the logic, the lookups and the singleton calls. | Row models, state builders and ViewModels; the composables are split by card or section (table below). |

Dead code removed: `convertRSSIToSignalStrength`, `PeerRowIconSize`, the unused `geohashPeople.size` count and its dead `Location` branch (Q-P1: it was never displayed), the `peerRSSI` collection, and the `\u001F` title pack and split (L11).

Found in the phone check and fixed. The first two are upstream bugs, found during the phone check; they reproduce on `upstream/main` and on the commit before the MVI move, so they are not 10b regressions.
- **A swiped conversation row never slid back.** After a swipe to mark read or delete, the row stayed off to the side and the gesture stayed disabled. The reset effect was keyed on `currentValue`, which moves to the next anchor once the drag passes halfway, so the effect fired mid-drag and the drag interrupted `reset()`. It is now keyed on `settledValue`. Haptic, read toggle and delete dialog still fire once per swipe, now when the row settles. Two device tests pin it.
- **The Undo snackbar was missing on most deletes.** It was a symptom of the stuck row: after the fix it shows on every delete on the phone, and Undo restored four of four. The emulator never reproduced it, so only guard tests were added.
- **Undo window start (a Codex finding on F2).** The window ran from the delete, so a queued Undo could expire while its snackbar was showing, under the two-minute accessibility timeout. It now starts when the Undo is first offered.

Preserved and pinned quirks, in tests named `…_currentBehaviour_<QID>`: Q-P1 (the two geohash counts), Q-P2 and Q-L12 (two mesh counts), Q-P3 ("You" compares a peer ID with the nickname), Q-P6 (unread counts all incoming messages), Q-P9 (an `@name` channel tap does nothing without a match), Q-P10 (leaving a channel broadcasts `left <name>`), Q-L6 (a bookmarked teleported channel appears twice), Q-L9 (`formatDistance` without a `Locale`). Q-L5 is the exception (see the decisions). Still true and listed: Q-P13 (the delete dialog does not survive rotation), Q-P19 (QR verify is hidden in a location channel).

Line counts, before (`18db3320`) and after:

| Screen | Before | After |
|---|---|---|
| Peer list composables | `MeshPeerListScreen.kt` 1648 | 1608 in five files: `MeshPeerListScreen.kt` 420, `PeerListConversations.kt` 769, `PeerListMeshPeople.kt` 201, `PeerListChannels.kt` 132, `PeerListCommon.kt` 86 |
| Geohash people section | `GeohashPeopleList.kt` 332 | 209 |
| Peer list logic (new files) | inside the composables | `ConversationRowModel.kt` 325, `MeshPeopleRows.kt` 397, `GeohashPeopleRows.kt` 167, `MeshPeerListUiState.kt` 199, `PeerListLookups.kt` 129 |
| Peer list ViewModel | 116 | 451 |
| Channels composables | `LocationChannelsSheet.kt` 1202 | 1271 in four files: `LocationChannelsScreen.kt` 362, `LocationChannelSections.kt` 419, `ChannelOptionRow.kt` 296, `CustomGeohashRow.kt` 194 |
| Channels logic (new files) | inside the composable | `LocationChannelRows.kt` 278, `GeohashChannelFormatting.kt` 142, `LocationChannelsUiState.kt` 189, `LocationChannelsPorts.kt` 61 |
| Channels ViewModel | 65 | 335 |

The composables did not shrink in total. The largest file went from 1648 to 769 lines and from 1202 to 419, and the rules moved out of the composables into code that has tests.

Tests: JVM 1200 to 1336 (3 skipped). Device: app 65 (unchanged), chat 1 to 6, location 2 to 4, settings 5, share 3. New JVM test classes: `NoPeerDataInLogsTest`, `ConversationRowModelTest`, `MeshPeopleRowsTest`, `GeohashPeopleRowsTest`, `MeshPeerListViewModelTest`, `LocationChannelRowsTest`, `GeohashChannelFormattingTest`, `LocationChannelsViewModelTest`. New device tests: `MeshPeerListRootTest`, the swipe tests in `MeshPeerListScreenTest`, and `LocationChannelsScreenTest`. One pre-task fix scrolled a settings test's target clear of an overlaid top bar on a tall emulator. The Mesh Lab was not run: no protocol, transport or foreground-service change.

Phone check, on a physical phone with synthetic data and a mocked location: rows, search with fast typing, swipe to mark read, delete and Undo, people, verify, channels, nearby channels, bookmarks, Tor off and on, teleport by typed geohash, location on and off, and the notes card all pass.

Notes a reader should know:
- **Notes card shows "Loading notes…" repeatedly.** While a jittering GPS fix crosses building cells, the card returns to "Loading notes…" every few seconds. This is pre-existing, not a 10b change: the Root's nearby-notes effect re-keys on each building-cell change, and each re-key stops and resubscribes (about 2 s of loading). The keys and calls are the same before and after the MVI move, and samples taken before and after were within noise. A follow-up candidate for the notes work.
- **Sampling** now starts at ViewModel creation and keeps going under Notes.
- **R2 timing.** The Tor row follows `modeFlow` a frame after the switch.

#### Known follow-ups from Phase 10b

Deferred from reviews, none blocking:
- `NoPeerDataInLogsTest` is a text scanner. It is naive about character literals and raw strings, and only sees interpolation inside the `Log` call itself.
- `MeshPersonRowUi.key` and `showHashSuffix` duplicate other fields (tests assert them).
- The saveable swipe state may fire again within about 150 ms of a restore.
- Comments on the Undo window say "snackbar appears", but the window starts at the head of the queue. There is no short-swipe-no-action test.
- The clock guard in `undoDelete` is not tested apart from the timer. The timer uses uptime and the guard `elapsedRealtime`, which can skew after deep sleep; the guard covers it.
- `FavoritesPersistenceService.favorites` is read on `Default` and is unsynchronised (pre-existing; 10c). `SwipeToggleRead` runs in `viewModelScope` from the restructure commit on, an invisible side effect.
- `topBarAlpha` in the channels screen is dead (pre-existing).
- After more than 5 s on Notes, the stale effect keys in `uiState` cause one redundant deactivate and activate; it self-heals.
- The panic-clear fence for a restore, for 10c (see the decisions).
- Stale `LocationChannelsSheet` names in a doc, a KDoc and the visual-review playbook.

Resolved during the phase: the six dead ViewModel helpers, the O(n²) lookups in `meshPeopleRows` (now a `HashSet` and a first-wins map), `ChannelTitle.level` (dropped), the duplicate `channelCountText` (the composable is now `peopleCountLabel`), and the real-city geohashes in the old sheet test (replaced by synthetic ones in `GeohashChannelFormattingTest`).

Owed: the Mesh Lab and the second-phone pass wait for 10c. Phase 10 is complete after 10c, which needs its own spec.

### Phase 10c-1: `:core:tor` and `:core:nostr` on Hilt

#### Phase 10c-1 decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Bridge first, then flip. Task 1 put a `@Provides` bridge module in each library that returned the existing static instance. Task 2 moved every outside caller to injection. Tasks 3 to 5 converted the classes and deleted each holder together with its bridge. | Every commit builds and passes; callers never wait on class conversion. A scan test (`NoStaticTorNostrAccessTest`) fails on any static reach, so a missed call site shows up as a red test. | Converting leaf classes first. It would need temporary setters on consumers that were still static. Task 5 ran roots first for that reason. | The bridge also covered `NostrNetwork`, `NostrBackgroundRuntime` and `LocationNotesInitializer`, which the plan's list had left out. |
| `ArtiTorManager` takes `dagger.Lazy<OkHttpProvider>`. | The two reference each other (the manager resets the provider on a route change, the provider reads the manager). `Lazy` breaks the cycle at the same two reset sites as before. | `Provider`; moving the reset to a listener. | `ArtiTorManagerRouteResetTest` pins the path; `resetNetworkConnections` is `internal` for it. |
| `ChatScreenDependencies` (an `@EntryPoint` in `:feature:chat`) gives the chat composables the Tor and Nostr singletons. It is transitional. `ConversationNotificationReceiver` reaches `NostrTransport` through a nested `@EntryPoint`. | Chat composables read these singletons directly and have no ViewModel yet. Kotlin cannot extend an `@AndroidEntryPoint` receiver cleanly (abstract `super.onReceive`), so the receiver uses the nested entry point, as `ApkDownloadWorker` does. | `@Provides` bridges kept for chat; a Hilt receiver base class. | The chat MVI work removes `ChatScreenDependencies`. |
| `NostrNetwork` (the holder with `install`) is replaced by `NostrNetworkModule` in `:app`, which `@Provides` `NostrHttpClients` from `OkHttpProvider` and `ArtiTorManager`. | The port still fails closed, now at compile time: a build with no binding does not compile, instead of throwing at the first relay call. | A runtime holder with a check. | `:core:nostr` cannot see the binding; `NostrNetworkModuleTest` pins that a Tor route change reaches the relays. |
| `NostrTestManager` is deleted. | It was dead: no reference anywhere outside its own file. | Converting it. | None. |
| The two runtime objects `NostrBackgroundRuntime` and `LocationNotesInitializer` became `@Singleton` classes with `Lazy` dependencies. | They are the Application's start-up wiring and must not build the relay or channel managers earlier than before. | Eager injection. | The `Lazy` holders keep first use where it was. |
| Dispatchers come by qualifier from `:core:mesh`: `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`, `@MainImmediateDispatcher`; their `@Provides` are in `:core:nostr`'s module. Each class keeps the dispatcher it had; `limitedParallelism(1)` is applied inside `GeohashActivityMonitor`. | `:core:mesh` is the lowest module that nostr and later the mesh both see; `:core:domain` holds models only. Tests can pass a test dispatcher. | Qualifiers in `:core:domain`; the call sites keep `Dispatchers.X`. | `:core:mesh` gained a `javax.inject` dependency. Qualifiers moved to `:core:mesh` early so Task 3 could use them. |
| `PoWPreferenceManager` keeps an explicit `load()`, called at the same two places as the old `init(context)`. | Loading in the constructor would run in `Application.onCreate` (the background-runtime events factory), earlier than the startup gate, and change which PoW setting background geohash events see. | Loading at construction, as the plan said. | Flipping is a two-line change if wanted. |
| `TorNostrSingletonIdentityTest` is not `@HiltAndroidTest`. It reads the real graph through a debug-only `@EntryPoint` (`TorNostrTestAccess`), as `ChatSessionTestAccess` does. | The repo has no Hilt test runner; adding one moves every device test onto `HiltTestApplication`. | A Hilt test runner. | It checks the production graph. It asserts the shared gate on 3 of about 7 holders. |
| `AppStartupOrderTest` pins the start-up order: `DefaultAppInitializer` is checked at runtime with fakes (PoW load, then location notes). `BitchatApplication.onCreate` is still checked in source, not at runtime. | Running `onCreate` under Robolectric also starts other static start-up work, so a source-order assertion stands in for it. | Leaving the order unpinned. | Its "no `NostrHttpClients` in `onCreate`" check is weak; the compile-time binding is the real guard. |
| The scan test reads only `src/main` of every module, with a small lexer that drops comments and string text and keeps `${}` template code. | It must fail on any static reach, including multi-line chains and `::` references, and ignore text. | A regex over raw lines. | It does not read `src/debug`. Constants and nested types are allowed by a generic rule on capitalised members, not by a list. |

#### What Phase 10c-1 changed

- `:core:tor` and `:core:nostr` use Hilt. `ArtiTorManager`, `OkHttpProvider`, `NostrRelayManager`, `NostrClient`, `NostrTransport`, `LocationChannelManager`, `GeohashBookmarksStore`, `LocationNotesManager`, `NearbyNotesController`, `RelayDirectory`, `LiveLocationPrivacyGate`, `PoWPreferenceManager`, `NostrEventDeduplicator`, `NostrBackgroundRuntime` and `LocationNotesInitializer` are injected singletons (`NostrClient`, which nothing injected, was deleted in 10c-2a-1). `GeohashActivityMonitor` is injected and unscoped. Their `getInstance()` / `.shared` accessors, `NostrNetwork`, `NostrTestManager` and both bridge modules are gone.
- Outside callers in `:app`, `:core:chat` and the features inject them (`Lazy` where the first use must stay late).
- Tests that needed global teardown now build fresh instances (see `docs/refactor-benefits.md`).
- Behaviour is unchanged. No persisted key, log line, dispatcher or start-up order moved.
- Every class is classified identity or process in a local planning inventory, for 10c-2a. `NostrRelayManager` is process-wide, but its identity-tied subscriptions must drop when an identity ends.
- Tests: JVM 1404 to 1410 (3 skipped). Device: app 65 to 68 (the three identity tests); chat 6, settings 5, location 4, share 3 unchanged. The Mesh Lab was not run for this phase; it is owed with 10c.

#### Follow-ups from Phase 10c-1

For 10c-2 (10c-2a-1 converted or deleted every class named here except `MessageRouter`; `PoWPreferenceManager` keeps its `load()`):
- Stateful singletons outside this run's list stay static: `GeohashConversationRegistry`, `NostrIdentityBridge`, `TorPreferenceManager`. `ArtiNative` is a JNI binding.
- `MessageRouter` is still a static `getInstance` (moved to the identity scope in 10c-2a-2b).
- `PowerManager.getInstance` is static and now reached by the relay-manager JVM tests, shared across Robolectric tests. Inject it.
- `PoWPreferenceManager` keeps its explicit `load()`.
- `NostrClient` was an injected singleton that nothing injected. Deleted in 10c-2a-1.
- `Provider<NostrHttpClients>` in `LocationChannelManager`, `GeohashBookmarksStore`, `GeocoderFactory` and `OpenStreetMapGeocoderProvider` is a leftover of `NostrNetwork.clients()`. Simplify it.

Deferred from reviews, none blocking:
- The identity test covers 3 of about 7 gate holders. The scan reads only `src/main`.
- `httpClients` and `liveLocationGate` are public `@VisibleForTesting` members on the singletons.
- `MainActivity` injects `NearbyNotesController` eagerly (no side effects; `Lazy` would be exact).
- `resetNetworkConnections` is `internal` for a test; `@VisibleForTesting` is not on it.
- The `javax-inject` version entry in `libs.versions.toml` sits apart from the Hilt group.

### Phase 10c-2a-1: the mesh, the app and Wear on Hilt

#### Phase 10c-2a-1 decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) 10c-2a is split. 10c-2a-1 moves every remaining static singleton in `:core:mesh`, `:app`, `:core:chat`, `:core:nostr`, `:core:tor` and Wear onto Hilt, with no behaviour change. 10c-2a-2 adds the identity scope. (2026-10-29) | Converting about 35 classes and introducing a new component in one run would put two kinds of risk in one diff. The scope work needs every class injectable first. | One 10c-2a run. | One more review and phone check. The identity scope waits for 10c-2a-2. |
| (P) Bridge first, then flip, as in 10c-1. Each class's static accessor and its bridge go in the same commit that gives it an `@Inject` constructor. Consumers flip before the class they reach, so no commit needs a temporary setter. | One instance per class at every commit. | Leaf classes first, with setters. | Commit order follows the reach graph, not the plan's grouping. |
| (P) Each init call stays where it was. A class whose constructor did no I/O keeps an explicit call at the old place: `SpamFilterPreferences.init`, `DebugPreferenceManager.init`, `GeohashAliasRegistry.initialize`, `GeohashConversationRegistry.initialize`, `WifiAwareController.initialize`, `AppStateStore.initializeConversationPersistence` and `MeshServicePreferences.init` in `BitchatApplication.onCreate` (the last also in `BootCompletedReceiver`), `ContactDirectory.initialize` in the chat session's start-up, `TorPreferenceManager.init` inside `ArtiTorManager.init`, and `WearPeerIdentityState.initialize` in the watch's `onCreate`. A class whose constructor did the work (`PowerManager`, `DebugSettingsManager`, `LiveVoiceManager`, `SeenMessageStore`, `ConversationListPreferences`, `WearNotificationCoordinator`) is first resolved where `getInstance` was first called, through `Lazy` where that moment is late. | A preference read late or early changes behaviour: the spam filter off for the first messages, or the wrong debug toggles. | Loading every class at construction. | `AppStartupOrderTest` and the new `WearStartupOrderTest` pin the order in source. Every Application field is `Lazy`. |
| (P) The watch's mesh is still created after permissions are granted. `WearMeshService` is `@Inject` and unscoped, and only `WearMeshHolder` (`getOrCreate()`, `peek()`) takes it. | Creating the mesh generates the identity keys, so injecting it anywhere else would create keys when the activity starts. | A `@Singleton` mesh. | `WearMeshHolderTest` pins `peek()` null before creation. A scan rule fails if anything else takes `WearMeshService`. |
| (R) `BluetoothMeshService` is built through a no-argument assisted factory, and `UnifiedMeshService` through one that takes the Bluetooth mesh. Only `MeshServiceHolder` takes them. The unscoped `BluetoothMeshService` binding in `MeshModule` is gone. | The holder taking a `Provider<BluetoothMeshService>` while that binding asked the holder is a cycle. It compiles and recurses at run time. | Keeping the binding. | Nothing can inject `BluetoothMeshService` directly; trying fails to compile. |
| (R) `FavoritesPersistenceService`, `AppStateStore` and `ContactDirectory` flip in one commit. | Their static reads form a cycle (favourites → app state → contact directory → favourites), so no consumer-first order exists. Handing the directory favourites later through `initialize` would file favourites under raw peer IDs before the chat session starts, and always on the watch. | Splitting the commit. | One large commit that bisects as a unit. `FavoriteConversationCanonicalizationTest` was written green on the static form first, then kept green. |
| (R) Favourites keep their pre-init contract. The constructor does no I/O, and `initialize()` does what the old constructor did: it is synchronized and idempotent, and it is retried after a failure. Every public method throws the same "not initialized" until then. The callers that initialised or retried before still do, at the same moments. | Consumers relied on that throw inside their own `try`. Initialising in the constructor would move Keystore work into the first injection. | Loading at construction. | The separate retry helper is gone. |
| (P) `MessageRouter` stays static until 10c-2a-2. Its `getInstance` also takes each singleton it used to reach statically as a parameter, passed by its three callers. | It is the mesh-replacement machinery the identity holder replaces. Converting it now would mean converting it twice. The parameters let its dependencies flip. | Converting it now. | The scan's only exemption, removed in 10c-2a-2b. |
| (P) The dispatcher `@Provides` module moved from `:core:nostr` to `:core:mesh`. | Wear does not depend on `:core:nostr`, and the mesh services now take their dispatchers by qualifier. | A second module in Wear. | None. |
| (P) Wear composables take their dependencies as parameters from `MainActivity`, which field-injects them. | It keeps the composables free of the graph. | An entry point in each composable. | `MainActivity` holds every field the screens need. |
| (R) `LocationChannelManager`, `GeohashBookmarksStore`, `GeocoderFactory` and `OpenStreetMapGeocoderProvider` take a plain `NostrHttpClients`, not a `Provider` of it. | The binding is a singleton, so the `Provider` always returned the same object. Each lookup still asks it for the current client, so a Tor mode change still applies to the next request. | Keeping the `Provider`. | Tor routing is unchanged, and the port still fails closed: there is no default binding. |
| (P) `MessageRetentionService` (all commented out) and `NostrClient` (injected nowhere) are deleted. | Dead code. | Converting them. | None. |
| The documented lock command now also resolves Wear's instrumented-test classpaths and `:core:mesh`'s release test-fixture classpath. | No task in the old command resolved them, so with Hilt in Wear, strict locking failed `:wear:compileDebugAndroidTestKotlin` and `:core:mesh:compileReleaseTestFixturesKotlin`. | Leaving them unlocked. | No new artifact or version. CI was not affected. See `docs/reproducible-builds.md`. |
| `NoStaticMeshAccessTest` reads every module's main and debug sources. It fails on a reach through a class name, a static holder, a converted class turned back into an `object`, mutable state in one's companion, an import alias or typealias, a hand-built shared store, and anything but `WearMeshHolder` taking the watch mesh. App's unit-test tasks declare the scanned trees as inputs. | It must fail on any static singleton. Without the inputs, a source-only change in another module left a stale up-to-date result locally. | Separate tests per module. | It is a text scan, not a type check. Companion state is found by file name. `src/androidTest` is not scanned: two screen tests build a `SpamFilter()` double there. |

#### What Phase 10c-2a-1 changed

- Wear is a Hilt app: `@HiltAndroidApp` on `BitchatWatchApplication`, and `@AndroidEntryPoint` on `MainActivity` and `WearMeshForegroundService`.
- These became injected `@Singleton`s, in their old packages and modules: `PowerManager`, `PeerFingerprintManager`, `DebugSettingsManager`, `SpamFilter`, `SpamFilterPreferences`, `FavoritesPersistenceService`, `LiveVoiceManager`, `SeenMessageStore`, `ConversationRepository`, `MeshGraphService`, `AppStateStore`, `ContactDirectory`, `TransportBridgeService`, `PrivateMessageArrivalOrder`, `VoiceWaveformCache`, `TransferProgressManager`, `DebugPreferenceManager`, `GeohashAliasRegistry`, `VerificationService`, `WifiAwareController`, `MeshServiceHolder`, `MeshServicePreferences`, `MeshTransportToggles`, `ConversationListPreferences`, `NostrIdentityBridge`, `GeohashConversationRegistry`, `TorPreferenceManager`, `WearChatState`, `WearPeerIdentityState` and `WearNotificationCoordinator`. `WearMeshHolder` is new.
- The mesh services (`BluetoothMeshService`, `UnifiedMeshService`, `WifiAwareMeshService`, `WearMeshService`) are injectable and unscoped, and still created only by their holders. The hand-built mesh parts take the singletons by constructor from whoever builds them.
- Every project `getInstance()` and `.shared` accessor is gone except `MessageRouter`'s (gone in 10c-2a-2b), and so is every bridge module. The stateful `object`s left are outside this phase's scope: the theme and chat UI-mode preferences in `:core:designsystem:phone`, `FileShareDispatcher` in `:feature:chat` and the globe's `LandData` cache in `:feature:location`.
- Behaviour is unchanged: no persisted key, log line, dispatcher, thread or start-up step moved. The panic wipe keeps its steps and their order, and now resets the injected instances.
- Tests that reset static state now build their own instances (see `docs/refactor-benefits.md`). `MeshSingletonIdentityTest` (device) shows the mesh and the panic wipe hold the graph's one instance of each shared store.
- Every class is classified identity or process in the local planning inventory, for 10c-2a-2.
- Tests: JVM 1410 to 1429 (3 skipped). Device: app 68 to 71 (the three identity tests); chat 6, settings 5, location 4, share 3 unchanged. The Mesh Lab was not run.

#### Follow-ups from Phase 10c-2a-1

For 10c-2a-2:
- `MessageRouter` and its unscoped `Provider`, `ChatSessionMesh.replace`, `SessionMeshSource` and `PanicClear`'s mesh rebuild become the identity holder pattern. `MeshServiceHolder` becomes the identity scope's mesh factory, and `WearMeshHolder` the watch's.
- `WearMeshService` implements the `MeshService` role interfaces.
- Process-wide classes that read identity state must follow the current identity: `PowerManager` (direct peers), `WearNotificationCoordinator` (the watch's chat state). The spam filter's settings are pushed into it once at start-up and must reach a rebuilt filter.
- `NostrRelayManager` stays process-wide; its identity-tied subscriptions must drop when an identity ends.

Left as they are (no behaviour change in this phase):
- The watch never initialises `ContactDirectory`, `DebugPreferenceManager` or `VerificationService`.
- The Bluetooth and Wi-Fi Aware meshes each build their own `NotificationManager` (beside the chat session's) and their own `EncryptionService` over the same stored keys.
- The command tests in `MessageSendingTest` (device) are flaky: they wait 5 s for the UI to react. `aCommandRunsInsteadOfBeingSent` fails on the unchanged base, and `pickingACommandSuggestionCompletesIt` timed out once in the final gate. Gates count them green only when a rerun of the class passes.
- `DebugSettingsManager`'s debug buffers hold peer IDs and nicknames; the 10c-3 wipe list must include them.

Resolved during the phase: `GeohashParticipantTrackingTest` failed when run alone, most likely because it leaned on spam-filter state that an earlier test left in the shared instance. It now builds its own `SpamFilter` and passes alone.

Deferred from reviews, none blocking:
- The scan finds a class's companion by file name, so a companion added to a class in a file of another name is not checked. It does not read `src/androidTest`.
- `BluetoothMeshService` and `PanicClear` expose their constructor parameters as public `@VisibleForTesting` properties for the identity test.
- `MeshServiceHolder.getOrCreate(context)` keeps an unused `context` parameter.

Owed: the Mesh Lab on physical devices, for 10c-1 and this phase.

### Phase 10c-2a-2a: the mesh roles

#### Phase 10c-2a-2a decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) 10c-2a-2 is split. 10c-2a-2a splits `MeshService` into role interfaces and makes `WearMeshService` implement the roles it supports. 10c-2a-2b adds the identity component, the holder, the mesh factory, `MessageRouter`, `ChatSessionMesh` and `SessionMeshSource`. (2026-10-31) | The roles change types only. The identity scope changes lifetimes. They are separate risks. | One 10c-2a-2 run. | One more review and phone check. |
| (P) `MeshService` is the sum of eight roles: `MeshLifecycle`, `MeshPeers`, `MeshMessaging`, `MeshReceipts`, `MeshVerification`, `MeshPrivateMedia`, `MeshDiagnostics` and `MeshWipe`. It keeps only `delegate`. Signatures and default parameter values are copied from the old interface. | A client that supports part of the mesh can implement part of it, without stubs. | One interface for all members. | Phone callers still take `MeshService`. Narrowing them is not needed by anything and is left alone. |
| (P) Seven members left the interface: `sendDeliveryAck`, `sendFavoriteNotification`, `sendFilePrivate`, `updatePeerInfo`, `shouldShowEncryptionIcon`, `getEncryptedPeers` and `printDeviceAddressesForPeers`. | Nothing called them through the interface. The first two had empty default bodies that `WifiAwareMeshService` relied on. | Keeping them with defaults. | A concrete class keeps a member as a plain method only if something calls it there. `sendFavoriteNotification` stays a plain method on `UnifiedMeshService`, which the debug test hook calls; the other callerless chains on the three mesh services were deleted; `MeshCore` keeps its copies. |
| (P) Wear implements `MeshLifecycle`, `MeshPeers`, `MeshMessaging` and `MeshDiagnostics`. Its own members stay outside any role: the nickname, `isRunning`, `connectToPeer`, `getPeerNickname`, `sendFavoriteNotification`, `sendFilePrivateEncrypted`, `onPrivateMessage` and `encryptionService`. | It supports no receipts, verification, private media or wipe through the mesh. | Implementing every role with stubs. | A member Wear delegates to `MeshCore` is allowed when `MeshCore` does the work. |
| (P) Wear's renames are pure. `sendChannelMessage` became `sendMessage`, which absorbs the two-parameter `sendMessage`. `sendPrivateMessageWithId` became `sendPrivateMessage`, which absorbs the three-parameter one. | The names now match the roles. The absorbed overloads already passed the same defaults (`channel = null`, `messageID = null`). | Keeping both names. | None. |
| (P) The watch's send helpers are generic over the roles. They take the nickname as a parameter, and the private voice note, which is Wear-only, as a send lambda. | Fakes of the roles can stand in for the mesh. | Taking `WearMeshService`. | `WearPeerIdentityState` takes `MeshPeers?`. |
| (R) No stubs. No role member has a default body. No override of a role member has an empty body, returns a constant without doing the work, or only logs. `MeshRoleImplementationsTest` scans for these, including a nested `Log` call. | A stub that compiles hides a missing feature. If a class cannot do a member, it should not implement that role. | Review alone. | It is a text scan. |
| (P) `PowerManager` and `LiveVoiceManager` take their dispatcher by injection. `WearApplicationInjectsLazilyTest` pins that the watch's application resolves its fields lazily. | The mesh services already took their dispatchers by qualifier. This removes the two that 10c-2a-1 left over. | Leaving them. | None. |
| (D) `MessageRouter` moved to 10c-2a-2b. (2026-10-31) | Its accessor re-points it at the current mesh each time it is resolved. A plain singleton now would need a temporary mesh-swap design that the holder replaces. | Converting it now. | It stayed the scan's one exemption until 10c-2a-2b. |
| (D) The panic rebuild of the chat session moved to 10c-2b. Panic keeps clearing in place and replacing only the mesh until then. (2026-10-31) | 10c-2b moves the chat session into the scope and switches panic to rebuilding it. This avoids a `Provider` change that the move would undo, and no screen points at a wiped identity. | Rebuilding the session in 10c-2a-2b. | Nothing new until 10c-2b. |
| (D) Private messages to a peer that restarted are dropped until that peer sends something and a new handshake runs. The bug stays recorded; a fix was tried and not kept. A receiver-side handshake kick was rejected. (2026-10-31) | Encrypted packets are not signature-checked. A receiver that started a handshake on a failed decrypt would act on a sender ID anyone can set. An upstream test also pins "no handshake without a session" on purpose. | The receiver-side kick. | Deferred to its own upstream change. Until then the first message after a restart may be lost. |

#### What Phase 10c-2a-2a changed

- `MeshService` is eight role interfaces plus `delegate`. Seven members left it (see above).
- `WearMeshService` implements four roles. Its send helpers and `WearPeerIdentityState` work against the roles.
- `PowerManager` and `LiveVoiceManager` take their dispatcher by injection.
- Behaviour is unchanged: same bodies, threads and log lines.
- Tests: `SendHelpersTest` and `WearPeerIdentityStateMeshRolesTest` (watch, with fakes of the roles), `MeshRoleImplementationsTest` and `WearApplicationInjectsLazilyTest`. Three `never()` checks on `sendFilePrivate` in `MediaSendingManagerMigrationTest` were dropped, since the type now rules the call out.

#### Follow-ups from Phase 10c-2a-2a

For 10c-2a-2b: the identity component, the holder, the mesh factory, `MessageRouter`, `ChatSessionMesh`, `SessionMeshSource`, and the scope and stale-reference tests.

For 10c-2b: the chat session in the scope, and panic as a rebuild.

Owed: the Mesh Lab on physical devices, as for 10c-1 and 10c-2a-1.

#### Upstream sync after Phase 10c-2a-2a (2026-11-08)

Merged #1027 (panic clears the unsent text) and #1042 (password-protected channels fail closed). The refactor had moved the code both change, so they were ported by hand:

| Upstream change | Where it went |
|---|---|
| Sends to a protected channel are refused, with no plaintext fallback | `MessageSender` |
| Stale mention selections are rejected | `ChatViewModel`, on `SelectMentionSuggestion` |
| Confirming a panic clears the suggestions | `ChatViewModel`, on `PanicClear` |
| Confirming a panic clears the text | `ChatScreen`, which owns the composer text |
| `ChannelPasswordPrompt` | `ChatScreen`, taking values from `uiState` instead of `StateFlow`s; the ViewModel closes the prompt |

Tests: `PasswordProtectedChannelRobolectricTest` moved to `:core:chat`, `ChannelPasswordPromptRobolectricTest` stayed with the screen in `:feature:chat`, and the nine opt-in composer tests (`-PcomposerUiTests=true`) now reach the session through `ChatSessionTestAccess` and swap its mesh with `ChatSessionMesh.replace`. All nine pass on an emulator. `PasswordProtectedChannelSendTest` is new: upstream's tests do not cover the refusal in the plain send path, and it fails when that refusal is removed.

### Phase 10c-2a-2b: the identity scope

#### Phase 10c-2a-2b decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (P) Every identity-bound mesh and Nostr class lives in a Hilt `IdentityComponent` (a child of `SingletonComponent`), reached through an `IdentityHolder`. The watch uses it too. Nothing ends an identity yet. (2026-11-08) | Panic and a changed identity need one place that owns what an identity holds. | Moving only the mesh group. | A holder-backed binding costs one more hop per lookup. |
| (R) Code outside the scope reaches an identity class through a `@CurrentIdentity`-qualified `@Provides` that asks the holder. There is no unqualified binding. | Dagger resolves a child component's request for a type to an ancestor's explicit binding first. An unqualified holder-backed binding in the singleton component would be handed to scope members too, and building it would call the holder for itself: it compiles and recurses at run time. A qualified binding is a different key, and a direct reference to the type outside the scope is a compile error. | Unqualified bindings. | Every bridge is a method on an entry point plus one qualified `@Provides`. |
| (R) Process-lived consumers (`@Singleton`s, `Application`, services, activities) take `@CurrentIdentity Provider<X>` and call `get()` when they use it, never in a constructor or `init`. Chat-session classes and ViewModels may take a direct `@CurrentIdentity X` until 10c-2b. | Nothing ends the identity before 10c-2b, and 10c-2b moves the chat session inside the scope, where a `Provider` would be churn the next run undoes. A `get()` during construction can ask for an identity class that is being built (Dagger throws "Scoped provider was invoked recursively"). | `Provider` everywhere. | `IdentityScopeRulesTest` fails on a violation. Its `PENDING_CHAT_SESSION` list is the allowance. |
| (R) `MessageRouterHolder` is an identity-scoped class with `getOrAttach(mesh)` and `peek()`, which keep what `getInstance` and `tryGetInstance` did: re-point the router at the current mesh, and return it only if built. The router is built through an assisted factory with no parameters, so only the holder can build one. | Reading the mesh itself would recreate a mesh after the foreground service stops. The plan said an unscoped `@Inject` constructor, but that lets any class inject a second router with no mesh, scheduler or listener, and it compiles. `BluetoothMeshService` already uses the factory form. | Unscoped `@Inject`. | A class can still take the factory and call it on purpose; the scan lists the router with the other built-inside classes. |
| (P) `VoiceWaveformCache` was the pilot. `PrivateMessageArrivalOrder` and `GeohashAliasRegistry` moved with the stores in the last commit. | A `@Singleton` cannot take an identity type directly, so a class can flip only when its `@Singleton` consumers do. `AppStateStore` and `ContactDirectory` take those two, and they flipped last. | Temporary `Provider`s that the last commit would undo. | Order follows the consumer graph. |
| (P) `PowerManager` collects direct peers from `start()`, not `init`, and a new `SpamFilter` applies the saved settings in its constructor. | Building a process singleton must not touch an identity class, and a rebuilt filter must not start with defaults. Both clients already called `start()` first in `onCreate`, pinned by order tests, and the filter had its settings pushed in once at start-up. | Reading in `init`; a separate reset call. | Behaviour is unchanged. |
| (D) The injected identity `CoroutineScope` waits for 10c-2b, with the teardown. (2026-11-08) | A scope nothing cancels only adds constructor changes. | Adding it now. | `MessageRouter`'s scheduler scope and the like stay as they are. |
| (P) `WifiAwareController` is in the identity scope. | It holds connected peers. | Process scope. | None. |
| (P) `IdentityScopeRulesTest` fails when a singleton is not on a short process-wide list with a reason, when an identity type is taken outside the scope without a `Provider`, and when a stateful `object` is not listed with a reason. `NoStaticMeshAccessTest` scans `MessageRouter` like the rest. | A class that holds identity data should have to declare its lifetime. | Review alone. | It is a text scan. Two identity objects, the pin epoch counter and the notification manager registry, are listed as unmoved. |

#### What Phase 10c-2a-2b changed

- `IdentityComponent`, `IdentityHolder`, `@IdentityScoped` and `@CurrentIdentity` are in `:core:mesh` (`identity/scope`). Each module that owns identity classes has a bindings file with its entry point and qualified bridges.
- Identity scope now holds the mesh classes and their holders, the router and its holder, the stores and favourites, the voice, transfer and verification classes, the Nostr identity, transport, location and bookmark classes, and the watch's mesh and chat state.
- `MessageRouter.getInstance` and its companion state are gone, and so is the exemption for them.
- Behaviour is unchanged: first use, start-up order and the panic wipe's steps are as before. The watch still creates its mesh only after permissions are granted.
- Tests: the final gate on the docs commit gave JVM 1496 tests (from 1455) with 0 failures (3 skipped). Device suites: app 79 (from 74); chat 6, settings 5, location 4, share 3 unchanged. The opt-in composer suite (`-PcomposerUiTests=true`) was 9 of 9 on the stores' commit. Whole-branch Codex and the whole-branch review found no Critical or Important issues. The holder, the scope rules and one instance across entry paths are covered by `IdentityHolderTest`, `IdentityScopeRulesTest` and `IdentityScopeWiringTest`. The watch was not run on a device.
- Two emulators over simulated Bluetooth, synthetic data: public message and private message with a read receipt after a cold start, a favourite surviving a restart, reply and mark-read from a notification, reboot auto-start, and panic followed by messaging the new identity all passed. All 13 Mesh Lab scenarios passed on those emulators, launching through `am start` because the tool's `monkey` launch fails on these images. Physical-device Mesh Lab remains owed.
- One physical phone, with no peer nearby: installing over the previous build kept the same identity, the real Bluetooth stack came up (GATT server active, scanning) under the foreground service, and the identity and the chat screen survived a forced process stop and an Activity recreation. No crash or scoped-provider error appeared. Delivery between real radios, voice notes, location channels and notes were not exercised on it.
- A `ForegroundServiceStartNotAllowedException` from `MeshForegroundService.start`, called by `BootCompletedReceiver`, crashed the process once on an emulator. The receiver and that path are unchanged here, so it predates this phase. It is logged in §5.

#### Follow-ups from Phase 10c-2a-2b

The list for 10c-2b is done, and Phase 10c-2b below records how. One item stays open: Wear screens take identity instances as composable parameters at composition, which goes stale if switching ever reaches the watch. The watch has no panic, so nothing switches today.

Deferred from reviews, none blocking:
- `IdentityScopeRulesTest`'s state pattern misses some collection types, its parser skips some shapes silently, and a process-wide entry that loses `@Singleton` stays listed.
- A few comments still say "one instance per process" for classes now in the scope.

Owed: the Mesh Lab on physical devices, as for 10c-1 and 10c-2a-1.

### Phase 10c-2b: the chat session inside the identity, and panic as a rebuild

Since 10c-2a-2b every class holding identity data sat in the identity scope, but nothing ended that scope. Panic cleared each class by hand, so a class someone forgot stayed populated (the bug class behind upstream PR #1022). Now panic throws the identity's objects away and builds new ones, so what is only in memory cannot survive by being forgotten.

#### Phase 10c-2b decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) The app never restarts on panic: same process, same Activity, no splash or onboarding. (2026-11-09) | The one requirement the developer named. | A process restart. The restart fallback for a wipe that cannot confirm belongs to 10c-3. | Everything process-lived has to follow the new identity. |
| (D) Panic resets the back stack to the main chat and discards the ViewModels; new ones are built against the new identity. (2026-11-09) | ViewModels can keep taking identity objects directly. | The earlier design's screens observing the holder's current identity. | An open private chat or sheet closes on panic. Accepted. |
| (D) The session sits inside the identity and ends first: when the person leaves the app, or when panic ends the identity. (2026-11-09) | Leaving and reopening stays as it is today. | Moving the session into the identity with the identity's lifetime. | Two scopes and two holders. |
| (P) A second qualifier, `@CurrentSession`, next to `@CurrentIdentity`. | The two lifetimes differ, and `IdentityScopeRulesTest` has to tell them apart. | Reusing `@CurrentIdentity`. | One more bridge per session class. |
| (P) A wipe that cannot prove the conversation database erased keeps today's path: no rebuild, gate closed, incomplete shown. | A new store would open the gate over a database that may still hold conversations, and a rebuilt session would restart geohash and Nostr, which have no gate. | Rebuilding anyway. | The transports can start again as they can today. 10c-3 replaces this path. |
| (P) The hand-written clears that blank the screen stay, and so do the ones that write storage. The purely in-memory ones go. | Nobody under duress should see messages during the slow stages. This is the one place the rebuild does not replace a clear. | Removing them all. | Which wipe stages report success is unchanged. |
| (P) The wipe runs on a process-lived scope (`PanicRunner`), not the session's. | Otherwise panic cancels itself when it ends the session. Today, swiping the app away mid-panic stops the wipe. | The session scope; a retained scope. | A small behaviour change, in the safe direction. A process kill mid-wipe is 10c-3's. |
| (R) Ending and building run in one non-suspending main-thread block, and the new session is built before the per-identity start-up runs. | Nothing can observe a half-swapped identity, and the Activity's collector must find the new session rather than build a stray one. | Suspending between the steps. | Measured at 69 to 110 ms for start-up, mesh start and session start on an emulator. |
| (R) Screens rebuild through a retained `GenerationViewModelStoreOwner` and `popTo(ChatRoute)`, with the screens key saved. | A plain reset would put chat over onboarding. The key saved means a process death after a panic still claims its saved state. | `resetTo`; `LocalViewModelStoreOwner`. | The order of ViewModel clearing against the session's end at leaving follows listener order; no harm found. |
| (R) Sends in flight stop when panic starts. | A geohash send still mining can outlast the wipe and publish during it. | Relying on the identity scope's cancel at the end. | Covers the current session's sends only. See the follow-ups. |
| (R) While a panic runs, a reopened app gets a session that is already over, and a second panic is refused. | Reopening must start nothing on the identity being wiped. | Waiting for the wipe in the Activity. | Reopening during a wipe shows no new session until the wipe ends. |
| (R) Each mesh's notification manager closes at identity teardown. | The managers for DMs with no UI stayed in the process-wide registry with sender names and message text in their previews. | Closing only the session's. | A late callback after the person leaves is dropped. |
| (R) A blank frame is a checked requirement, fixed rather than recorded as a limit. | See "The blank frame" below. The design calls the blank the reason the early clears stay. | Accepting about 0.4 s of messages on screen during the wipe. | `/clear`, and switching to an empty conversation, lose the list's fade. |
| (R) The rest of this phase's device checks ran on two scratch emulators. | Another tool kept taking the foreground on the usual one, so a tap could have landed in another app. | Sharing it. | A scratch AVD is left on disk. |

Clears removed: `spamFilter.reset()` on the rebuilt path. `AppStateStore.resumePrivateConversationsAfterPanic` had no caller left and is deleted, so the old store's gate cannot reopen.

#### What Phase 10c-2b changed

- `ChatSessionComponent` is new, a child of `IdentityComponent`, reached through `ChatSessionHolder`. `@ChatSessionScoped` covers every class that holds session data or runs session jobs.
- `IdentityHolder` can end an identity and build the next. `IdentityTeardown` runs what each class registered, and a cleanup that throws does not stop the others. An injected identity `CoroutineScope` is cancelled with it.
- `IdentityStartup` holds what `BitchatApplication` did for one identity, so it runs again for each new one. `PENDING_CHAT_SESSION` is gone, and `IdentityScopeRulesTest` now fails on a scoped class that owns a scope, timer, executor or receiver with no teardown, and on a zero-argument `replace()` outside `PanicClear`.
- `MainActivity` keeps nothing of the identity's across a panic. `PowerManager` and the foreground service follow each identity's store. The location managers, the router and the classes with their own scopes clean up at teardown.
- `:wear` has no panic and its holder never ends an identity.
- Protocol, packets, Noise, crypto and relay behaviour did not change.

#### The blank frame

The design says to blank the screen before the slow wipe stages. `BlankFrame` waits two frames for that, and its test passed. A screen recording showed the messages at full opacity under the old nickname until the new identity's screen appeared, about 0.4 s with 6000 empty seeded files on an emulator. On the base branch the dialog stays up until the block ends, so this phase had made the messages more exposed, not less.

Two reasons. Rows removed from a `LazyColumn` keep fading and are still drawn after they leave composition, and then the main thread blocks. And the test could not see it: first it read the store at draw time, and a rewrite that read the Compose semantics tree also passed, because those rows have already left the tree. Neither is the screen.

- The fix: `MessagesList` composes no list when it has no messages, and a spacer keeps its place. `BlankFrame` is unchanged.
- The test now copies the window's pixels with `PixelCopy`, on its own thread, around the seeded text while the wipe runs. It asserts the text was drawn first, and requires a sample of the old session in which the text is gone, and that no later sample of the old session shows it again (18 of 24 and 8 of 10 samples were blank on the fix). The sampler is always stopped, even when the test fails. Against the old list every sample still showed the text: 19 of 19 from the main chat, 8 of 8 with a private chat and a sheet open.
- A new screen recording on the fix, with the same seeding, read frame by frame: messages under the dialog's scrim until the tap; from the next frame the messages are gone under the old nickname, for 19 frames (about 0.3 s) while the dialog fades away; then the new nickname, then the "wiped" line. No frame during the block shows a message. Same process and window, no restart events. The wipe took 382 ms.
- A plain `/clear` on the same emulator, with messages from both sides afterwards: both showed, in the same process and window, with the same identity.

#### Checked on two emulators, synthetic data

Simulated Bluetooth. Two other bitchat instances, not started for this check and not touched by it, shared the simulated radio and can relay and take connection slots, so no result here is from an isolated pair.

- Panic from the main chat, a second panic straight after, panic with dark-mode recreation before it, and a notification reply right after: same process and same main window across the panic, no Activity create or destroy, no restart events, a new peer ID each time, and messaging worked afterwards.
- Swiping the app away mid-panic (through task removal, not the Recents gesture): the wipe finished, seeded media was gone, and reopening showed chat with no onboarding.
- Leaving through Home, Back and task removal, then reopening: peer ID, nickname and messages kept. The same run was not repeated on the base branch.
- Dark-mode recreation changes the window, as it should, and keeps the identity.
- Panic's time on the main thread with seeded media: 200 synthetic files of 100 KB, 247 to 291 ms and no ANR, against 287 to 322 ms with none. Media makes no visible difference at this size on emulator storage, which says nothing about real flash. With 6000 empty files it was about 400 ms.
- No full-screen flash, splash, onboarding or black or white frame in any recording.
- Mesh Lab, `scenario all`, 13 scenarios, launching through `am start` because the tool's `monkey` launch fails on these images: 12 of 13 on the first run and 13 of 13 on the second. `identity_reset`, the scenario closest to this work, passed both times. The one failure is `media_private`, in both builds. See section 5. Repeated from a clean setup, it failed 3 of 11 runs here and 2 of 10 on the base branch, so it is not from this phase. A full run on the base branch passed 13 of 13.
- Each panic left the other device holding about one more connection until it reached its 8-slot limit, on the base branch too. See section 5.
- Old public messages reappear about two seconds after a panic, because a nearby peer syncs them back. The base branch's recording shows the same. See section 5.

Tests: JVM 1626 (from 1496) with 0 failures (3 skipped), and lint green. Device suites: app 102 (from 79), chat 6, settings 5, location 4, share 3, and the opt-in composer suite 9 of 9. One app test failed: `MessageSendingTest.aCommandRunsInsteadOfBeingSent` expects "no one else is around", and the two other instances on the simulated radio were around. `/w` sent by hand with three peers in view lists them. The test passed with the mesh off (the composer variant's full run, 111 of 111) and straight after a panic, before any peer was seen. The test is unchanged in this phase and will fail on any device with a peer nearby. `PanicAroundTheScreenTest` leaves no seeded files, and the suites after it pass.

#### Not verified

- Real Bluetooth between phones, and Mesh Lab on physical devices.
- A panic on a real phone, including what the blank looks like there. This check is owed and is the developer's to run. It must never run on a phone holding a real identity.
- The watch on a device.
- How long the wipe takes on slow flash. All timing above is from emulators.
- A wipe that fails, on a device. That path has JVM tests only.
- A panic started from a private chat with two devices. The UI offers no way to start one there, so only the device test covers a private chat being open.

#### Follow-ups for 10c-3

- Three wipe stages report nothing into the panic's result: `dataManager.clearAllData()`, `conversationListPreferences.clearAll()` and `clearAllNotifications`. A throw from the mesh's `clearAllInternalData` also does not mark the wipe incomplete. Both go with the confirm stage.
- Two companions stay static, with the reason in the rules test: `SecureIdentityStateManager`'s pin epoch and `NotificationManager`'s `liveManagers`.
- `DebugSettingsManager`'s buffers hold peer IDs and nicknames and belong on the wipe list.
- An earlier session's geohash send can still publish during a wipe. `cancelSendsInFlight` covers only the current session's sends.
- `NostrDirectMessageHandler` can write favourites after the wipe. The race exists today too.
- On the failed path, mesh notification managers keep their previews, as they do today.
- There is no automated process-death test. The check was scripted on an emulator once.
- Storage wipe by location, the keep-list, the panic marker and start-up re-check, the restart fallback and the system-side registry were always 10c-3's.

Deferred from reviews, none blocking:
- A double build failure during a swap leaves the identity with no start-up, and a late cleanup registered after teardown drops its failure.
- `ConversationRepository.close` blocks its caller until the writer drains.
- `NostrBackgroundRuntime` unsubscribes before it cancels.
- The closing order of `MessageRouterHolder` has no ended flag.
- The order of stopping before closing is not tested on the real graph, and a comment in `MainActivity` does not match the observed timing of clearing ViewModels.
- Test quality: `IdentityScopeWiringTest` and `PanicScreensRebuildTest` reflect on private fields, the latter on a library's; the construction scan skips `@Provides` bodies; some fixtures and cleanups were never seen red or are only smoke-tested; `exitingMentionRowsCannotRefillInput` can pass without clicking; `PanicAroundTheScreenTest` has a fixed sleep; the new `MessagesListFollowTest` case passes with the whole fix reverted (it guards what the old list already did), and fails when only the spacer is kept, so it covers the two additions beside it. The stale-layout worry after a refill did not reproduce on the JVM: a test recording every scrolled-up report across clear and refill passed on the code as it is, so the code is unchanged.

Owed: the Mesh Lab on physical devices, as for the phases before it, and the phone check of a panic.

### Phase 10c-3: the panic wipe fails closed

Since 10c-2b a panic ended the identity and built a new one, but storage was still wiped by a hand-written list: a file, preference or database nobody added to it stayed on disk. Now panic wipes the sandbox by location, everything except a short keep-list, so what nobody listed is gone by default. A wipe that cannot prove itself restarts the process, and the next launch finishes it before anything reads storage.

#### Phase 10c-3 decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) A wipe that cannot confirm restarts the process, and only then. A normal panic never restarts: same process, same Activity. The relaunch finishes the wipe before anything opens storage. (2026-11-14) | A process that could not prove its storage wiped must not build a new identity over it. | Restarting on every panic; carrying on with the old storage. | A restart shows the splash. It should never happen on a working phone. |
| (D) Old public messages synced back by peers after a panic are not fixed. (2026-11-14) | Panic cannot erase what other devices hold. | A sync change, which is a protocol change. | Stays in section 5 as upstream behaviour. |
| (D) Tor state (`files/arti`) is kept as "network route only". (2026-11-14) | It holds relay guards and directory caches, no user data, and a fresh bootstrap is slow. | Wiping it. | A guard set survives a panic. |
| (D) The keep-list is settings and onboarding only: theme, UI mode, Tor mode, onboarding and skip flags, live-voice toggle, background and boot settings, spam-filter and proof-of-work settings, debug toggles, the downloaded relay list, the cached APK, its release metadata and cooldowns, and WorkManager's database. Everything else is wiped, the hotspot name and last peer-alert time included. (2026-11-14) | Settings survive and onboarding is not shown again; nothing that says who the person is or who they talked to stays. | A longer list. | A setting someone forgets to add resets on panic, which is the safe direction. |
| (P) A kept preferences file is kept by key, not whole. On panic it loses every key not on its list. | A contributor who puts user data in `bitchat_settings` leaks nothing; a missed setting only resets. | Keeping whole files. | `KeepListRulesTest` checks every kept key is a literal in main sources. |
| (P) The wipe covers every child of the data directory except `lib` and `code_cache`, plus the app's external files and cache directories, and deletes every Keystore alias. | A future `getDir("x")` folder is wiped by default. `lib` and `code_cache` are system-owned and hold no user data. | Only the folders the design named. | — |
| (P) The wipe runs on the main thread inside `IdentityHolder`'s lock. | No reader can build an identity mid-wipe. Panic already deleted media on the main thread. | A background wipe. | Measured below: about 0.4 s for 6000 files on an emulator. |
| (P) A teardown cleanup that fails also restarts. | A failed cleanup can leave a job or receiver of the old identity alive; a fresh process is the only structural fix. | Reporting only. | — |
| (P) No identity is built while the marker is pending. If the start-up re-check cannot confirm either, the first identity read crashes the process instead of opening unwiped storage. | Fails closed. | Building over it. | A crash loop in a case that should never happen (the app cannot delete its own files); it leaks nothing. |
| (P) The clipboard counts as a system-side effect. Panic clears the primary clip, even if it came from another app. | Messages and fingerprints can be copied. | Leaving it. | A clip from another app is lost too. |
| (R) `SecureIdentityStateManager` takes a process-wide storage lock for the panic, and every manager built before the panic took the lock refuses every write: the fence runs inside the lock. `panicReset` is narrowed to releasing the relay subscriptions. | Found on an emulator: ended-session work built a manager mid-wipe and the new identity then crashed reading its own keys, after a wipe that had confirmed; and a registry `apply()` landed after the wipe had listed the files. Review then found that a fence run before the lock missed a manager built in between. | Waiting for old work to drain. | A write from another thread during the panic is refused and never blocks. Building a manager on another thread does block, for as long as the panic holds the lock: the identity's end, the wipe and the next identity's build. So nothing inside the lock may wait on work that builds a manager. Review checked the lock for deadlocks and found none. |
| (R) A launch that finds a pending marker runs the panic's system-side cleanups again, right after Hilt is ready. | A kill after the marker is written but before the panic reaches its cleanups left notifications, conversation shortcuts and the clip in place; the launch only wiped the sandbox. | A second persisted flag for the cleanups. | Only Hilt's own start-up runs before them. After a restart the cleanups run twice, which is harmless. |
| (R) The relay list download's temp file in `cache` (`relays_*`) is on the keep-list. | The refresh is process-wide and a panic does not stop it, so an overlapping download made a normal panic restart. | Moving the temp file into `files`. | It holds the public relay list only. |
| (R) A build failure after a confirmed wipe also restarts. | A failed build means old work may still be writing. The marker stays set, so the next launch wipes once more. | Showing incomplete and carrying on. | Not in the design's list; fail-closed. |
| (R) A marker that can never be written, together with a wipe that cannot confirm, leaves the next launch with nothing to re-check. Accepted. | A sandbox that refuses a file write in `no_backup` is outside what the app can fix. The restart still runs. | A second marker location. | A known gap. |
| (R) The panic contract test is an instrumented test on the real application graph, not a Robolectric one. | No JVM test in the app runs the real Hilt graph, and adding Hilt's Robolectric testing would be a dependency change the plan did not ask for. | Robolectric with a new test dependency. | It runs on a device, so CI does not run it. |

#### What Phase 10c-3 changed

| Panic | Before (10c-2b) | After (10c-3) |
|---|---|---|
| Storage | A hand-written list of clears; a forgotten file stayed | Everything outside the keep-list is deleted; a forgotten file goes |
| Keys | Cleared through named calls on each storage class | Every Keystore alias deleted; the new identity makes new keys |
| A kill mid-wipe | Nothing finished the wipe | A marker is written first; the next launch finishes the wipe before anything reads storage |
| A wipe that cannot confirm | No rebuild, gate closed, "incomplete" shown, same process | The process restarts with the marker set and wipes again at launch |
| Notifications, shortcuts, clipboard, debug buffers | Notifications cleared by hand; the rest stayed | Each registers a panic cleanup; `PanicSystemSideRulesTest` fails on a system-side API with no cleanup or reason |
| "Completed" | Every listed stage confirmed | The sandbox walk found only keep-list entries and no Keystore alias, and every cleanup confirmed |

- `:core:mesh` `panic/`: `SandboxWipe`, `KeepList`, `PanicMarker`, `StartupWipe`, `PanicCleanup`, `SandboxWipeRunner`, `ProcessRestarter`. `IdentityHolder.replace` runs the wipe between ending one identity and building the next.
- `BitchatApplication` finishes a pending wipe before Hilt starts, and when it found one, runs the panic's system-side cleanups right after Hilt starts. No content provider in the merged manifest reads bitchat state before that.
- Debug builds wrap the wipe with two test hooks, `panic.failWipeOnce` and `panic.pauseBeforeWipe` (released by `panic.resume`, or a timeout), and log each panic stage's duration (stage names and times only).
- `:wear` has no panic, as before. This stays a known gap.
- Images the person saved to Downloads from the image viewer are left alone and allowlisted with that reason. An open question the developer may raise later.
- Protocol, packets, Noise, crypto and relay behaviour did not change.

#### Checked on two emulators, synthetic data

Simulated Bluetooth, two emulators started for this check.

- Panic from the main chat: same process and Activity, no restart or Activity events, a new peer ID, "all data wiped" shown, and private and public messages both ways afterwards.
- The sandbox afterwards: every file is a keep-list entry or was written by the new identity after the wipe (its keys, nickname and empty conversation database). Seeded media and favourites gone. Theme, UI mode and Tor mode set beforehand survived. No onboarding.
- `panic.failWipeOnce`: the process restarted once, came back on a fresh identity, and the sandbox was clean with no marker left.
- `panic.pauseBeforeWipe` then a force stop: the marker and seeded media were still there after the kill; the next launch removed both before the first screen and came up on a new identity. `panic.resume` from adb released the pause while the main thread waited, and that panic completed in the same process.
- Panic time with 6000 seeded media files of 4 KB: the wipe took 408 to 417 ms. From "Panic started" to "Panic completed" it took 1.86 and 2.20 s; with no media, 0.99 and 2.04 s. The longest main-thread block was 1.0 to 1.2 s. No ANR. The developer decides whether this needs the bulk-media change.
- Where the time goes, measured afterwards with a debug-only log of each stage's duration, in eight panics on one emulator (four the first after a cold start, four later; three with 6000 media files): 1.03 to 2.86 s in all. The first panic after a start took 1.03, 2.19, 2.23 and 2.86 s, the later ones 1.26 to 1.86 s.

| Stage | Time (ms) |
|---|---|
| Marker write, reading the old objects, location off, sends cancelled, mesh data cleared | 13 to 69 |
| Clearing the conversation store (waits on its database writer) | 35 to 1045; 152 to 1045 in the first panic after a start (the 1045 also holds the sends and mesh data, logged as one stage in that run) |
| Clearing the screen's state, then the blank frame | 94 to 623 |
| Relays released, old mesh stopped, session ended | 26 to 160 |
| Waiting for the storage lock | 0 to 1, once 59 |
| Ending the identity (its teardown, the conversation database's close included) | 80 to 347 |
| The sandbox wipe | 55 to 270 with no media; 382 to 583 with 6000 files |
| Building the next identity and the system-side cleanups | 6 to 43 |
| The new identity's start-up (its stored state and services) | 94 to 427 |
| The new mesh built (its keys and encrypted storage) | 94 to 213 |
| Mesh and session started | 35 to 90 |

  The two largest and most variable stages are the conversation store's clear and the blank frame, which both ran in 10c-2b as well. The storage lock is not a cost. The wipe is 0.06 to 0.58 s of it. The new identity costs 0.2 to 0.7 s, and the first panic after a start is not always the slowest, so a cold JIT alone does not explain it. Why these stages take longer than 10c-2b's 0.38 to 0.46 s total was not measured.
- Mesh Lab, `scenario all` from a clean setup, launching through `am start` as before: 12 of 13. `ptt_broadcast` failed once (the receiver timed out waiting for the live burst) and passed both reruns alone. Push-to-talk has no change in this phase. `identity_reset` and `session_recovery` passed.
- A panic started from a private chat: the private chat has no logo, so the UI cannot start one. The device test with a private chat and a sheet open covers that case.

Tests: JVM 1679 (from 1626) with 0 failures (3 skipped), and lint green. Device suites: app 103 (from 102, the new panic contract test), chat 6, settings 5, location 4, share 3, all passing.

After the review fixes, the full gate on the final code: JVM 1684 with 0 failures (3 skipped), lint and both assembles green; device chat 6, settings 5, location 4, share 3. The app suite crashed once in that gate, in `PanicAroundTheScreenTest`'s panic while stopped: the new identity's first encrypted preferences failed to decrypt their keyset (`AEADBadTagException`), the class the storage lock was added for. The same test then failed every time on the base commit and on the fixes alike while the app kept the data that run left, and passed on a fresh install: the class twice, and the whole app suite, 103 of 103 with the panic contract test. So the fixes did not cause it. The crash was closed afterwards; see "Closed after 10c-3".

#### Closed from the 10c-2b follow-ups

- The three wipe stages that reported nothing are gone; a throw from the mesh's `clearAllInternalData` now marks the panic incomplete.
- `DebugSettingsManager`'s buffers are cleared by a panic cleanup.
- Storage wipe by location, the keep-list, the marker and start-up re-check, the restart fallback and the system-side registry.
- The wipe that fails is now checked on a device, not only on the JVM.
- Closed in the final review: the fence now runs inside the storage lock; a directory the confirm walk cannot list is a leftover, not empty; cached long-lived conversation shortcuts are removed too, not only the dynamic ones.
- `SecureIdentityStateManager`'s companion is now process-wide on purpose (the panic's fence and lock), not an identity object left behind.

#### Follow-ups for 10c-3

- Plain-preferences writes from old-session work after the rebuild are not proven absent (the same class as `NostrDirectMessageHandler` writing favourites after a wipe).
- Secondary external volumes, `obbDir` and device-protected storage are not wiped. The app writes none of them today.
- The marker is set again just after the holder's lock is released, so a read in that window can build an identity before the process restarts. The next launch wipes again.
- `NotificationManager`'s `liveManagers` stays a static companion.
- An earlier session's geohash send can still publish during a wipe.
- Test quality: some keep-list key checks are weak for generic names; the clipboard check passes on an unreadable clip; `MeshSingletonIdentityTest`'s name over-claims; one contract seeding failure was not root-caused.
- Panic takes 1.0 to 2.9 s on an emulator, against about 0.4 s in 10c-2b. The split by stage is above. (D) Accepted; to be measured on a real phone later (see "Phase 11: audit").

Owed: a panic on a real phone, on a throwaway install, which is the developer's to run; Mesh Lab on two physical phones; the watch.

#### Closed after 10c-3

- **The `AEADBadTagException` after a panic.** The wipe deletes the master key that every encrypted preferences file shares, and two generators can then create it: androidx's `MasterKey.Builder`, under its own lock, and Tink's, under another, when a keyset is built with no key there. A manager built its master key before taking the storage lock, so one started while a panic held the lock had the old key, waited, and after the wipe built its keyset through Tink's generator while another thread was in androidx's. The later key replaced the one the keyset had just been encrypted under, and that storage failed on every later launch until a reinstall. The master key is now built inside the storage lock with the keyset, through one helper that `EncryptionService` uses too; until now it took no lock at all.
  - Evidence on an emulator: a probe that held the lock, started a manager, deleted the file and the key, then started a second manager broke the storage in 3 of 100 rounds (7 of 100 threw, `KeyPermanentlyInvalidatedException` or `AEADBadTagException`), the same stack as the gate's crash. On the fix: 0 of 300.
  - Test: `PanicContractTest.aManagerStartedDuringTheWipeBuildsNoKeyUntilThePanicLetsGo` starts a manager inside a real panic's wipe and checks no master key exists until the panic lets go. Before the fix it failed (the key was there); it passes on the fix.
  - Only this branch had the bug: before 10c-3 nothing deleted the master key while the app ran. A device that already holds the broken pair stays broken until a panic or a reinstall; none outside test emulators does.
- `PanicMarker.set()` syncs the directory too, and `clear()` returns whether the marker is gone; the panic logs a failed clear, and a marker left in place keeps failing closed.
- The clear methods with no caller anywhere, tests and debug sources included, are removed. Kept because a test calls them: `clearPersistentIdentity`, `clearIdentityData`, `FileUtils.clearAllMedia`, `ConversationListPreferences.clearAll`. Kept because removing it takes a listener callback with three implementations along: `clearAllFavorites`.
- The start-up order rule requires the wipe as the first statement of `onCreate`, with block and trailing comments stripped.

Gate on the final code: JVM 1685 with 0 failures (3 skipped), lint and both assembles green. Device, on a fresh install: app 104, chat 6, settings 5, location 4, share 3. Then the same suites again over the data that run left, with no reinstall, which is how the crash used to come back: app 104, chat 6, settings 5, location 4, share 3, all passing.

#### Upstream sync after Phase 10c-3 (2026-11-26)

Merged upstream to `f6bfceac` (30 commits). Every behaviour fix is kept; where the refactor had moved or rebuilt the code, it was ported:

| Upstream change | Where it went |
|---|---|
| Panic reconnects Nostr relays, but only after a confirmed wipe (#1040: `1e3515dd`, `c1b2e1be`) | `NostrRelayManager.clearAllOnPanic()` now leaves relays offline (connectivity undesired) and on the default relays only (upstream's `forgetSessionRelays`). The reconnect is the next identity's start-up (`NostrBackgroundRuntime.initialize` connects), which `PanicClear` runs only after the sandbox wipe confirmed; a wipe that cannot confirm restarts the process instead. So upstream's `reconnect` flag and its withheld-retry flag are not needed. `GeohashSession.panicReleaseRelays` calls the new `NostrBackgroundRuntime.releaseSubscriptions()`, which only unsubscribes; the old `resetSubscriptions()` re-subscribed the identity being wiped and is gone. |
| Private chat text goes to its own chat or is refused (#1043) | `MessageSender.sendToConversation`, used by `PrivateChatViewModel`. A chat that cannot start, or whose peer is blocked, closes its screen (`PrivateChatEvent.Close`). |
| `/msg` opens the private chat sheet | Taken as opening the private chat screen, the only place a private chat is shown. `MessageSender` reports the new selection (`onSelectedOther`). Typed in the main chat, the chat screen opens that chat (`ChatEvent.OpenPrivateChat`); typed in an open private chat, the screen replaces itself with it (`PrivateChatEvent.OpenConversation`); both call `openPrivateChat`. Upstream's tests kept, asserting the selection; the sheet assertion became `PrivateConversationSendRobolectricTest`'s check of the report. |
| Wear DM send survives the input screen closing (#1036) | The helper stays generic over the mesh roles; the caller passes the mesh's scope and `isRunning`. |
| Foreground service retries and Wi-Fi Aware recovery (#1007), Wi-Fi Aware restart after an unexpected stop and the coalesced cooldown (#1044) | `MainActivity` delegates to `ForegroundServiceStartRetry` (a lifecycle observer, so the retry runs once the lifecycle reads STARTED). `WifiAwareController.publishStarted` returns a `PublishOutcome`, so a reused instance that deferred its start is kept, not stopped. |
| Tor port rebuild and self-cancelling restarts (#1039, #1048) | `ArtiTorManager` keeps its injected constructor and gains an internal one taking the scope and proxy factory, for the tests. |
| Store-and-forward manager removed | Removed: the refactor had only moved it. |
| BLE scan recovery, offline peers greyed, globe detail while dragging, Play target API check, relay data | Merged as is (the globe in `:feature:location`). The conversation swipe fix was already on this branch, keyed on `settledValue`; upstream's `onDismiss` version was not added on top. |

Tests: upstream's tests were ported to this branch's classes, beside their subjects. The two `MainActivity` Robolectric tests became `ForegroundServiceStartRetryTest`, because the app's JVM tests do not build Hilt entry points; for the same reason `MeshForegroundServiceStartupTest` sets the injected fields by hand and drops upstream's `onCreate` check. The relay test fails against the old `clearAllOnPanic`. `PanicClearTest` gains the order upstream's intent needs: relays released before the identity ends, the reconnecting start-up only after a confirmed wipe, none after one that failed.

### Phase 11: audit

(D) Phase 11 was narrowed to a short audit of the finished refactor. Sharing Wear's UI with the
phone is deferred and out of scope for this refactor. (2026-11-26)

#### Fixed

| ID | Before | After |
|---|---|---|
| P11-01 | The chat file picker took a persistable read grant on every picked document. The system keeps that grant outside the sandbox, so it survived a panic. | The copy runs inside the picker's callback, where the picker's own grant is enough, so no grant is persisted. `PanicSystemSideRulesTest` now treats `takePersistableUriPermission` as a system-side API. |
| P11-02 | `FileShareDispatcher`, a stateful `object` that nothing dispatched through, was still installed by `ChatScreen`. | Deleted, with its `LaunchedEffect` and its allowlist entry. |
| P11-06 | Three process-wide reasons in `IdentityScopeRulesTest` were wrong. `DebugSettingsManager` and `DebugPreferenceManager` both said "the wipe list must cover its buffers": a panic cleanup empties the first one's buffers, and the second holds none. `WearNotificationCoordinator`'s said only "system notifications", though it keeps unread message previews. | Each says what the class holds: the debug toggles and limits, which are no peer or identity data; the buffers of peer IDs, nicknames and device addresses that `DebugBuffersPanicCleanup` empties; and the watch's unread previews, which no panic reaches because the watch has no panic. |
| P11-13 | `MeshIdentityBindings` had a `@CurrentIdentity` bridge for `PrivateMessageArrivalOrder` that nothing took. | Deleted; the stores inside the scope take it directly. |
| — | `/msg` typed in an open private chat moved the selection to the new peer while the screen stayed on the old chat, so the new peer's messages counted as read and further commands in that screen were refused. | `MessageSender.sendToConversation` reports the new selection and the screen replaces itself with that chat (upstream moves its sheet). `PrivateConversationSendRobolectricTest` fails against a sender that does not report it. |
| — | Nothing pinned that the panic's relay release only unsubscribes. | `GeohashSessionSendTest` and `NostrBackgroundRuntimeReleaseTest` check that `panicReleaseRelays` and `releaseSubscriptions` make no subscribe call, even after ten minutes of virtual time. The runtime's test fails against a release that subscribes again. |
| — | `/msg` typed in the main chat selected the peer but opened nothing, and the main composer still sent to the selection: what looked like a public message went privately to a peer the screen did not show, and the replies were not visible (Codex review on PR #972). | The main composer sends to what the main chat shows (`MessageSender.send` has no private path) and keeps no private draft, and the chat screen opens the chat `/msg` selected. `PrivateConversationSendRobolectricTest` fails against a sender that sends main-chat text to the selection, and `PrivateChatDestinationTest` opens the chat from `/msg` on a device. Commands typed in the main chat still act on the selection, which only a private chat that is open or leaving holds. |
| — | Private media is sent only from the private chat, but the one-shot consent to send it unencrypted to an older peer was asked only by the chat screen, which is not composed under the private chat. The send waited with no prompt until the user went back (since Phase 7.5; Codex review on PR #972). | One `LegacyPrivateMediaConsentDialog`, drawn once by `SessionDialogs` above navigation (see the next row), so the consent is asked over whatever is on top when it is raised: the private chat, or the peer list if the user has left the chat by then. `SessionDialogsViewModelTest`, and `PrivateChatDestinationTest` on a device for both cases. |
| — | `/join` of a password-protected channel typed in a private chat raised the password prompt, but only the chat screen drew it, and that screen is not composed under the full-screen private chat. The composer cleared with no prompt, which appeared only after backing out (since Phase 7.5; Codex review on PR #972). | The session's dialogs, the password prompt and the legacy media consent, are drawn once by `SessionDialogs` in `:feature:chat`, hosted by `MainActivity` above `BitchatNavDisplay` while chat is the root. Its ViewModel lives in the screens' store, so a new session discards it with the screens. No screen draws either dialog any more. Each dialog is its own window and takes Back while it shows, so Back never closes a prompt the user cannot see. After an Activity recreation, a restored sheet can sit above a pending dialog until Back closes the sheet; the dialog then shows with its state kept. `SessionDialogsViewModelTest`, and `PrivateChatDestinationTest` shows the prompt over the private chat on a device. |

Also closed in this section's cleanup of section 5: the smells `MessageRouter`'s unscoped
`Provider` (10c-2a-2b), `GeohashSession`'s never-cancelled scope (10c-2b) and the `object`
singletons left after Phase 3 (10c-1 and 10c-2a-1); and the 10c-3 follow-ups closed after it,
which are listed in "Closed after 10c-3".

#### Known, not fixed

- **P11-07: relay state after a panic.** Since the upstream sync, `clearAllOnPanic` drops the
  subscriptions, handlers, per-geohash routing, queued events and the deduplication cache,
  closes every socket, and puts the relay list back to the defaults with fresh relay objects
  and no live-location tokens (`forgetSessionRelays`). What remains in memory is
  `pendingGiftWrapIDs`: the IDs of gift wraps the wiped identity sent that no relay has
  acknowledged yet. They stay until a relay answers or the process ends.
- **Two relay timing gaps, both also upstream.** A Tor route change that races the panic's
  release can open the default relays again during the wipe. And anything the ending identity
  hands the relay manager after the release is replayed on the next identity's sockets.
- **Test seams in main sources:** `ChatSessionMesh.replace`, used only by device tests, and
  `AppStartupGate.forcePhase`, an `internal` hook used only by device tests.
- **P11-11: `:app` still declares libraries of code that moved out.** No `:app` main source
  imports ZXing, ML Kit, CameraX, Accompanist, Play Services location, ExifInterface, Navigation
  Compose, the Nordic BLE library or security-crypto, yet `app/build.gradle.kts` declares them.
  Removing them changes the lockfiles and the verification metadata, so it is left for a
  dependency change of its own.
- **P11-12: functions the refactor left without callers.** Their callers upstream were moved or
  rebuilt: `BitchatSheetTopBar` and `BitchatSheetCenterTopBar` (the sheets became full
  screens), `MeshCore.sendFilePrivate` and `MeshCore.getEncryptedPeers` (10c-2a-2a),
  `NotificationManager.clearAllNotifications` (the panic cleanup does its work; the 10c-3
  removal of uncalled clear methods missed it),
  `FavoritesPersistenceService.clearAllFavorites` (see "Closed after 10c-3"),
  `VerificationHandler.isNoisePublicKeyVerified`, `GeohashSession.peerIdentityForNostrPubkey`,
  `NostrFilter.geohashEphemeral` and the constant `AppConstants.Nostr.SUBSCRIPTION_VALIDATION_INTERVAL_MS`.
- **P11-15:** `cd_close` and `join` are defined in both `:feature:chat` and `:feature:location`.
- **P11-16:** `ScreenTransitions.DURATION_MS` repeats `BitchatMotion.EMPHASIZED_MS` (240),
  because `:core:navigation` cannot see the theme.

#### Real-phone checks (2026-11-27)

On one Android phone, with the developer's go-ahead to wipe its install, on 7e8e3f68. The Codex fixes that followed change neither panic, storage nor startup.

| Check | Result |
|---|---|
| Install over the previous refactor build | Peer ID, fingerprint, Noise key and nickname unchanged; the same 30 preference, database and file entries. Only Tor's directory cache shrank. |
| Recreation (font scale) in chat | The window was recreated; no crash. |
| Panic from the header | 0.79 s in total, in the same process and Activity. Screen blank at 145 ms; wipe 30 ms with 0 leftovers and 0 Keystore keys; a new peer ID, fingerprint, Noise key and nickname. What stayed matches the keep-list (settings, Tor state, relay list, the public release APK). No crash or `AEADBadTagException`. |
| Fresh install, first launch (F8) | Launcher, splash, then the permission explanation; no frame of chat (screen recording at 5 frames a second). |
| Declining the system battery dialog (F7) | Stays on the battery step; the app stays optimized. |
| Recreation during onboarding | Stays on the same step, no error. |

The stage split on the phone: identity ended 165 ms in, sandbox wiped by 195 ms, the next identity started by 536 ms and the new mesh built by 758 ms. Most of the time is the new identity, not the wipe.

#### Decisions

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| (D) A panic taking 1.0 to 2.9 s on an emulator, against about 0.4 s in 10c-2b, is accepted. On a real phone it took 0.79 s (2026-11-27). (2026-11-26) | The split by stage (10c-3) shows no single cause, and emulator storage says little about real flash. | The bulk-media change now. | If the real phone is slow, the conversation store's clear and the blank frame are the first stages to look at. |
| (D) The real-device checks are the developer's, at the end of the refactor. (2026-11-26) | They need two physical phones, a throwaway install and a watch. | Running them per phase. | Listed in section 5, "Owed before handover". |

Tests: JVM 1773 with 0 failures (3 skipped), lint, both assembles and `clientRewriteContractTest`
green after the Codex fixes and the session dialogs, and on an emulator after a fresh install:
app 108, chat 6, settings 5, location 4, share 3, all passing. After the first two Codex fixes:
JVM 1770 and app 106. Before those fixes: JVM 1763, run twice.
Device, on an emulator after a fresh install: app 104, chat 6, settings 5, location 4, share 3.
Then the same suites again over the data that run left, reinstalled in
place: app 104, chat 6, settings 5, location 4, share 3, all passing. Mesh Lab on two
emulators passed 14/14; the earlier flaky runs had a stale third bitchat build running its own
mesh on one image, and every flaked scenario passed alone.

### Upstream sync after Phase 11: #1096, battery and CPU (2026-11-27)

Merged upstream to `884d951a` (PR #1096 and its four review rounds). Every behaviour change is
kept; where the refactor had moved or rebuilt the code, it was ported:

| Upstream change | Where it went |
|---|---|
| One encrypted identity storage per process, with a read-through cache of its string sets | `SecureIdentityStateManager` shares one opened storage, its string sets cached on it, still opened inside the storage lock (the 10c-3 master-key fix). Upstream's panic only clears keys, so its cache stays valid; this branch's wipe deletes the files and the Keystore key, so `SandboxWipe.run()` drops the shared storage and empties its cache, and a manager fenced by the panic reads storage directly. Without that, a manager built after the wipe would write under the deleted keyset (`AEADBadTagException` at the next open) and a manager held across it would read the ended identity's peers. `PanicContractTest` checks both. |
| Cached current Nostr identity and lock-free geohash identity lookup | `NostrIdentityBridge` is `@IdentityScoped`, so the cache ends with the identity. Upstream's read-write lock existed only for `clearAllAssociations`, which this branch had already removed as dead; it is not added. |
| Chat poll gated on foreground, peers or an unsent QR verification; no writes when nothing changed | `ChatSessionStartup` (was `ChatViewModel`). The skip state lives in the session scope, so a panic resets it without the hand-written reset upstream needs. |
| Geohash identity derived off the main thread | `GeohashSession` (was `GeohashViewModel`). The peer list already derived it off the main thread for the selected channel, so `GeohashPeopleList`'s change had nothing left to fix. |
| Batched seen-message writes | `SeenMessageStore`, inside the identity scope. Its teardown closes the store, cancels the pending batch and waits for a write in flight, so no batch lands after the wipe. Upstream's `clear()` is not added back. |
| Arti soft-dormant in the background | `ArtiTorManager` takes a `TorAppBackground` port, bound by the app from `PowerManager`, so `:core:tor` still depends on no project module. |
| Relay pause and heal on network loss, background throttle, wait for Tor's route | `NostrRelayManager`, with the wait through a new `NostrHttpClients.awaitRoute`, which has no default binding, like the rest of the port. `RelayDirectory` waits the same way instead of calling `ArtiTorManager` statically. `clearAllOnPanic()` also clears the new throttle state. |
| Block lists reloaded only when they change | `NostrBackgroundEventProcessor` in `:core:chat`, inside the identity scope. `DataManager`'s change counter is process-wide and holds no list. |
| Debug counters as ring buffers, nothing recorded while the debug sheet is hidden | `DebugSettingsManager`; the panic cleanup empties its new per-peer maps too. |
| Mesh, Noise, gossip and protocol hot paths (fewer copies, thread-local digests, BFS routing, conflated cleanup) | Merged as is. `MeshCore` takes `PowerManager` from its caller instead of `getInstance`. No wire format changed. |
| Rebuilt Arti libraries | Under `core/tor/src/main/jniLibs`; `SHA256SUMS` updated, `verify-checksums.sh` passes. |

Tests: upstream's new tests were moved beside their subjects in `:core:mesh`, `:core:nostr`,
`:core:chat` and `:core:tor` and adapted to the injected constructors. The relay tests of upstream's
`reconnect` flag are not added; this branch has no such flag (see the sync after Phase 10c-3).
`IdentityScopeRulesTest` lists the three new process-wide objects with their reasons.
