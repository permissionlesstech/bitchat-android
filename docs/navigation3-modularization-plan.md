# Navigation 3, dependency injection, and modularization

Design and sequencing for introducing Jetpack Navigation 3, a DI framework, and a
multi-module structure to the phone client.

Status: **groundwork and the Navigation 3 skeleton landed; overlay conversion not started.**

DI, the convention plugins, `:core:domain`, `:core:navigation` and the NavDisplay
host are in place; onboarding and chat are routes. The nine chat overlays are
still booleans.

Sections 1–6 describe the design and still contain the pre-implementation view
of the app in §1. §7 tracks sequencing and marks what has landed, §9 collects
toolchain constraints worth not rediscovering, and §10 holds the open questions.

---

## 1. Where the app is today

Facts, with references, so this document can be executed from a cold start.

### There is no navigation

`libs.androidx.navigation.compose` is declared at `app/build.gradle.kts:136` but
**nothing in `app/` uses `NavHost` or `rememberNavController`.** It is an unused
dependency; only `wear/` has a real `WearNavHost`. What exists instead:

| Mechanism | Location |
|---|---|
| `when (onboardingState)` over 8 `OnboardingState` values | `MainActivity.kt:231-349` |
| 9 overlays driven by independent booleans | `ChatScreen.kt:816-936`, flags in `ChatState.kt` |
| Hand-written back priority chain | `ChatViewModel.kt:1682-1708` |
| Two escape-hatch Activities | `GeohashPickerActivity`, `HotspotActivity` |

Four of the nine overlay flags live in `ChatState` (`showAppInfo`,
`showMeshPeerList`, `privateChatSheetPeer`, `showVerificationSheet`,
`showSecurityVerificationSheet`, `showPasswordPrompt`); the rest
(`showLocationChannelsSheet`, `showLocationNotesSheet`, `showUserSheet`,
`showDebugSheet`) are local `remember` state inside `ChatScreen` — e.g.
`ChatScreen.kt:855`. `ModalBottomSheet` intercepts back for those, so behaviour
is currently correct by accident rather than by design.

`MainActivity.kt:317-332` constructs an `OnBackPressedCallback` and calls
`addCallback` **directly in a composable body**, with no `remember` or
`DisposableEffect`. Every recomposition of that branch registers another
callback. They are lifecycle-bound so nothing outlives the Activity, and sheets
intercept back ahead of them, so it is benign today. It disappears entirely once
back handling moves to `NavDisplay(onBack = ...)`.

### There is no DI

- 129 Kotlin `object` singletons; 87 files call `getInstance(...)`
- `MeshServiceHolder` static holder (`MainActivity.kt:124-125`)
- One hand-rolled `ViewModelProvider.Factory` at `MainActivity.kt:65-72`
- 5 ViewModels: `MainViewModel`, `ChatViewModel`, `GeohashViewModel`,
  `ApkDownloadViewModel`, `HotspotViewModel`
- No KSP plugin anywhere in `gradle/libs.versions.toml`

### `ChatState` is the god object

`ChatViewModel` (1723 lines) is a **facade**. It already delegates to ten
collaborators, constructed at `ChatViewModel.kt:143-215`:

```
DataManager            MessageManager        ChannelManager      PrivateChatManager
CommandProcessor       NotificationManager   VerificationHandler MediaSendingManager
MeshDelegateHandler    GeohashViewModel
```

Every one of them takes `state: ChatState` (359 lines) in its constructor. That
single shared mutable blackboard is the actual coupling. `GeohashViewModel` is
already a real `AndroidViewModel`, merely hand-constructed inside
`ChatViewModel`; `VerificationHandler` already owns `verifiedFingerprints`.

### `ChatViewModel` surface used per overlay

Measured by unique `viewModel.*` references in each file. This drives the
sequencing in §7.

| Overlay | Members used | Assessment |
|---|---:|---|
| `LocationNotesSheetPresenter` | 1 | trivial |
| `LocationChannelsSheet` | 5 | easy, geohash-local |
| `ChatUserSheet` | 8 | moderate, geohash + chat |
| `VerificationSheet` | 8 | easy, `VerificationHandler` exists |
| `SecurityVerificationSheet` | 9 | easy, verification-local |
| **`MeshPeerListSheet`** | **48** | **the real work** |

