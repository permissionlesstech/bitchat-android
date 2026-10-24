# Refactor decision log: Navigation 3, Hilt and modules

## 1. Purpose and how to read this

This log records what the Navigation 3, Hilt and modularization refactor decided, why, and
what it left open. It is written for the maintainer, who will receive the whole refactor once,
at the end. Read section 2 for the summary. Read section 3 only for the phase you want to
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
  classes) is built in an Activity-retained Hilt scope instead of inside `ChatViewModel`.
  `ChatViewModel` went from 1744 lines (start of Phase 4 Part 2) to 269 (end of Phase 7a).
  Each screen has its own ViewModel where it owns state.
- **Modules.** The phone app was one module and the watch compiled 113 of its files through a
  copy task. There are now 16 library modules:
  - `:core:domain`, `:core:navigation`, `:core:mesh`, `:core:nostr`, `:core:chat`, `:core:tor`
  - `:core:designsystem` (shared with Wear) and `:core:designsystem:phone`
  - `:feature:share`, `:feature:settings`, `:feature:location`, `:feature:chat`, each with an
    `:api` module

  Wear depends on `:core:domain`, `:core:mesh` and `:core:designsystem`. The copy task is gone.
- **Device tests.** The repo had no instrumented tests when Phase 4 began. There are now 59. They
  run on a connected device or emulator, not in CI.

### What users will notice

Very little. Every user-visible change is listed below.

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
  `NotificationTargets`, `HotspotRadioLease`, `TransportDiagnostics`). A port that guards
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
  7.5. The container build for the Phase 8 head has not run yet. It runs when Phase 8 is pushed.
- **Tests.** 742 JVM tests across the phone, the core modules and Wear after the 2.0.3 merge.
  59 device tests.

## 3. Decisions, by phase

### Groundwork: upstream PRs #912 and #913

