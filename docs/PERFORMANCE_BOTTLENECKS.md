# Performance bottlenecks under chat load

This review covers large mesh membership, busy geohash channels, and long-running
chat sessions. Each candidate is verified before its fix is applied. All fixtures
are synthetic. Operation counts describe algorithmic work, not measured Android
frame times. Raw logs and device evidence stay outside the repository.

## 1. Repeated encrypted identity-cache writes

**Status:** Confirmed and fixed; focused regression tests pass.

The once-per-second `ChatViewModel` peer poll calls four identity-cache setters
per known fingerprint. Each setter reads, copies, scans, and rewrites its whole
encrypted preference set, even when the value is unchanged.

**Baseline:** A synthetic unchanged refresh of 1,000 peers reads 4,000,000 set
entries and submits 4,000,000 entries across 4,000 preference updates. This is
quadratic in the number of peers when the caches have similar sizes.

**Change and validation:** Peer polling now submits one batch on `Dispatchers.IO`.
Each preference set is read once, and only changed sets are written. Individual
cache setters also skip unchanged values. The synthetic 1,000-peer steady-state
poll now reads 4,000 entries and performs zero writes: 99.9% fewer entries read
and 100% fewer unchanged writes. A first batch writes four sets rather than
4,000 sets. Tests also verify changed nicknames and restored cache values.

### Product decisions

None. Existing encrypted preference formats and identity validation are retained.

## 2. Unbounded public and channel histories

**Status:** Confirmed and fixed; retention and existing state-store tests pass.

`AppStateStore` retains public/channel messages and their deduplication data
without a retention bound. An append copies the complete existing timeline.

**Baseline:** Appending to a 10,000-message synthetic public timeline reads all
10,000 existing entries. Cumulative copying is quadratic over an unbounded run;
retained message payloads and deduplication data also grow with traffic.

**Change and validation:** Public/channel timelines retain the newest 1,000
messages. At most 20 channel timelines are retained by recent message activity.
Public deduplication IDs and content keys each retain the latest 10,000 entries.
`MessageManager` uses the bounded process-store snapshot instead of independently
appending a second copy. Tests cover oldest-message eviction, channel eviction,
recent replay suppression, updated media rows, and clearing state. A retained
public-ID index and channel membership checks prevent duplicate row IDs even
after the rolling recent-ID window expires.
Clear/leave operations update the shared store as well as the UI snapshot so the
next append cannot resurrect cleared rows; a regression reproduced and verifies
that case. Clearing visible history retains the bounded recent replay window.

**Estimated improvement:** Append copying is capped at 999 old entries rather
than growing without limit (at least 90% less than the 10,000-entry baseline).
Retained public/channel payload count is bounded at 21,000 messages. This is a
message-count bound, not a byte budget or a device memory measurement.

### Product decisions

Keep the latest 1,000 messages in each ephemeral public timeline and retain 20
channel timelines by recent activity. Older public messages and inactive channel
histories leave memory; no new disk archive is introduced. Private-chat history
and its existing durable retention policy are unchanged. Replay suppression is
bounded to the latest 10,000 public IDs/content keys, so older replays may be
admitted again. These limits favor responsiveness during long sessions.

## 3. Eager participant-row composition

**Status:** Confirmed by composition structure; Android runtime regression passes.

Mesh and geohash participants are rendered inside one outer lazy-list item.
`AnimatedRowColumn` eagerly creates each row and its animation/layout state.

**Estimated impact:** Composition and measurement scale with the full member
count rather than the visible viewport. Opening the people sheet or reordering a
large membership can cause frame stalls. Device timing is not measured yet.

**Change and validation:** Participant cards now use a keyed `LazyColumn` with a
400 dp maximum viewport and lazy-item placement animations. Both mesh and geohash
cards use it. An instrumented regression test checks that a 2,000-member card
initially composes fewer than 100 rows and that the last member remains reachable.
The test passes on a fresh disposable API 35 emulator. Its runner uses a plain
application so the fixture starts no app transports, identity persistence, or
location services. This is a functional composition/scrolling check, not a frame
time benchmark or physical-device visual review.