`MeshPeerListSheet` is not a sheet. It is the conversation-list screen —
conversations, drafts, pin/mute, unread counts, favourites, media sending, peer
RSSI, session states — presented as a bottom sheet over the public chat. It is
a conventional inbox / conversation-list feature in all but name, and it is the
list pane of the list-detail layout this work is meant to deliver, with
`PrivateChatSheet` as the detail pane.

### Build constraints that shape the plan

- `gradle/verification-metadata.xml`: **736 pinned artifact checksums**,
  `verify-metadata: true`. Regenerated with
  `./gradlew --write-verification-metadata sha256 help`.
- `build.gradle.kts:16` — `dependencyLocking { lockAllConfigurations() }`
- **Byte-for-byte reproducible releases.** `tools/reproducible-builds/build-release.sh:46-48`
  sets `-Pkotlin.incremental=false --no-build-cache --no-configuration-cache`;
  `.github/workflows/release.yml` runs it twice via `matrix.replica` and
  byte-compares with `compare-release.sh`. These flags are **not** in
  `gradle.properties`, so ordinary dev builds keep full caching and incremental
  compilation.
- **`wear/` file-copies source out of `app/src/main/java`.**
  `wear/build.gradle.kts:68-98` defines `sharedSourceIncludes` as path globs
  (`com/bitchat/android/{protocol,noise,crypto,identity,mesh,model,sync,favorites}/**`
  plus named files); `syncSharedAppSources` (line 111) `Sync`s them into
  `wear/build/sharedSrc`. `AGENTS.md` codifies this arrangement. **Moving any of
  those packages out of `:app` silently produces an empty directory and breaks
  `:wear`.**

---

## 2. Goals

1. **Adaptive list-detail** — `MeshPeerListSheet` / `PrivateChatSheet` become
   two panes on tablets and foldables instead of stacked bottom sheets.
2. **A real back stack** — replace 9 booleans plus a fixed-order priority chain
   with one explicit stack; correct predictive-back animations.
3. **Deep links and notification intents** — notification and `bitchat://verify`
   intents seed the back stack instead of poking ViewModel methods.
4. **Multi-module** — features register their own routes; features depend on
   each other only through `:feature:<name>:api`.

### Non-goals

- Converting the 129 singletons to injected types. They stay. DI covers the
  ViewModel and UI layer only.
- Extracting `mesh/`, `protocol/`, `crypto/`, `noise/`, `identity/`, `sync/`,
  `favorites/` into modules. They stay in `:app`; see §3.2.
- Any change to `:wear` beyond the three-line addition in §3.2.
- Redesigning any screen's visual appearance.

---

## 3. Decisions

### 3.1 DI: Hilt, with Koin as a pre-decided fallback

**Hilt, contingent on PR 0 passing.**

Rationale:

- The Nav3 multi-module pattern it enables — `@Provides @IntoSet
  EntryProviderInstaller`, `rememberViewModelStoreNavEntryDecorator`, an
  `@ActivityRetainedScoped` navigator — is an established, documented approach
  (see the `modular-hilt` recipe in the Navigation 3 guide), not novel design
  work. The equivalent Koin integration is still marked experimental.
- Compile-time graph validation is modest at ~12 ViewModels but free and permanent.
- Google default; OSS contributors will recognise it.

Costs, weighed and accepted: ~25 extra components in `verification-metadata.xml`
(regeneration is automated), and full non-incremental KSP cost in the twice-run
release pipeline — on the order of minutes for ~15 small modules.

**The one real risk was toolchain compatibility** — Hilt's Gradle plugin performs
bytecode transformation through AGP APIs that AGP 9 reworked. **Resolved:** Hilt
2.60.1 and KSP 2.3.11 build on AGP 9.3.1 / Kotlin 2.4.10 / compileSdk 37, with
`transformDebugUnitTestClassesWithAsm` and the `hiltAggregateDeps*` tasks running
normally.

The second half of that risk turned out not to exist. KSP versions independently
of Kotlin as of KSP 2.3.0; the old `<kotlin>-<ksp>` scheme that required an exact
match to the Kotlin version was retired, so there is no longer a KSP release
pinned to Kotlin 2.4.10 to wait for.

