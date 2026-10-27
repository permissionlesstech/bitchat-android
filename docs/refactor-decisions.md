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
- The iOS client files Nostr private messages in a separate thread.
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

### Phase 10: MVI on every screen (10a and 10b done; 10c to come)

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
`phase10b/mvi-peers-channels`): the peer list and the location channels screen. **10c** (to come, own spec): the project's singletons become Hilt
classes.

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
- The gate ran as `SDD_WS=2026-10-24-phase10b-mvi-peers-channels-plan .superpowers/sdd/tools/gate.sh taskN`, so its logs land in this workspace's folder. Cost if wrong: logs in another folder.
- The gate used whichever emulator was listed. A larger emulator image dropped offline mid-suite once and failed one settings test deterministically; the clean image passed. Cost if wrong: a flake from a dirtier image; re-run on the clean one.
- Tasks 4 and 8 (a person checking each screen on a physical phone) are real stop points, because the spec requires that check before the next screen.
- JVM tests live in `feature/*/src/test/java/...`, the modules' existing source directory, not `src/test/kotlin`.
- Per task the implementer commits first, then the gate and Codex run on the commit; fixes are follow-up commits. A red gate costs an extra fix commit, not an amend.
- The Task 2 review finding (six dead ViewModel helpers, one duplicating a rule) was left to Task 3, which rewrote the ViewModel anyway. One intermediate commit carried the duplicate.
- The Undo clock and a strengthened `deleteThenNewCollector_undoStillRestores` (it now cancels mid-delete) were folded into F2 by fixup, so F2 stays one revertable commit. Only unpushed SHAs changed.
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

### Phase 11: Wear shares UI with the phone (not started)

Planned scope: Wear shares UI with the phone beyond the design system base that Phase 8 added.

| Decision | Why | Alternatives rejected | Cost / what to watch |
|---|---|---|---|
| *To be filled in* | | | |