A separate before/after visual review built the actual component at the merged
base and reviewed commit in an isolated worktree. Four local captures on stable
Android 17 / API 37 cover three-member and 40-member synthetic cards at about
411 dp width in dark theme. Small-card geometry is unchanged; the large card
uses its 400 dp viewport and leaves the following footer visible. The temporary
debug Activity uses a plain application and starts no transports or location
services. Captures were inspected and their paired dimensions validated. Raw
images stay local under the repository privacy rules; this is component evidence,
not full-sheet or physical-device validation.

**Estimated improvement:** Row composition/state scales with the viewport and
prefetch region rather than all P participants. For a 2,000-member card this
should eliminate most off-screen row work; exact frame savings are unmeasured.

### Product decisions

Large participant cards scroll inside a viewport up to 400 dp high; small cards
still wrap their content. Every member remains accessible. Existing section
labels, membership, and ordering are retained. This introduces an inner scrolling
region in exchange for avoiding a larger sheet restructuring.

## 4. Redundant and unpaced gossip synchronization

**Status:** Confirmed by two failing regression tests before the fix; both pass after it.

The request filter advertises only a subset of cached packets while responses
consider the entire cache. Omitted known packets can therefore be retransmitted.
Responses also synchronously submit every missing packet.

**Baseline:** With default settings and identical synthetic 1,000-packet caches,
the original probe observed 639 redundant sends. An empty requester produced
1,000 immediate send callbacks. Exact redundant counts depend on packet IDs and
filter collisions; they are not a bandwidth measurement.

**Change and validation:** Advertisements and responses use the same recent
packet window, including deterministic packet-ID ordering for equal timestamps
and any further trimming needed to fit the filter. Before admitting a message,
reserve space for its sender's cached announcement when available; prefixes
remain closed under that dependency if encoding trims the tail. Responses send
announcements first, then messages in chronological order. Response packets are spaced
20 ms apart. Only one response batch runs at a time; stop/reset cancels it.
Tests exercise production filters and response handling with 1,000 cached
packets, and the existing filter/protocol contract tests pass.

**Estimated improvement:** Identical full caches produce zero responses in the
fixture instead of 639 redundant sends. An empty requester receives at most 355
packets with the default 400-byte, 1% filter settings, instead of an immediate
1,000-packet burst. Callback submissions are limited to about 50 packets/second
within a batch; this is not a transport-throughput guarantee. Different cache
windows can still cause bounded redundant traffic, and GCS false positives can
still omit missing packets.

### Product decisions

Automatic sync offers only the recent window representable by the local request
filter. Older cached packets outside it are not automatically recovered. A busy
responder ignores additional requests until its current batch finishes; peers
retry through their normal periodic sync. This bounds burst load at the cost of
slower recovery and possible starvation under continuous competing requests.
There is no wire-format or cryptographic change. See [sync.md](sync.md).

## 5. Full conversation reconciliation on membership updates

**Status:** Confirmed in the callback chain; change-detection regression test passes.

Every peer-list callback schedules main-thread work over all peers. Each peer
can trigger global private-conversation canonicalization and alias persistence,
even when its aliases have not changed.

**Estimated impact:** Join waves multiply peer resolution and full-history work.
For P peers and H retained private messages, repeated global reconciliation can
approach P times H work per snapshot, before identity-resolution overhead.

**Change and validation:** A conflated channel serializes peer snapshots. Alias
resolution and store reconciliation run on the I/O dispatcher. A reconciler
compares canonical conversation IDs and alias sets, then merges only changed
peers. Explicit connected-peer resolution also avoids a whole-mesh fingerprint
search for every member; a regression reproduced that unnecessary search before
the fix. A changed batch canonicalizes global conversation state once. Failures
do not kill the observer; a later snapshot retries. Tests cover 1,000 initial
peers, ten unchanged/reordered snapshots, alias promotion, and reconnect.