**Fallback: Koin.** If PR 0 fails, switch. Koin adds 3 artifacts, no codegen, no
KSP, no AGP plugin transform, and ships a first-party Nav3 integration
(`org.koin.dsl.navigation3.navigation`, `getEntryProvider()`,
`activityRetainedScope()`) — currently `@KoinExperimentalAPI`, with a stable
fallback of declaring `single<EntryProviderInstaller> { ... }` per feature and
collecting via `getAll<EntryProviderInstaller>()`. Every step after PR 1 is
framework-agnostic except annotations, so the switch is cheap.

If Koin is chosen, a `verify()` graph test per module in CI is **mandatory from
day one**, not deferred — it is the substitute for compile-time validation.

Manual DI was considered and rejected: it cannot give feature self-registration
across module boundaries without `:app` hand-assembling the entry list, and it
means hand-writing twelve `ViewModelProvider.Factory` instances — precisely the
boilerplate at `MainActivity.kt:65-72` this work removes.

### 3.2 Module scope: UI and features only

Mesh, protocol, crypto, noise, identity, sync and favorites stay in `:app`, so
`wear`'s `sharedSourceIncludes` keeps working.

**One unavoidable exception.** A `:feature:*:presentation` library module cannot
depend on `:app` — Gradle forbids library→application. But every feature needs
`com.bitchat.android.model.BitchatMessage` and the `MeshService` interface, both
currently in `:app` and both inside `wear`'s globs.

**As built, `:core:domain` holds two files:** `model/BitchatMessage.kt` (which
declares `BitchatMessage`, `BitchatMessageType` and `DeliveryStatus`) and
`model/BitchatFilePacket.kt`. Between them those are every `model` type the UI
layer imports, and both depend only on `Parcelable`, Gson and `java.nio` — no
`com.bitchat` package at all.

Two things the original design got wrong here:

- **`model/**` is not extractable wholesale.** Four of its ten files reach into
  other packages: `RoutedPacket` and `FragmentPayload` into `protocol`,
  `RequestSyncPacket` into `sync`, and `FileSharingManager` into `features.file`
  (the last is already in `sharedSourceExcludes`). Moving the package entire
  would drag `protocol/` and `sync/` out of `:app`, which §2 rules out.
- **`MeshService` cannot move.** It is an interface, but it references
  `MeshDelegate`, `PeerInfo` and `PrivateMediaPreparation` from its own package
  and returns `noise.NoiseSession.NoiseSessionState`. Extracting it would pull
  the Noise/crypto layer into `:core:domain`. It stays in `:app`.

That second point is a constraint, not a loss: feature modules have no business
depending on the transport API. They reach mesh behaviour through their own
ViewModels, which live in `:app`'s graph.

`wear/build.gradle.kts` gains a second `from(...)` block in `syncSharedAppSources`
reusing the same include and exclude lists, so the moved files keep being
mirrored and `sharedSourceIncludes` keeps its shape:

```kotlin
from("../core/domain/src/main/java") {
    include(sharedSourceIncludes)
    exclude(sharedSourceExcludes)
}
```

### `:core:designsystem` is deferred

It is not a pure move, for two reasons:

- **Resources.** The candidate files reference 2 drawables, 4 fonts and 3
  strings, and one of those strings (`fingerprint_verified_label`) exists in all
  **34** `values-*` directories. Extracting them means recreating that locale
  tree in the new module and splitting the translation workflow.
- **Visibility.** `internal` is Gradle-module scoped, so four symbols would have
  to widen to `public`: `BitchatFontFamily` (30 external referencing files),
  `BASE_FONT_SIZE` (8), `ChatVisualTokens` (4) and `PeerAvatar` (2).
  `PeerAvatarBadgeSize` and the two colour schemes have no external references
  and would stay `internal`.

Widening those four is defensible — they become the design system's public API —
but combined with the locale tree it deserves its own change rather than riding
along with the module scaffolding. Nothing before the first feature module needs
it.

### 3.3 Ordering: DI first, Nav3 second, feature modules as output

Modularization is **not one phase.** It splits in two, and the halves belong at
opposite ends:

