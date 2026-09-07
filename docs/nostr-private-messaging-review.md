# Mutual-favorite private messaging review

This review covers the phone's path from favorite exchange and local echo through
mesh selection, Nostr publication, background reception, receipts, reconnect,
catch-up, deletion and process restart. Wear shares the admission and storage
changes, but does not gain Nostr transport.

## Findings addressed

| Failure | Change |
| --- | --- |
| Selecting Nostr marked a message sent before encoding or relay acceptance | Routing returns queued. Only an accepting relay `OK` advances Nostr messages to Sent; only the intended contact's authenticated receipt advances Delivered/Read. |
| Process death lost the in-memory outbox | Schema 5 stores encrypted delivery jobs in the same SQLite transaction as the outgoing echo. The process-owned worker recovers jobs on startup. |
| A mesh route selected just before disconnection never fell back | One mesh attempt gets a 30-second ACK window; eligible mutual contacts then use Nostr with the same wire message ID. |
| Multiple transport layers owned independent pending copies | The coordinator owns DM retries. Confirmed publication bypasses the relay manager's separate best-effort event queue. |
| Incoming deduplication consumed failed admissions, and replay did not re-ACK | Live gift wraps reach durable admission on every replay. A stored duplicate recreates receipt intent; storage failures remain retryable. |
| Message IDs could collide across unrelated conversations | New incoming local IDs include the canonical contact identity; the original wire ID is retained for receipts. Database conflicts cannot merge unrelated owners. |
| A claimed favorite public key could select the relationship to mutate | The authenticated Nostr author selects the existing Noise binding. Mesh controls use the authenticated Noise session key. Claims cannot target a different author. |
| Controls replayed in relay order could undo more recent state | Relationship state records authenticated packet time and a tie-break ID. Pending local controls retain their original ID and timestamp. |
| Receipts identified only a globally searchable message ID | Receipt admission verifies the canonical conversation and outgoing direction before updating status. Status advancement and message-job removal are transactional. |
| Background reception was effectively read and lacked the normal notification path | Background DMs are stored unread and notify once after durable admission. Read receipt intent is separate from delivery. |
| Opening an offline chat had no Nostr read-receipt path | The router persists read receipt intent together with the per-message local read write; live focused reception stores a read receipt with admission. |
| A 48-hour, 100-event subscription missed randomized gift wraps and busy history | Per-account, per-relay catch-up scans 30 days plus two days and 15 minutes of wrapper overlap. Saturated ranges are split, including same-second ties. Checkpoints advance only after EOSE and successful admission. |
| DNS failures and retry limits could permanently strand relay connections | DNS failures use capped exponential reconnects; network availability triggers reconnection. Server close handshakes are answered, and CLOSED subscriptions retry. |
| Delayed callbacks and retry writes could survive reset or completed delivery | Reset generations gate callbacks and processing. Retry updates require an existing matching job, so they cannot recreate work removed by an ACK or deletion. |
| Duplicate implementations obscured the active path | Removed unused NostrClient/NostrTestManager and the router/transport memory queues. Nostr reception no longer retains UI chat managers. |

## Ownership and status contract

`ConversationRepository` owns encrypted message and delivery storage.
`PrivateDeliveryCoordinator` performs bounded batches of four jobs, checks current
blocking and mutual-favorite state, chooses transport, and retries failures.
`NostrTransport` encodes the existing Bitchat envelope and requests confirmed
publication. `NostrDirectMessageHandler` authenticates, authorizes and admits
incoming content without a ViewModel. `NostrInboxSync` is the testable history
scan; `NostrBackgroundRuntime` supplies lifecycle and relay checkpoints.

Sending means durable work exists. Sent means at least one selected Nostr relay
accepted the event, not that the recipient received it. Delivered and Read require
authenticated contact receipts and cannot be downgraded by later relay results.
Messages remain retryable after relay acceptance until a recipient receipt arrives.
Receipts have no receipt-of-receipt protocol; Nostr receipt jobs complete on relay
acceptance and message retransmission can recreate them.

Jobs expire after 30 days. The queue allows 100 jobs per conversation and 500 in
total. Capacity failure rolls back a new outgoing echo rather than admitting a
message without its delivery intent. Retention pruning preserves pending outgoing
messages. Deleting messages/conversations removes their associated jobs and leaves
replay tombstones. Migration marks old Sending rows failed; it does not replay
historical Sent messages whose delivery history cannot be recovered.