**Estimated improvement:** The synthetic unchanged snapshots cause zero history
merges instead of one per peer. A 1,000-peer changed batch runs one global
canonicalization instead of up to 1,000. Explicit connected-peer resolution uses
direct lookups rather than up to P fingerprint comparisons per member. Resolving
the snapshot still visits P peers, and contact/offline alias searches and genuine
conversation merges still cost work. This removes repeated history work and
moves it off the UI dispatcher; device latency is unmeasured.

### Product decisions

Pending intermediate peer snapshots may be coalesced into the latest snapshot.
The final membership and alias state is reconciled. Conversation identity,
unread counts, and delivery state retain their existing merge rules.
An explicit connected peer is preferred when resolving its aliases; if multiple
connected IDs share one identity, this avoids choosing an arbitrary other peer
from the mesh. Contact/offline inputs retain fingerprint-based discovery.

## 6. Whole-history rendering preparation

**Status:** Confirmed by synthetic read-count probes; rendering regressions pass.

Mention identity lookup is rebuilt when messages or participant snapshots change.
Arrival tracking also scans the timeline on insertions.

**Baseline:** At 20,000 synthetic messages, mention lookup reads 20,000 entries
and arrival tracking reads approximately 20,000 entries for one insertion.
Presence-only updates can invalidate the mention lookup without new messages.

**Change and validation:** History-based mention candidates are built on
`Dispatchers.Default` and cached separately from live participants. Presence
changes reuse those candidates; the two sources are combined before resolving
ambiguities. An append fast path in arrival tracking checks the old timeline's
boundaries and scans only its new suffix. Clears, replacements, and retention
trimming retain full reconciliation. Tests cover ambiguous names across cached
sources and existing animation/replacement rules.

**Estimated improvement:** A single append to the synthetic 20,000-message
timeline reads fewer than ten entries for arrival tracking rather than about
20,000 (>99.9% fewer). Presence changes read zero message entries for mention
preparation. Message changes still rebuild history candidates in O(H) on a
background dispatcher; combining candidate maps costs O(distinct mention tokens).
This does not eliminate all history work, especially for durable private chats.

### Product decisions

History-derived mention colors may briefly use the existing nickname fallback
while background preparation finishes. Switching conversations clears the old
history lookup immediately. The append animation fast path treats an unchanged
first ID and old last ID at the old boundary as an append; historical edits
combined with an append may consequently adopt inserted interior messages
silently. Message content and delivery are unaffected.

## 7. Unbounded incoming packet queues

**Status:** Confirmed: unlimited actor channels, per-packet launches, and a
never-evicted actor map are explicit in the original implementation. Load tests
pass for the replacement.

`PacketProcessor` uses an unlimited actor channel per peer, launches a coroutine
per incoming packet, and retains actors until shutdown.

**Estimated impact:** When ingress exceeds processing capacity, retained packets
and latency grow with the backlog; membership churn also retains idle actors.
No device memory or throughput threshold has been measured.

**Change and validation:** Sixteen fixed workers replace the per-peer actor map.
A peer consistently hashes to one FIFO channel with 64 queued slots. A shared
8 MiB payload budget includes queued and in-flight packets. Admission uses
`trySend` without launching a coroutine. Rejected packets never fall back to
unordered direct processing. Handler failures leave the worker alive; shutdown
cancels workers and releases queued packets. Both production delegates await
message/file and leave handling inside the worker, so payload reservations cover
suspending downstream work rather than ending at coroutine submission. Tests
block a suspending handler to verify the budget and FIFO, flood one queue with 2,000
packets, exercise the shared byte budget, verify FIFO processing and subsequent
admission, and inject a handler failure.

