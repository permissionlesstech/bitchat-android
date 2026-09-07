# iOS feature parity review

Reviewed against iOS commit `9b84b361225facd8e623f25d76f889d3dc54a879` and Android main
`936a4cdf6d91a944698f7b46528962aef09c1f9c`. The merged-PR window is August 9 through
September 8, 2026 (UTC). This is a source and automated-test assessment; it is not
an assertion of physical Android/iOS interoperability.

## Recent iOS changes that matter

| Merged PR | Android finding and action |
| --- | --- |
| [#1647 — timestamp sanity](https://github.com/permissionlesstech/bitchat/pull/1647) | Bound verification QR timestamps in both directions without integer overflow; reject implausible Nostr DM rumor timestamps separately from randomized outer envelopes. Validate kind, signature, recipient and rumor/seal consistency on the common decrypt path. |
| [#1598 — reachable recent chats](https://github.com/permissionlesstech/bitchat/pull/1598) | Android main already persists and exposes recent private conversations. Preserve that implementation; integrate new outboxes and groups with it. |
| [#1597 — connectivity truthfulness](https://github.com/permissionlesstech/bitchat/pull/1597) | Add a persistent Bluetooth/Tor availability banner. Failed handshakes display an open lock; a closed lock requires an established session. |
| [#1595 — honest privacy claims](https://github.com/permissionlesstech/bitchat/pull/1595) | Default notification text previews off, make enabling them explicit, and describe panic wipe as local deletion with visible outcomes. |
| [#1588 — confirmable panic wipe](https://github.com/permissionlesstech/bitchat/pull/1588) | Replace immediate triple-tap erasure with confirmation, progress, success, and retryable failure. Suspend private writes and background forwarding during erasure; surface failures from persistent stores. |
| [#1596 — remove screenshot broadcast](https://github.com/permissionlesstech/bitchat/pull/1596) | No new screenshot reporting is added. This iOS removal is a privacy correction, not a feature to port. |
| [#1657](https://github.com/permissionlesstech/bitchat/pull/1657), [#1656](https://github.com/permissionlesstech/bitchat/pull/1656), [#1654](https://github.com/permissionlesstech/bitchat/pull/1654) — localization | Android has its own resource/localization system. New controls use resources; full translation coverage for the newly integrated group/board/settings surface remains follow-up work. |
| [#1653](https://github.com/permissionlesstech/bitchat/pull/1653), [#1651](https://github.com/permissionlesstech/bitchat/pull/1651) — deterministic tests | Keep coroutine/queue tests deterministic and exercise observable admission and revocation behavior. Swift-specific timing changes do not require an Android port. |
| [#1648 — blocking dead-code scan](https://github.com/permissionlesstech/bitchat/pull/1648) | Preserve Android lint and client rewrite contract gates; Periphery itself is Swift-specific. |

The immediately preceding [#1645](https://github.com/permissionlesstech/bitchat/pull/1645)
(fail-closed handshake identity and secure randomness) and
[#1646](https://github.com/permissionlesstech/bitchat/pull/1646) (blocking SwiftLint)
were also inspected as context, but merged August 8 and are outside this window.
Android already has authenticated Noise peer binding and secure-random primitives;
new courier/group paths must preserve those properties.

## Feature matrix and implementation

Paths in the iOS column are relative to the iOS repository. Android paths are
relative to this repository. Features below also include older iOS functionality
still absent from Android main; they are not all attributed to the recent PRs.

| Capability | iOS evidence | Android main | This implementation |
| --- | --- | --- | --- |
| Durable private text retries, offline courier | `bitchat/Services/Courier/MessageOutboxStore.swift`, `bitchat/Services/Gateway/BridgeCourierService.swift` | No equivalent durable outbound courier pipeline | Keystore-encrypted text outbox, retry/expiry and authenticated acknowledgements; direct mesh deposits and opt-in relay courier. |
| Durable private media receipts | `bitchat/Services/BLE/BLEPrivateMediaReceiptStore.swift`, `BLEPrivateMediaSessionStore.swift` | Encrypted private media and PTT already exist; no stable receipt/retry lifecycle | iOS-compatible stable IDs, encrypted bounded media outbox, durable receiver admission, duplicate/tombstone ACKs, matching-recipient retries, and single-row finalized PTT notes. |
| One-time prekeys | `bitchat/Services/Prekeys/LocalPrekeyStore.swift`, `PrekeyBundleStore.swift` | Not available | Signed bundle validation, persist-before-use assignment/consumption, refresh and typed synchronization. |
| Private groups | `bitchat/Services/Groups/GroupProtocol.swift`, `GroupStore.swift`, `bitchat/ViewModels/ChatGroupCoordinator.swift` | Not available | Creator-signed membership/key epochs, encrypted messages, 16-member bound, replay checks, group commands/list, durable history and process-lifetime handling. |
| Signed notices and mesh board synchronization | `bitchat/Protocols/BoardPackets.swift`, `bitchat/Services/Board/BoardManager.swift` | Location notes already exist, signed mesh board absent | Signed creation/deletion, TTL, scoped synchronization, storage and unified notices UI. Preserve existing location privacy gates. |
| Vouch attestations | `bitchat/Protocols/VouchAttestation.swift`, `bitchat/ViewModels/ChatVouchCoordinator.swift` | Direct verification only | Signed bounded-age attestations and persisted derived trust; a vouch remains distinct from direct verification. |
| Mesh-to-mesh relay bridge | `bitchat/Services/Gateway/BridgeService.swift` | Not available | Explicit opt-in forwarding with event validation, bounded dedup/rate limits, and revocable queued publication permissions. |
| Nearby internet gateway | `bitchat/Services/Gateway/GatewayService.swift` | Not available | Separate default-off gateway toggle; signed geohash events use carrier directions 1/2, authenticated capability discovery, timestamp checks, bounded forwarding and relay-echo suppression. |
| Custom relays | `bitchat/Nostr/NostrRelaySettings.swift`, `NostrRelayURL.swift` | Built-in relay selection | Persistent custom secure WebSocket relays, URL validation, subscription updates, removal and panic cleanup. |
| Incoming text/URL sharing | `bitchat/Services/SharedContentHandoff.swift`, `bitchat/App/SharedContentImportModel.swift` | No incoming text share target | Android text share target stages a bounded draft for user review; never auto-sends. |
| Channel invitations | `bitchat/Services/ChannelShare.swift` | No invitation import/export | Explicit `bitchat://geohash/…` links and share action; import selects a manual channel without requesting device location. |
| Notification privacy | `bitchat/Services/NotificationPrivacySettings.swift` | Sender/text previews exposed by default | Generic notification content by default and explicit preview control; turning previews off clears existing notification/shortcut surfaces. |
| Ping/route diagnostics | iOS mesh diagnostic packet/command paths | No matching commands | Versioned ping/pong payloads, bounded TTL/rate handling, `/ping`, `/trace` and mesh topology display. |

Android main already supports Noise encryption, Nostr/Tor location chat, QR
verification, private image/audio transfer, live PTT, Cashu handling, location
notes and retained private conversations. These are integration constraints,
not missing features. Peer-ID rotation is not included: an iOS primitive alone
does not establish an end-to-end feature to advertise.

## Implementation strategy

1. Reuse and integrate the existing Android work for courier delivery, groups,
   signed boards, vouching, bridging and diagnostics instead of introducing a
   competing router or conversation database. The integration builds on PRs
   [#877](https://github.com/permissionlesstech/bitchat-android/pull/877),
   [#769](https://github.com/permissionlesstech/bitchat-android/pull/769),
   [#768](https://github.com/permissionlesstech/bitchat-android/pull/768),
   [#778](https://github.com/permissionlesstech/bitchat-android/pull/778) and
   [#770](https://github.com/permissionlesstech/bitchat-android/pull/770), with
   verification hardening informed by #910 and #927.
2. Treat durable receipt semantics, authenticated capabilities, timestamp checks,
   panic admission gates and publication revocation as prerequisites for safe
   feature exposure. A relay accepting an event is not a recipient delivery ACK.
3. Own group and retry work at application/process lifetime. Activity attachment
   supplies navigation only; it must not decide whether an encrypted message is
   persisted or acknowledged.
4. Keep phone-only capability bits disabled on Wear until their runtimes are
   present. Shared packet parsing/sync comes from `app/` through the Wear source
   include list. Private-media encryption remains independent of receipt support.
5. Validate wire vectors and storage/race behavior locally, then run physical
   Mesh Lab and iOS interop before treating parity as release-ready.

## Wire and persistence contract

- Existing wire values are retained. Group invite/update use Noise types 6/7;
  group messages use packet `0x25`; diagnostics use `0x26`/`0x27`.
- Capability flags are advertised only when the owning runtime is present:
  prekeys bit 0, gateway bit 2, groups bit 3, boards bit 4, vouch bit 5,
  diagnostics bit 6, bridge bit 7, private media bit 8 and media receipts bit 9.
  Gateway and bridge bits follow their independent opt-in settings.
- Typed gossip includes signed boards, prekeys and groups, retaining compatibility
  with legacy sync masks. See [sync.md](sync.md).
- Stable media message IDs hash the iOS domain and length-prefixed sender,
  recipient and supported filename. `PrivateMediaMessageIdentityTest` contains
  an exact iOS-compatible vector. Original encoded payloads survive retries.
- Private-media outbox limits: 100 records, 8 MiB per record, 64 MiB total,
  eight attempts and 24-hour lifetime. Only authenticated live receipt support
  enables retry. Missing/corrupt storage does not produce a delivery ACK.
- A deleted received message remains acknowledged through its durable tombstone;
  replay must not recreate it. Encrypted media also requires a readable saved file.
- Bridge/gateway publication permissions carry a generation. Turning a feature
  off invalidates both disconnected-queue entries and already-selected writes;
  re-enabling cannot revive old permission.
- Channel invitation tests use synthetic cells only. No implementation/test
  workflow requires reading real device location.

## Release-readiness and remaining work

Local validation results and visual evidence are reported in the pull request.
Physical Mesh Lab and Android-to-iOS interoperability are required separately:

- Offline text/media delivery, process death and restart, duplicate receipt after
  deletion, blocked/expired/corrupt payloads and panic during retry.
- BLE and Wi-Fi Aware delivery with the Activity closed; multi-hop courier with
  the original sender unavailable; phone-to-watch capability fallback.
- Group invite/update/removal, stale epochs, restart and background notification.
- Board signature/delete/sync and bridge/gateway disable/re-enable while relays
  disconnect or delayed writes are queued.
- One-time-prekey races and consumption persistence, plus iOS vectors on both
  real clients. Static screenshots cannot establish any of these properties.

The updated panic-help sentence falls back to English where previous translations
still promised immediate erasure; those stale claims are removed. Full locale coverage, accessibility review and the physical matrix remain
release work. Manual-channel Nostr board publication still obeys the existing
location-note privacy gate; this integration does not weaken it to force a
successful publish. No claim is made that this large integration is ready to
merge without the hardware and cross-client review above.
