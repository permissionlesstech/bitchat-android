# PR-977 test notes

This note records the test coverage and findings added while validating the
expanded store-and-forward work. All test content and physical-device evidence
used synthetic data. Raw device evidence remains local and is not committed.

## Coverage matrix

| Capability | Automated coverage | Physical Mesh Lab coverage | Result |
|---|---|---|---|
| Durable private-message outbox | `MessageOutboxStoreTest`, `MessageRouterTest` | `durable_outbox`: recipient offline, sender process death, then delivery after reconnection | Pass |
| Direct and peer-courier routing | `CourierEnvelopeTest`, `CourierStoreTest`, `DirectCourierDepositPolicyTest`, `MessageRouterTest` | `courier_contract` | Pass for two-phone contracts; three-party delivery remains blocked below |
| Courier Noise X confidentiality and sender authentication | `NoiseCourierTest`, `BridgeCourierServiceTest` | N/A | Pass |
| Courier delivery acknowledgements | `MessageHandlerTest`, `MessageRouterTest` | Three-party courier delivery pending | Automated pass; physical blocked below |
| Courier fragmentation and custody commit | `FragmentingPacketSenderTest`, `CourierStoreTest` | `courier_contract` | Pass |
| Nostr kind-1401 bridge wire contract | `BridgeCourierServiceTest` with a deterministic in-memory relay | N/A | Pass |
| Relay acceptance before bridge completion | `BridgeCourierServiceTest` | N/A | Pass |
| Public-history retention and recovery | `GossipSyncManagerTest` | `sync_auto_recovery` and `sync_recovery` | Pass |
| Public file and fragment recovery | `GossipSyncManagerTest` | `sync_file_recovery` with a synthetic digest | Pass |
| Ordinary mesh regressions | Existing unit suite | `broadcast`, `dm`, `favorite_verification` | Pass |

The deterministic bridge test checks signed kind-1401 events, recipient and
expiry tags, receiver filtering/deduplication, size limits, rejection of
inconsistent metadata, and the rule that the outbox completion callback waits
for relay acceptance. `CourierEnvelopeTest` separately covers rotating recipient
tags. No test connects to public relays.

## Commands completed

- `./gradlew --no-daemon :app:testDebugUnitTest --max-workers=1`
- `./gradlew --no-daemon :wear:testDebugUnitTest --max-workers=1`
- `./gradlew --no-daemon lintDebug clientRewriteContractTest --max-workers=1`
- `./gradlew --no-daemon :app:assembleDebug --max-workers=1`
- `python3 -m py_compile tools/release_gate/mesh_lab.py`
- `git diff --check`

## Physical Mesh Lab results

The completed scenarios used two authorized phones in logical A/B roles and a
current debug build. They passed with synthetic message and file content:

- `broadcast`
- `dm`
- `favorite_verification`
- `courier_contract`
- `durable_outbox`
- `sync_recovery`
- `sync_auto_recovery`
- `sync_file_recovery`

`durable_outbox` exercises the production `MessageRouter` path, queues while
the recipient is actually offline, force-stops the sender, recreates the
router, and verifies receiver-side delivery only after reconnection.

`sync_auto_recovery` intentionally does not call the debug sync-request hook.
`sync_file_recovery` verifies receiver-side digest equality after replaying a
missed public file and its fragments.

## Findings

### Fixed: offline known contacts could skip peer-courier deposit

**Impact:** An offline known contact could resolve to its canonical contact ID
without recovering its cached Noise public key. The router then had no
recipient key and skipped peer-courier deposit entirely.

**Fix:** Contact resolution now recovers only a fingerprint-validated cached
Noise key for that canonical contact. The `ContactDirectoryTest` and
`MessageRouterTest` regressions cover resolution and the resulting single
courier deposit.

### Fixed: public-history archive could be lost on filesystems without atomic move

**Impact:** Persisting the public gossip archive silently discarded the
temporary archive when atomic rename was unsupported, defeating recovery after
restart on those filesystems.

**Fix:** Archive persistence now falls back to a normal replace-existing move
only when atomic move is unavailable. `GossipSyncManagerTest` verifies history
survives manager recreation.

### Fixed: Mesh Lab direct-link assertion produced a false failure

**Impact:** The runner required each endpoint to independently establish a
GATT client connection. One direct GATT link is bidirectional, so a healthy
link could be incorrectly reported as failed after one endpoint restarted.

**Fix:** `ensure_direct_link` now accepts a direct link observed from either
endpoint.

### Test-harness isolation improvement: debug transport toggles alone were insufficient

**Observation:** Disabling debug transports did not always make a counterpart
observe link loss promptly enough for an offline-routing test.

**Change:** Offline scenarios now force-stop the test app and temporarily turn
off the Bluetooth adapter before asserting the recipient is absent. This is a
test-isolation improvement; it is not classified as a production defect.

### Test infrastructure finding: concurrent test worker crashed before assertions completed

**Observation:** The initial combined Gradle invocation ended with a JVM-level
test-worker crash during the app unit-test task, before any test assertion
failure was reported.

**Resolution:** Re-running the app and Wear unit suites serially in fresh,
single-use Gradle processes passed. This is recorded as local test-runtime
instability, not as a product defect. The raw crash diagnostic remains local
and is not committed.

## Remaining coverage limits

- `courier_delivery` is implemented as a three-phone A→B→C scenario: C is
  offline for the deposit, A then goes offline, and C returns to receive only
  through B. It is **blocked (not run)** because only two authorized phones are
  currently attached. A third phone is required; reusing C offline/online is
  already part of the scenario.
- No public Nostr relay or desktop courier bridge was used. The bridge has a
  deterministic in-memory relay contract test, but live desktop/relay
  interoperability remains **not run**.
- Android/iOS live courier interoperability remains **not run**.

No unresolved product defects were found in the completed automated and
two-phone physical coverage.