## Compatibility boundaries

The existing Bitchat `v2:` encryption codec and embedded packet formats are retained.
The codec functions now explicitly name the legacy Bitchat format; compatibility
wrappers remain for existing callers. This is not a migration to standard NIP-44
ciphertext. Outer signature/recipient, seal signature/author, and rumor kind, ID and
recipient validation are enforced. The wire message ID is unchanged across mesh and
Nostr; incoming database IDs are local implementation details.

The catch-up window is a product retention policy, not a promise that any relay
retains 30 days of events. A relay that truncates below the requested limit without
indicating it cannot be detected reliably. A saturated single second beyond the
scan cap remains incomplete rather than silently advancing its checkpoint.

## Validation

Regression coverage includes loopback WebSocket OK acceptance/rejection, durable
restart recovery, ACK/retry races, queue-capacity rollback, receipt-storage failure,
background unread state, duplicate re-ACK, sender-bound controls and receipts,
contact-scoped ID collisions, deletion and reset replay, long-offline messages,
and bounded history pagination. Existing legacy protocol tests remain in place.

Run the JVM/build checks with the repository's configured JDK and SDK:

```sh
./gradlew testDebugUnitTest lintDebug :app:assembleDebug :wear:assembleDebug
python3 -m unittest tools.release_gate.test_nostr_relay_fixture
```

Physical Mesh Lab validation is **blocked (not run)** until two authorized,
disposable physical test devices are available. The new `nostr_dm` scenario is
explicit-only and is excluded from `all`: it requires externally enforced internet
egress isolation and an in-process loopback relay. It exercises the real durable
router, mutual favorite exchange, bidirectional out-of-mesh delivery and ACKs, and
receiver restart after relay publication. The existing `dm` scenario bypasses the
router and does not cover this contract.

After the operator authorizes destructive setup and establishes egress isolation,
use the normal setup from the Mesh Lab runbook, then:

```sh
MESH_LAB_NOSTR_ISOLATED=1 python3 tools/release_gate/mesh_lab.py scenario nostr_dm \
  --serial-a <device-a> --serial-b <device-b> --out <local-evidence-directory>
python3 tools/release_gate/mesh_lab.py scenario all \
  --serial-a <device-a> --serial-b <device-b> --out <local-evidence-directory>
```

The environment flag records operator-established isolation; it does not configure
a firewall. Keep non-loopback internet egress blocked before launching either app.
The debug hook permits cleartext only to loopback and uses ADB reverse for the local
relay. Scenario teardown removes its reverse mappings; the apps retain the fixture
relay configuration for that process and their mesh services were stopped. Restart
the apps before ordinary mesh testing, keeping egress isolated until disposable
fixture state has been cleared. Never publish raw evidence or device selectors.

## Follow-up work

1. Run the physical scenario, phone/watch mesh regression set, and Android/iOS
   offline interoperability matrix before promoting this change to a release.
   Include process death during each persistence boundary and a receiver offline
   for several days. JVM tests do not establish radio or OEM background behavior.
2. Replace the remaining fingerprint-only favorite preference fallback after a
   migration strategy for contacts without authenticated Noise keys is agreed.
   Known phone contacts now persist relationship intent before updating the UI;
   legacy fingerprint favorites and Wear's synchronous preference API still exist.
3. Add user-visible outbox capacity, expiry, retry and incomplete-sync diagnostics.
   Today queue admission returns failure, failed jobs update message status, and
   history failures retry; there is no complete delivery troubleshooting UI.
4. Consolidate bulk conversation read operations and per-message receipt intent.
   The current durable path covers live reception and loaded messages on chat open;
   reading an unloaded history through a bulk action needs an explicit receipt
   policy rather than silently generating unbounded network work.
5. Separate corrupt-job quarantine from whole-queue loading, and reserve capacity
   for receipts/control work under sustained backlog. Measure catch-up crypto/IO
   cost before changing overlap or pagination bounds.
6. A standards-based codec migration, relay inbox discovery, authenticated-relay
   support and offline media each need separate compatibility work. They are not
   introduced implicitly by this reliability refactor.