**Estimated improvement:** Worker count stays at 16 regardless of membership or
churn; queued count is at most 1,024 plus at most 16 in-flight packets. Retained
payload bytes are at most 8 MiB, replacing an unbounded backlog. Packet objects,
headers, reassembly, downstream transport buffers, and other app state are
outside that byte budget. Sustainable throughput still depends on handlers and
transport, and hash collisions can reduce concurrency.

### Product decisions

Drop the newest incoming packet when its worker queue or the shared payload
budget is full. Accepted packets from a peer retain FIFO ordering and security
validation; unrelated peers sharing a worker may wait behind one another.
Overload can drop announcements, handshakes, fragments, and messages. Existing
retry/sync mechanisms may recover some losses, but recovery is not guaranteed
for every packet type. This deliberately favors bounded memory and app survival
over retaining an arbitrarily large backlog. No new acknowledgement or wire
protocol is introduced. Physical overload/loss testing remains outstanding.

## 8. Sorting peers to count network size

**Status:** Confirmed in `PeerManager`; a relay regression also failed on the
original duplicate lookup and passes with a single lookup.

Relay decisions obtain the active count through `getActivePeerIDs()`, which
filters, allocates, and sorts the peer list, potentially twice per packet.

**Estimated impact:** O(P log P) work per count instead of O(P) counting, multiplied
by packet rate. Device CPU savings are not measured yet.

**Change and validation:** Count connected values directly and reuse that count
throughout each relay decision. Existing peer lifecycle tests verify count
behavior; the low-TTL relay test asserts one network-size lookup and successful
broadcast.

**Estimated improvement:** Each count is O(P) with no filtered map, ID list, or
sort. Low-TTL relays perform one count instead of two (50% fewer count calls).
At 1,000 peers the avoided sorting is on the order of 10,000 comparisons per
lookup, depending on input order. No device CPU percentage is claimed.

### Product decisions

None. The returned count must remain unchanged.

## Regression review

The review compares these changes with the merged geohash-presence fix. All
fixtures use synthetic data. The two review axes are recorded separately.

### Standards

Two confirmed regressions were found and fixed:

1. **Panic clearing race:** an I/O cache poll could resume after the main-thread
   identity wipe and restore old peer fingerprints, keys, or nicknames. Capture
   the cache generation before collecting the snapshot; reject stale batches
   under the process-wide wipe lock. The batch and wipe serialize across manager
   instances. A deterministic test resumes an old snapshot after a second manager
   wipes preferences, checks all four caches remain empty, and verifies a fresh
   snapshot can still persist.
2. **Reverse sync history:** newest-first selection also sent messages newest
   first, reversing a joining peer's arrival-ordered timeline. Keep recent-window
   selection but send announcements before messages and messages chronologically.
   The regression checks actual callback order and latest-message inclusion.

No additional documented-standard violations were found in the reviewed
changes. Shared source ownership remains in `app/`, including the Wear include.
Physical Mesh Lab validation remains outstanding as detailed below.

### Spec

Two incomplete fixes were confirmed and corrected:

1. **Missing sync identity dependencies:** a bounded recent window could omit an
   older announcement needed to authenticate newer messages. Selection now
   reserves that announcement before admitting the message. Each prefix retains
   its dependencies even if the GCS encoder trims it. Regression tests cover a
   single sender with 500 newer messages, many senders, announcements-first
   delivery, chronological replay, the response bound, and filter convergence.
2. **Downstream packet backlog:** production delegates launched message/file and
   leave work outside the packet worker, releasing the payload budget too early.
   Suspending delegates now await those handlers. A blocked-handler test proves
   in-flight bytes stay reserved, the next packet from that peer waits, overload
   is rejected, and capacity returns after completion.

A further retention edge case was fixed: clearing an unknown channel is a no-op,
so empty timelines cannot bypass the 20-channel limit. Its regression repeatedly
clears absent channels and verifies no entries are allocated.