| Work | Hard prerequisite |
|---|---|
| DI | none |
| Nav3 | none |
| `build-logic` + `:core:{designsystem,navigation,domain,presentation}` | **none — verified `ChatViewModel`-free** |
| `ChatState`/`ChatViewModel` decomposition | none, but far cheaper with DI |
| **`:feature:*` modules** | **the decomposition** |

Verified clean of any `ChatViewModel` reference: `ui/theme/**`, `onboarding/**`,
`PeerAvatar`, `PressFeedback`, `AnimatedCount`, `ChatUIConstants`,
`PeerColorSeed`, `PeerIdentity`.

Why not modules first: the first feature module attempted fails to compile.
`VerificationSheet(viewModel: ChatViewModel, ...)` cannot move without
`ChatViewModel`, which cannot move because five other overlays need it, which
transitively drags in `BluetoothMeshService`, `GeohashViewModel`,
`NostrTransport` and the singleton graph. You would be doing the hardest work
first, blind, with nothing shippable until it is all done.

Why not Nav3 standalone: Nav3 does **not** force the decoupling inside a single
module — an entry can reach the activity-scoped `ChatViewModel` and carry on.
(It must, in fact: `ChatViewModel` holds the mesh delegate via
`unifiedMeshService.delegate = chatViewModel`, so it has to stay a single
instance. Per-entry `hiltViewModel<ChatViewModel>()` under
`rememberViewModelStoreNavEntryDecorator` would yield a different instance per
entry and break the mesh.) Nav3-then-modules therefore rewrites every entry
later.

**Conclusion: Nav3 and the decomposition are the same work, done one overlay at
a time.** Each PR converts one overlay into a route with its own ViewModel, in
its own feature module, and deletes its members from `ChatViewModel`. No feature
module is ever created empty and backfilled.

---

## 4. Target module layout

```
:app                              MainActivity, BitchatApplication, NavDisplay host,
                                  mesh/ protocol/ crypto/ noise/ identity/ sync/ favorites/,
                                  all 129 singletons, DI bindings for them
:core:domain                      model/** + MeshService interface   <- wear also reads this
:core:designsystem                theme/, PeerAvatar, PressFeedback, AnimatedCount,
                                  ChatUIConstants, PeerColorSeed, PeerIdentity
:core:navigation                  Navigator, EntryProviderInstaller, NavMetadata,
                                  sheet + dialog SceneStrategy
:core:presentation                ObserveAsEvents, UiText, MVI base contracts
:feature:onboarding:{api,presentation}
:feature:chat:{api,presentation}            public/geohash chat, input, messages
:feature:conversations:{api,presentation}   MeshPeerListSheet -> ConversationsScreen
:feature:geohash:{api,presentation}         LocationChannels, LocationNotes, GeohashPicker
:feature:verification:{api,presentation}    Verification + SecurityVerification
:feature:hotspot:{api,presentation}
:feature:about:{api,presentation}           About + Debug
:build-logic:convention           bitchat.android.{application,library,feature.api,
                                  feature.presentation,hilt}
```

**Hard rule:** features consume each other only through
`:feature:<name>:api`, which holds routes and `Navigator.navigateToX()`
extensions. Never import another feature's `presentation`. This is why
`PrivateChatRoute` lives in `:feature:chat:api`, reachable from
`:feature:conversations:presentation` without importing chat internals.

---

## 5. Route model

Single stack. No tabs.

A `Navigator` interface, an `EntryProviderInstaller` typealias and a
`NavMetadata` map are the whole contract surface. **Do not reach for a
multi-back-stack navigator.** The common Nav3 tab pattern — a
`LinkedHashMap<NavKey, SnapshotStateList<NavKey>>` of per-tab histories with
`switchTab`, a rendered-subset helper, scoped keys to disambiguate the same route
in two tabs, and "exit through home" back logic — exists solely to serve bottom
navigation. bitchat has no tabs, so all of it would be dead weight around a map
that only ever holds one key. What is needed is a single
`SnapshotStateList<NavKey>` plus scene strategies.