**What changed.** `BackHandler` replaced an `OnBackPressedCallback` that was registered on
every recomposition (#912). Onboarding and chat went behind a Navigation 3 back stack, Hilt and
KSP were added, and ViewModels were injected (#913). Both PRs are contained in this branch.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| Onboarding is one destination, not one per step. | Its steps follow permission results and adapter state, not user navigation. Going back a step was never supported. Per-step routes would invent a history that does not exist. | Eight onboarding routes. | The state machine stays in `MainActivity` until Phase 9. |
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
| `MessageRouter` is unscoped and injected as `Provider<MessageRouter>`. | `getInstance` re-points the router at the current mesh. A cached binding would skip that after a panic clear replaces the mesh. | A scoped binding. The deeper fix, a mesh service with a stable identity, reaches into the mesh layer shared with Wear. | The deeper fix is still open. |
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
| `GeohashSession` keeps a never-cancelled scope. | Tying it to the session would cancel a geohash message still sending when the Activity finishes. That is a messaging change for its own commit. | The session scope. | Still open. |
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

## 4. Upstream merges during the refactor

| Merge | Upstream content | Where it went |
|---|---|---|
| Phase 2 start | 26 commits. | Merged clean. |
| End of Phase 7 | Only the weekly relay-data bot commits. | Merged clean. |
| End of Phase 7.5 | Wear ongoing activity, icons, Wear 0.1.4. | One conflict, in `gradle/verification-metadata.xml`. Resolution checked by hand. |
| During Phase 8: 2.0.3 / Wear 0.1.5 (#977, #979, #981) | Chat workload bounds under heavy activity; geohash presence UI stalls. | See below. |

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

PRs #912 and #913 were open upstream at last record, and this branch contains both. If they
merge first, expect a Back-handling conflict in `MainActivity`. Keep this branch's handler
(`pendingBackAction`).

## 5. Known gaps and follow-ups

### Pre-existing upstream bugs found, not fixed

- `NostrRelayManager.resetAllConnections` iterates `relaysList` unsynchronized. A concurrent add
  or remove can throw `ConcurrentModificationException` mid-reset. `disconnect()` has already
  closed every socket, so the effect is relays left disconnected, not sockets on the old route.
  A candidate small upstream PR.
- Recreating the Activity within about two seconds of launch cancels `initializeApp` and lands
  on the onboarding error screen.
- The first Nostr private message sent after Bluetooth is turned off can be lost with no error.
- A Nostr private message's sender shows as "anon" instead of the favourite's nickname
  (`displayNameForNostrPubkeyUI`).
- `BluetoothPacketBroadcaster` spins at about 100 failures a second while Bluetooth is off.
- The favourite star has no accessibility label.
- The iOS client files Nostr private messages in a separate thread.
- `FragmentManagerTest > test reassembly` flakes. Root cause not established. Candidates: an
  unseeded `Random`, and a cleanup loop that nothing cancels.

### Test gaps

- The project cannot host Compose unit tests: Robolectric's maximum SDK is below the target SDK.
  Back dispatch and screens are covered only by device tests, which CI does not run.
- `VerificationDestinationTest` flakes about once in eight full runs. Pre-existing timing.
- `EveryRouteHasAnEntryTest` builds the installers by hand, so it does not catch a feature
  missing from the Hilt graph or a route missing from its list.
- The device-suite step must rebuild the test APK from a clean outputs directory. A failed
  androidTest compile once let a stale test APK pass.

### Smells left on purpose

- `WifiAwarePeers` carries a non-peer `enabled` flag.
- `ic_notification` lives in `:core:domain`, the lowest module chat and share both see.
- `TransportDiagnostics` has about 20 members. The real fix is moving `BluetoothMeshService`
  out of `:app`.
- `MessageRouter` relies on an unscoped `Provider` to follow a replaced mesh. The deeper fix is
  a mesh service with a stable identity.
- `GeohashSession` keeps a never-cancelled scope (see Phase 5).
- About 17 `object` singletons remained in `services/` and `ui/` at the end of Phase 3.

### Deferred items

- Parked from Phase 7.5: the mesh graph's settle rule, voice read-marking while backgrounded,
  the Wear `DmScreen` unconditional clear, and a narrower pop for the verify deep link.
- Revert the CI trigger line for the `refactor` branch when it merges.

### Owed before handover

- **Mesh Lab on two physical Android phones.** Phases 4–7 moved the private chat path, the mesh
  delegate wiring, the send path and the transport files. Phase 8 moved Tor.
- **Manual checks on a real device:** a public message, the peer list, the user sheet, and the
  geohash picker and globe. These are blocked on the emulator by the rule against using
  location data.
- **Wear visual check.** No Wear emulator was available for Phase 8's design system change.
- **Container reproducible build** for the Phase 8 head, in fork CI on push.
- **Before/after screenshots** in the handover PR for the Phase 7.5 screens and the Wear peer
  colors, with synthetic data only.

A manual cross-platform messaging check on physical devices passed at the end of Phase 7.5:
Bluetooth public and private messages with read receipts, a mutual favourite, and Nostr private
messages both ways with Bluetooth off. That covers the code up to that point, not Phase 8.

## 6. Phases 9–11

### Phase 9: onboarding (not started)

Planned scope:
- `:feature:onboarding` gets the six screens (`BluetoothCheck`, `LocationCheck`,
  `BackgroundLocationPermission`, `BatteryOptimization`, `PermissionExplanation`,
  `Initializing`; about 1.7k lines) as state and callbacks.
- The state machine (`OnboardingCoordinator` and about 300 lines of `MainActivity`) becomes an
  `OnboardingViewModel` in MVI form.
- Permission requests move to `rememberLauncherForActivityResult` inside the feature, so the
  status managers stop holding the Activity.
- It changes first-launch behaviour, so it gets its own spec and device tests.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| *To be filled in* | | | |

### Phase 10: MVI on every screen (not started)

Planned scope: every screen exposes one UI state and takes actions, as the chat screen and
private chat already do. Phase 8 named this as the natural point to slim `TransportDiagnostics`.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| *To be filled in* | | | |

### Phase 11: Wear shares UI with the phone (not started)

Planned scope: Wear shares UI with the phone beyond the design system base that Phase 8 added.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| *To be filled in* | | | |