All five review findings are addressed. The performance estimates remain
operation counts and bounded-resource estimates; physical throughput has not
been measured. The intentional retention, nested scrolling, recovery-window,
and overload policies remain documented under each bottleneck's product
section.

## Validation and limits

The initial audit used four local synthetic JVM probes against production code.
They confirmed cache-write amplification, history copying, history scans, and
redundant sync responses. No candidates in this review were rejected as false.
The previous 100 ms geohash participant-batching fix is retained.

Automated results:

- App JVM suite: 635 passed, 3 skipped (638 total).
- Wear JVM suite: 225 passed.
- `clientRewriteContractTest`: passed, including existing protocol vectors.
- Phone and Wear debug APK builds: passed; the phone test APK also builds.
- Participant card instrumentation: 1 passed with 2,000 synthetic members on a
  fresh API 35 emulator; off-screen composition is bounded and the final row is
  reachable.
- Before/after participant visuals: two inspected pairs on stable API 37, with
  small-card layout preserved and the large-card viewport bounded. Raw captures
  remain local. The locked Espresso library fails before app code on API 37, so
  this visual check uses an isolated temporary debug Activity and ADB capture;
  the automated scrolling test remains validated on API 35.
- App and Wear `lintDebug` tasks complete under the repository's non-aborting
  lint configuration. Reports are **not clean**: 305 app errors and 37 Wear
  errors remain. None of those errors intersects edited source lines. The
  explicit Android test-runner dependency adds a version-age warning while
  preserving the already-locked instrumentation version; no dependency version
  upgrade is part of these fixes.
- `git diff --check`: passed.

To repeat the automated checks:

```sh
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest clientRewriteContractTest \
  :app:assembleDebug :wear:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug :wear:lintDebug
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bitchat.android.ui.ParticipantListInstrumentedTest
```

The second command requires an isolated emulator; the test uses synthetic rows
and does not exercise mesh behavior. Performance estimates above are operation
counts or resource bounds, not measured FPS, battery use, or end-to-end latency.

### Physical Mesh Lab handoff

**Result: blocked (not run).** No physical test devices were connected during
this review. Unit tests and the UI emulator do not establish physical mesh
correctness. Required coverage includes routing, private messaging, sync
recovery, file/fragment delivery, identity persistence, and overload behavior.

Use two authorized disposable physical lab devices. Setup and `all` clear app
data/identities and alter device settings: obtain explicit operator approval for
those devices before running them. Set the two selector variables privately,
keep raw evidence local, and inspect peers after setup; continue only when all
peers belong to the controlled lab. Follow [the Mesh Lab skill](../.agents/skills/mesh-lab/SKILL.md)
and [the runbook](release-gate-runbook.md) for safeguards and coverage limits.

```sh
python3 tools/release_gate/mesh_lab.py setup \
  --serial-a "$MESH_SERIAL_A" --serial-b "$MESH_SERIAL_B" \
  --apk app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
python3 tools/release_gate/mesh_lab.py cmd --serial "$MESH_SERIAL_A" peers
python3 tools/release_gate/mesh_lab.py cmd --serial "$MESH_SERIAL_B" peers
```

After the controlled-peer check:

```sh
MESH_EVIDENCE_DIR="$(mktemp -d)"
if [ -z "$MESH_EVIDENCE_DIR" ] || [ ! -d "$MESH_EVIDENCE_DIR" ]; then exit 1; fi
chmod 700 "$MESH_EVIDENCE_DIR"
python3 tools/release_gate/mesh_lab.py scenario all \
  --serial-a "$MESH_SERIAL_A" --serial-b "$MESH_SERIAL_B" \
  --out "$MESH_EVIDENCE_DIR"
```

The existing two-device suite does not establish many-member throughput,
queue-overload recovery, geohash relay load, or sustained resource bounds. Those
need a controlled synthetic-load/device profiling follow-up. Never use live or
historical user location data for that work.