| Kind | Route | Replaces |
|---|---|---|
| Full screen | `OnboardingRoute` (**not** per-step) | the `when (onboardingState)` in `MainActivity` |
| Full screen | `ChatRoute` | `ChatScreen` body (public/geohash chat) |
| List-detail | `ConversationsRoute` / `PrivateChatRoute(peerId)` | `MeshPeerListSheet` / `PrivateChatSheet` |
| Sheet scene | `LocationChannelsRoute`, `LocationNotesRoute`, `UserActionsRoute(nick, msgId)`, `AboutRoute`, `DebugRoute`, `VerificationRoute(fromSidebar)`, `SecurityVerificationRoute` | the 9 booleans |
| Dialog scene | `ChannelPasswordRoute(channel)` | `showPasswordPrompt` |
| Full screen | `HotspotRoute`, `GeohashPickerRoute` | the two extra Activities |

Sheets and dialogs become back-stack entries via `SceneStrategy` (nav3 recipes
`bottomsheet.md`, `dialog.md`), so `NavDisplay(onBack = ...)` pops them LIFO.

**`selectedLocationChannel` and `currentChannel` stay as ViewModel state, not
routes.** They are content selection within the chat screen, not destinations;
routing them would make every channel switch a back-stack entry.

### Behavioural change to test explicitly

Today's pop order is **fixed** — app-info, then password, then private chat, then
channel (`ChatViewModel.kt:1683-1707`) — regardless of the order the user opened
things. Under a real stack it becomes **chronological**. These overlays are
near-mutually-exclusive in practice, so no user-visible difference is expected,
but it is a semantic change. Cover all four ordering cases with tests before
landing PR 11.

### Deep links

`handleNotificationIntent` (`MainActivity.kt:794-849`) and
`handleVerificationIntent` (`MainActivity.kt:851-860`) stop calling
`chatViewModel.showPrivateChatSheet(peerID)` and instead seed the stack:
`[ChatRoute, ConversationsRoute, PrivateChatRoute(peerId)]` — the
`deeplinks-syntheticbackstack.md` recipe. Back from a notification then lands on
the conversation list rather than exiting the app, which is an improvement on
current behaviour.

---

## 6. Decomposing `ChatState`

The target is six state holders replacing one. Because the ten managers already
exist and are already separate, most of this is re-homing constructor arguments,
not rewriting logic.

| New ViewModel | Wraps | `ChatState` fields it takes |
|---|---|---|
| `VerificationViewModel` | `VerificationHandler` (exists) | `showVerificationSheet`, `showSecurityVerificationSheet`, `verifiedFingerprints` |
| `GeohashChannelsViewModel` | `GeohashViewModel` (exists) | `selectedLocationChannel` |
| `UserActionsViewModel` | `PrivateChatManager`, geohash DM entry points | `nickname`; reads `selectedLocationChannel` from `GeohashChannelsViewModel`. Target nickname and message id come from the route. |
| `ConversationsViewModel` | `PrivateChatManager`, `ChannelManager`, `MediaSendingManager`, `ConversationListPreferences` | conversations, drafts, unread, pin/mute, favourites |
| `ChatViewModel` (reduced) | `MessageManager`, `CommandProcessor`, `MeshDelegateHandler` | messages, `currentChannel`, input, suggestions |
| `AboutViewModel` | `DebugSettingsManager` | `showAppInfo` |

`ChatViewModel` keeps the `BluetoothMeshDelegate` implementation and stays a
single activity-scoped instance throughout — the mesh delegate assignment
(`MainActivity.kt:185, 701, 755, 787`) depends on that and must not be broken at
any point in the sequence.

---

## 7. Sequencing

Small sequential PRs. Each is independently reviewable, revertable, and shippable.

### PR 0 — Toolchain spike — **done**

AGP 9.3.1 + Kotlin 2.4.10 + KSP 2.3.11 + Hilt 2.60.1 build together. Hilt's
bytecode transform runs on AGP 9. Koin is not needed. Verification metadata
gained 36 components and lost none; `:wear` and `settings-gradle.lockfile` were
untouched.

### PR 1 — DI in `:app` only — **done**

