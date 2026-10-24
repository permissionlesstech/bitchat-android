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
  classes) is built in an Activity-retained Hilt scope instead of inside `ChatViewModel`.
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
- **Device tests.** The repo had no instrumented tests when Phase 4 began. There are now 64: 63
  in `:app` and 1 in `:feature:chat`. They run on a connected device or emulator, not in CI.

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

Onboarding (Phase 9). The full list, with commits, is in "What Phase 9 fixed" in section 3:
- First launch no longer flashes the chat screen before the permission explanation. Try again
  stays on onboarding instead of flipping to chat for half a second.
- Rotating the phone during startup no longer ends on "Failed to initialize the app".
- Declining the system battery dialog keeps you on the battery step.
- Phones without Bluetooth get the "Bluetooth not supported → Continue" step instead of an error.
- A skipped battery step no longer comes back when a new optional permission is asked. Turning
  Bluetooth off while granting permissions no longer jumps to the Bluetooth step.

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
  7.5. The container build is deferred to the end of the refactor, after Phase 11, and is not
  run per phase. Fork CI is blocked for now: GitHub answers "Actions has been disabled" with
  every setting enabled.
- **Tests.** 879 JVM tests (3 skipped) across the phone, the core modules, the features and Wear
  at the end of Phase 9. 64 device tests: 63 in `:app`, 1 in `:feature:chat`.

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

PRs #912 and #913 were open upstream at last record, and this branch contains both. If they
merge first, expect a Back-handling conflict in `MainActivity`. Keep this branch's handler
(`pendingBackAction`).

## 5. Known gaps and follow-ups

### Pre-existing upstream bugs found, not fixed

- `NostrRelayManager.resetAllConnections` iterates `relaysList` unsynchronized. A concurrent add
  or remove can throw `ConcurrentModificationException` mid-reset. `disconnect()` has already
  closed every socket, so the effect is relays left disconnected, not sockets on the old route.
  A candidate small upstream PR.
- The first Nostr private message sent after Bluetooth is turned off can be lost with no error.
- A Nostr private message's sender shows as "anon" instead of the favourite's nickname
  (`displayNameForNostrPubkeyUI`).
- `BluetoothPacketBroadcaster` spins at about 100 failures a second while Bluetooth is off.
- The favourite star has no accessibility label.
- The iOS client files Nostr private messages in a separate thread.
- `FragmentManagerTest > test reassembly` flakes. Root cause not established. Candidates: an
  unseeded `Random`, and a cleanup loop that nothing cancels.

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
- `MessageRouter` relies on an unscoped `Provider` to follow a replaced mesh. The deeper fix is
  a mesh service with a stable identity.
- `GeohashSession` keeps a never-cancelled scope (see Phase 5).
- About 17 `object` singletons remained in `services/` and `ui/` at the end of Phase 3.
- `AppStartupGate.forcePhase` is an `internal` test hook in main sources, used only by device
  tests.
- Two binding modules with near names: `ReadinessBindingsModule` (main, Wi-Fi Aware only) and
  `ReadinessBindingModule` (debug and release, `DeviceReadiness`).

### Deferred items

- Parked from Phase 7.5: the mesh graph's settle rule, voice read-marking while backgrounded,
  the Wear `DmScreen` unconditional clear, and a narrower pop for the verify deep link.
- Revert the CI trigger line for the `refactor` branch when it merges.

### Owed before handover

- **Mesh Lab on two physical Android phones.** Phases 4–7 moved the private chat path, the mesh
  delegate wiring, the send path and the transport files. Phase 8 moved Tor. Phase 9 moved app
  startup and where the mesh delegate attaches and detaches.
- **Manual checks on a real device:** a public message, the peer list, the user sheet, and the
  geohash picker and globe. These are blocked on the emulator by the rule against using
  location data.
- **Phase 9 phone run, fresh install, from the spec's checklist:** no chat flash before the
  permission explanation (F8); declining the battery dialog stays on the step (F7); Bluetooth
  off and on in chat restores the open chat and reopens nothing (F1); rotating during startup
  shows no error (F2); a DM sent from a second device while the app is in the background
  arrives as a notification (F9); the onboarding screens' insets.
- **Wear visual check.** No Wear emulator was available for Phase 8's design system change.
- **Container reproducible build**, once, at the end of the refactor, when fork CI can run.
- **Before/after screenshots** in the handover PR for the Phase 7.5 screens and the Wear peer
  colors, with synthetic data only.

A manual cross-platform messaging check on physical devices passed at the end of Phase 7.5:
Bluetooth public and private messages with read receipts, a mutual favourite, and Nostr private
messages both ways with Bluetooth off. That covers the code up to that point, not Phases 8
or 9.

## 6. Phases 10–11

Phase 9 moved to section 3 when it finished. What it left open is in section 5.

### Phase 10: MVI on every screen (not started)

Planned scope: every screen exposes one UI state and takes actions, as the chat screen and
private chat already do. Onboarding already does, since Phase 9. Phase 8 named this as the
natural point to slim `TransportDiagnostics`.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| *To be filled in* | | | |

### Phase 11: Wear shares UI with the phone (not started)

Planned scope: Wear shares UI with the phone beyond the design system base that Phase 8 added.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| *To be filled in* | | | |