`@HiltAndroidApp` on `BitchatApplication`, `@AndroidEntryPoint` on
`MainActivity`, `@HiltViewModel` on the 5 existing ViewModels. Delete the
hand-rolled factory at `MainActivity.kt:65-72`. Bridge the singletons the
ViewModels need with `@Provides` wrappers around existing `getInstance()` calls.
Regenerate `verification-metadata.xml` and the lockfile.

**Zero behaviour change. No singleton is converted.**

### PR 2 — `build-logic` + `:core:domain` — **done**

`build-logic` is an included build exposing `bitchat.android.library` and
`bitchat.android.library.compose`, plus `:core:domain` and the
`wear/build.gradle.kts` edit from §3.2.

Scoped down from the original four core modules: `:core:designsystem` is
deferred for the reasons in §3.2, and `:core:navigation` and
`:core:presentation` have no content to move yet. `:core:navigation` belongs
with PR 3, where the Navigation 3 types it would hold are introduced;
`:core:presentation` waits until there is shared MVI scaffolding to put in it.
Creating either now would violate the rule in §3.3 against empty modules that
get backfilled.

Still outstanding from this step: remove the unused
`libs.androidx.navigation.compose` from `app/build.gradle.kts`. Deliberately
left for PR 3, which replaces it rather than merely deleting it.

### PR 3 — Nav3 skeleton + onboarding — **done**

`:core:navigation` (Navigator, `@ActivityRetainedScoped` AppNavigator,
`EntryProviderInstaller`, the NavDisplay host) plus `OnboardingRoute` and
`ChatRoute` in `:app`. Removes the `when (onboardingState)` and the
recomposition-registered `OnBackPressedCallback`.

**Onboarding is one destination, not eight.** The original plan called it "the
cleanest possible first target: it is already a state machine" — which is true,
and is exactly why its steps are *not* back-stack entries. Transitions are
driven by permission results and adapter state changes, not by the user
navigating, and the app has never supported going back a step. Per-step routes
would invent a history that does not exist. Only the crossing between onboarding
and chat is a navigation event, and it uses `resetTo` so Back cannot re-enter
onboarding.

Two things that only show up at runtime, both now covered:

- **NavDisplay rejects an empty back stack**, and a `LaunchedEffect` does not run
  until after the first composition. Seeding the stack from an effect crashes on
  launch. It has to be seeded before `setContent`.
- **Configuration changes are handled by the retained scope**, so the seeding
  call must be idempotent — re-seeding on Activity recreation would discard the
  history the scope is still holding.

Chat keeps its boolean-driven overlays for now, so back consults
`ChatViewModel.handleBackPressed()` first through an `interceptBack` hook on the
host. That hook is temporary and is deleted as the overlays become routes.

Still outstanding: the unused `libs.androidx.navigation.compose` in
`app/build.gradle.kts` — `:wear` still uses Navigation 2, so removing it from
`:app` is a separate check rather than a free deletion.

### PRs 4–9 — One overlay each, cheapest first

Each PR: overlay becomes a route + its own ViewModel + its own feature module;
its members are deleted from `ChatViewModel`; `ChatState` sheds the corresponding
fields.

| PR | Overlay | Members | Module |
|---:|---|---:|---|
| 4 | `LocationNotesSheet` | 1 | `:feature:geohash` |
| 5 | `LocationChannelsSheet` | 5 | `:feature:geohash` |
| 6 | `VerificationSheet` | 8 | `:feature:verification` |
| 7 | `ChatUserSheet` | 8 | `:feature:chat` |
| 8 | `SecurityVerificationSheet` | 9 | `:feature:verification` |
| 9 | `AboutSheet` + `DebugSettingsSheet` | — | `:feature:about` |

### PR 10 — Conversations + private chat + list-detail

`MeshPeerListSheet` (48 members) becomes `ConversationsScreen` in
`:feature:conversations`; `PrivateChatSheet` becomes `PrivateChatRoute` in
`:feature:chat`. Add the list-detail `SceneStrategy` so they render as two panes
on expanded widths. **Attempted last, with the pattern proven six times.**

### PR 11 — Retire the old machinery

Delete `handleBackPressed()` (`ChatViewModel.kt:1682-1708`) and the remaining
flags in `ChatState`. Land the back-order tests from §5. Convert
`GeohashPickerActivity` and `HotspotActivity` to routes and remove them from
`AndroidManifest.xml`.

### Optional resequencing

If seeing the tablet two-pane layout early is worth rework, PR 10 can run at
position 4 against the still-intact `ChatViewModel`, at the cost of rewriting
those two entries once the decomposition reaches them.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| ~~Hilt/KSP incompatible with AGP 9.3.1 / Kotlin 2.4.10~~ | **Closed.** Hilt 2.60.1 + KSP 2.3.11 build and R8-minify cleanly. |
| ~~`:wear` breaks when `model/` moves~~ | **Closed.** `:wear`'s `sharedSrc` still resolves all nine model files and still excludes `FileSharingManager`. |
| Codegen breaks byte-for-byte reproducibility | Release CI builds twice and byte-compares, so a regression fails the build rather than shipping. Checked locally after the DI change: two `:app:bundleRelease` runs under the determinism flags from `build-release.sh` produced identical bytes, so KSP and the Hilt processors do not introduce ordering nondeterminism. That is a proxy, not the guarantee — the canonical check pins JDK 21.0.11+10 and Linux SDK archives and only runs in the container. |
| Mesh delegate lost mid-sequence | `ChatViewModel` stays a single activity-scoped instance through every PR. Assert `unifiedMeshService.delegate === chatViewModel` in an instrumented test before PR 4. Note the DI change made the ordering safer, not riskier: the ViewModel now reads `MeshServiceHolder` itself instead of capturing two `lateinit` fields from `MainActivity`. |
| Back-order semantics change | Explicit tests for all four cases in `handleBackPressed`'s chain, landed with PR 11. |
| Long-lived branch conflicts | Every PR is independently mergeable; none should stay open more than a few days. |

## 9. Toolchain notes for the remaining modules

Things that cost time once and should not cost it twice.

**Kotlin does not smart-cast across a module boundary.** Moving `BitchatMessage`
into `:core:domain` broke six call sites of the form
`if (message.channel != null) { use(message.channel) }`, because a property
declared in another module could in principle change between the check and the
use. The fix is a local `val`, not `!!` — the local keeps the null-safety the
smart cast was providing. Expect a handful more of these each time a widely-used
type moves.

**AGP 9 reshaped the extension DSL.** `CommonExtension` is no longer generic
(it was `CommonExtension<*, *, *, *, *, *>` through AGP 8), and it exposes
getters only. The block-syntax overloads — `defaultConfig { }`,
`compileOptions { }`, `lint { }`, `buildFeatures { }` — are declared on the
concrete `LibraryExtension` / `ApplicationExtension`. Convention plugins must
configure the concrete type.

**AGP 9 applies the Kotlin Android plugin itself.** Convention plugins must not
apply `org.jetbrains.kotlin.android`; that is why the version catalog has no
entry for it.

**Metaspace.** `org.gradle.jvmargs` capped metaspace at 512m, which KSP and the
Dagger processors exhaust. The failure does not present as a memory error in the
task that caused it — it surfaces as `ClassLoader.defineClass` OOM in whatever
task happens to be loading classes, including lint on an unrelated module. Worse,
when it lands during `--write-verification-metadata` it can truncate
`gradle/verification-metadata.xml` to zero bytes. Raised to 1g. **Back that file
up before any regeneration run**, and check it is well-formed afterwards; the
repo's own guidance not to accept metadata generated after an unexplained
failure applies exactly here.

**New Compose modules must set `includeComposeMappingFile.set(false)`.** `:app`
and `:wear` both do, because Kotlin 2.4.10's optional Compose group-key mapping
depends on unspecified class-file iteration order. A library that omits it
reintroduces the nondeterminism the release pipeline byte-compares against. The
`bitchat.android.library.compose` convention plugin sets it centrally.

## 10. Open questions

- Does `:feature:conversations` need a `:domain` and `:data` split, or is
  `{api, presentation}` enough? Defer until PR 10; the other five features do not
  need it.
- Should `ApkDownloadViewModel` and `HotspotViewModel` move into
  `:feature:hotspot`, or is APK sharing its own feature? Defer to PR 9.
- Does the list-detail Scene use `material-listdetail` or a hand-rolled
  `scenes-listdetail` strategy? Decide in PR 10 against the actual layout.
