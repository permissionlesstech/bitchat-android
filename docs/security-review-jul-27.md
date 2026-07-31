# Bitchat Android — Security Review

**Commit:** 92d07b22 (worktree: `~/.opencode/worktrees/bitchat-security-review`)
**Scope:** Exploitable bugs, privacy violations, tracking opportunities, DoS vectors.
**Method:** Read-only static review of `crypto/`, `noise/`, `identity/`, `mesh/`, `protocol/`, `net/`, `service/`, `nostr/`, `geohash/`, `ui/`, `features/`, manifest & build config. All findings verified against source.

---

## CRITICAL

### C1. Noise session key material (DH shared secrets, chaining keys) written to logcat — ships in release
`noise/southernstorm/protocol/SymmetricState.java:135-159` (also 98-104, 168-182)
`mixKey()` logs the raw X25519 shared secret ("Input data"), current and new chaining keys in hex. `proguard-rules.pro` has **no** `assumenosideeffects` for `android.util.Log`, so this reaches the release APK. Anyone with the logcat transcript of a handshake can recompute `split()` outputs and fully decrypt the transport session. **Release-blocking.**

### C2. Remote decompression bomb — pre-auth memory exhaustion via ~30-byte BLE write
`protocol/BinaryProtocol.kt:429-454`, `protocol/CompressionUtil.kt:75-118`
For compressed v2 packets the decoder reads a 4-byte attacker-controlled `originalSize` and immediately does `ByteArray(originalSize)` — up to 2 GiB per packet. The ratio guard is skipped when `compressedSize == 0` and is 50,000:1 anyway (real deflate max ≈ 1032:1). Runs inline on the GATT callback path; any nearby unauthenticated BLE peer can OOM/crash the foreground service with tiny writes.

---

## HIGH

### H1. Replay-window bit shift direction is wrong — Noise transport replay protection broken
`noise/NoiseSession.kt:69-106`
Bit indexing is LSB-first, but the window-shift code right-shifts the byte string. A replayed captured frame (nonce 0) tests a zero bit and is accepted; only the single most-recent nonce is reliably blocked. A passive BLE observer can replay DMs, ACKs, and receipts.

### H2. Announcement signing key not bound to Noise key — "first announce wins" peer-ID impersonation
`mesh/AnnouncementIdentityValidator.kt:13-36`, `mesh/SecurityManager.kt:282-313`, `mesh/MessageHandler.kt:265-299`
An attacker can replay a victim's Noise public key (→ victim's peerID) inside an announcement signed with the *attacker's* Ed25519 key. On fresh installs or for never-authenticated peers the forged binding wins, and the genuine victim's later announce is rejected as "key replacement".

### H3. Decrypted private message content written to logcat
`mesh/MessageHandler.kt:88` (also 125; `nostr/NostrProtocol.kt:277`)
`Log.d` logs the first 30 chars of every decrypted E2E private message plus sender peerID. Not stripped in release. Defeats E2E guarantees for any logcat reader. Related: `ui/DataManager.kt:161-196` dumps the full favorite/fingerprint social graph at startup (Low).

### H4. E2E-encrypted DM content exposed in system notifications without lock-screen redaction
`ui/NotificationManager.kt:224-246`
Decrypted DM bodies (mesh + Nostr) are posted with full text in `BigTextStyle`/`InboxStyle`; no `VISIBILITY_SECRET` / lockscreen visibility set. Content renders on lock screen and to any notification-listener app; no opt-out.

### H5. No signature verification on incoming public Nostr events — relay can forge any user
`nostr/GeohashMessageHandler.kt:44-105`, `nostr/LocationNotesManager.kt:358-414`, `nostr/NostrRelayManager.kt:663-706`
`NostrEvent.isValidSignature()` (exists, `NostrEvent.kt:171`) is never called for kind 20000/20001/1. A malicious/compromised relay (auto-selected from a daily third-party list, see M9) can inject events with arbitrary pubkeys — impersonating any user in any geohash channel, spoofing participants, caching attacker-chosen nicknames, registering DM aliases.

### H6. Automatic delivery/read receipts over Nostr = online-presence & location oracle
`nostr/NostrDirectMessageHandler.kt:134-138,165-175`, `nostr/NostrTransport.kt:316-384`
Any decryptable gift-wrapped DM triggers an automatic signed DELIVERED ack. For geohash DMs the ack uses the public deterministic per-geohash identity, so anyone can probe a target's known geohash pubkey and confirm the device is online *now*, and chart activity patterns. No setting gates this.

### H7. Building-precision geohash published publicly and permanently in location notes
`geohash/LocationChannel.kt:8` (precision 8 ≈ 19×38 m), `nostr/LocationNotesManager.kt:119-165`
Kind-1 (relay-archived, persistent) notes carry the 8-char geohash, exact `created_at`, and an optional plaintext nickname, signed by a stable pubkey — a permanent public record "pubkey X was within ~30 m of this spot at time T". REQ `#g` filters also disclose precise location to relays.

### H8. Deterministic per-geohash identities are stable forever — long-term passive location tracking
`nostr/NostrIdentity.kt:137-177`, `ui/GeohashViewModel.kt:184-193`
Geohash identity = HMAC(deviceSeed, geohash), never rotated. A passive observer of a channel can recognize returning users months later and assemble their full history in that channel; the plaintext `["n", nickname]` tag (`nostr/NostrProtocol.kt:167-169`) links the same person across channels.

### H9. Unbounded per-peer actor map and unlimited packet queues — memory/coroutine exhaustion
`mesh/PacketProcessor.kt:43-95`, `mesh/BluetoothPacketBroadcaster.kt:122-124`
A new coroutine actor with `Channel.UNLIMITED` is created per attacker-chosen `senderID` *before* any security validation, with no eviction. Spraying packets with random sender IDs grows live coroutines + unbounded channels forever.

### H10. Unauthenticated packet types relayed mesh-wide with attacker-controlled TTL — flood/amplification
`mesh/SecurityManager.kt:268-280`, `mesh/PacketRelayManager.kt:59-107,134-163`
Signature verification enforced only for ANNOUNCE/MESSAGE/FILE_TRANSFER/LEAVE. FRAGMENT, REQUEST_SYNC, NOISE_* verify unconditionally and are relayed (unconditionally at TTL ≥ 4) with no rate limit. One BLE radio can make the entire mesh re-broadcast forged traffic, draining bandwidth and battery.

### H11. Unsigned REQUEST_SYNC forces bulk re-broadcast — amplification
`mesh/BluetoothMeshService.kt:671-676`, `sync/GossipSyncManager.kt:168-199`
A spoofed REQUEST_SYNC with an empty (forgeable) GCS filter makes a victim dump its entire sync cache onto the radio; repeating keeps neighbors transmitting continuously. No rate limit or response budget.

### H12. ANNOUNCE replay with TTL=7 forces Noise session teardown + fake "direct neighbor"
`mesh/SecurityManager.kt:78-90`, `mesh/BluetoothMeshService.kt:586-621`
Duplicate ANNOUNCEs are re-accepted at TTL ≥ 7; direct-link is inferred from TTL alone (excluded from the signature, attacker-settable). Replaying a victim's recent ANNOUNCE at TTL=7 repeatedly tears down the victim's Noise sessions, breaking in-flight DMs.

---

## MEDIUM

### M1. No low-order point / all-zero DH rejection — Noise spec violation, key-compromise downgrade
`noise/southernstorm/protocol/HandshakeState.java:750-762,1034-1040,1050-1068`
Only the all-zero ephemeral key is rejected; other low-order Curve25519 points pass, DH outputs never checked, remote static key unvalidated. A malicious identity with low-order keys yields all-zero DH outputs → publicly derivable session keys. `SecureIdentityStateManager.validatePublicKey()` (identity/…:403-417) is debug-only and its blocklist misses real low-order points.

### M2. Unbounded crypto work + session state per forged handshake — crypto/memory DoS
`noise/NoiseSessionManager.kt:228-252,312-316`
Any frame from any spoofed peerID creates a responder session (~3 X25519 scalar mults) inside a `@Synchronized` method, with no cap on half-open responder sessions (staleness check only on the initiate path).

### M3. Stable 8-byte peerID in BLE scan response — passive long-term tracking
`mesh/BluetoothGattServerManager.kt:400-412`, `mesh/BluetoothMeshService.kt:54`
The scan response embeds the truncated fingerprint of the *persistent* static Noise identity, surviving restarts and MAC rotation (by design, for dedup). Any passive sniffer can track a user across time/place. Nickname is broadcast in every ANNOUNCE; ANNOUNCE gossip TLVs disclose the user's neighbor graph.

### M4. Fragment reassembly poisoning — cross-sender fragment-ID collision
`mesh/FragmentManager.kt:32,202-263`
Reassembly keyed by 8-byte fragment ID only (not sender). Fragments are unsigned; an attacker injects one colliding fragment to destroy a victim's in-flight (up to 1 MB) transfer. Bounded by existing caps; DoS/corruption, not forgery.

### M5. Legacy plaintext Ed25519 private key can persist after "migration"
`crypto/EncryptionService.kt:505-524`
Old plaintext key is deleted only if the encrypted store doesn't already have one; otherwise the plaintext private key remains on disk indefinitely.

### M6. Attacker-controlled image decode bombs / main-thread decode
`ui/media/ImageMessageItem.kt:74`, `ui/media/FullScreenImageViewer.kt:75`, `ui/MessageComponents.kt:294-304`
Received images decoded with `BitmapFactory.decodeFile` — no bounds check, no `inSampleSize`, on the main thread during composition. A small PNG with huge dimensions → instant OOM when the chat renders. `readBytes()` re-reads whole files on every recomposition.

### M7. Received files auto-downloaded unencrypted; size limit only on send path
`features/file/FileUtils.kt:194-263`, `nostr/NostrDirectMessageHandler.kt:191-194`
Nostr DM path accepts up to 10 MB per message (`AppConstants.kt:71`); a malicious contact can fill `cacheDir` indefinitely. Filenames are sanitized (path traversal verified not exploitable).

### M8. Exported MainActivity acts on attacker-supplied intent extras
`AndroidManifest.xml:98-115`, `MainActivity.kt:828-894`
Any app can fire intents with `EXTRA_OPEN_PRIVATE_CHAT`/`EXTRA_PEER_ID` to open arbitrary chat sheets and silently clear the victim's pending notifications, or trigger the verification sheet UI. QR payload itself is cryptographically validated — no verification forgery.

### M9. Relay directory auto-updates daily from third-party GitHub CSV, unsigned/unpinned
`nostr/RelayDirectory.kt:29,152-191`
Compromise of `permissionlesstech/georelays` steers all users to attacker relays → precise `#g` filters + forged-event injection (H5).

### M10. Geohash subscription filters & geo-nearest relay selection leak location to relays/observers
`nostr/NostrRelayManager.kt:131-147`, `nostr/RelayDirectory.kt:88-106`, `nostr/NostrFilter.kt:37-71`
Each relay learns subscribed cells; `#p` DM filters reveal owned pubkeys. Mitigated: Tor ON by default, fail-closed proxy config (`net/ArtiTorManager.kt:151,224`).

### M11. Unbounded outbound Nostr message queue — never drained, re-sent on reconnect
`nostr/NostrRelayManager.kt:107,286-288,862-871`
`messageQueue` entries are never removed after send; every relay reconnect re-sends full history (duplicate gift wraps, memory growth, extra metadata).

### M12. Unbounded identity-keyed caches — memory DoS by malicious relay (compounds H5)
`nostr/GeohashRepository.kt:22-29,61,104-118`
`geohashParticipants`, `geoNicknames`, etc. grow per unique pubkey with no eviction; forged events from unlimited fresh pubkeys exhaust memory. No WebSocket frame size cap (`NostrRelayManager.kt:874-876`).

### M13. "NIP-44" DM encryption omits padding — exact plaintext length leakage; not real NIP-44
`nostr/NostrCrypto.kt:211-260,267-293`
Raw XChaCha20-Poly1305 over unpadded UTF-8; relays/observers see exact DM lengths (receipt vs message vs file) and it's incompatible with real NIP-44 clients. AEAD itself sound.

### M14. Peer-table flooding with throwaway identities
`mesh/PeerManager.kt:93,228-240,551-564`
No cap on the peers map; cheap self-signed ANNOUNCEs create verified entries and can each trigger Noise handshakes. 3-minute sweep is the only bound.

### M15. No rate limiting on GATT writes / inbound packet processing
`mesh/BluetoothGattServerManager.kt:232-277`, `mesh/BluetoothGattClientManager.kt:644-656`
Every write is fully decoded/verified with no per-connection or global rate limit; combined with H9 guarantees backlog growth.

### M16. Two independent persistent Ed25519 signing identities per device
`crypto/EncryptionService.kt:485-503` vs `identity/SecureIdentityStateManager.kt:145-195`
Different keys in different pref files; same device presents two signing identities; panic wipe doesn't rotate both. Also: `EncryptionService.sign()` returns an empty signature and `verify()` ignores its inputs entirely (EncryptionService.kt:226-242) — a latent trap.

---

## LOW

- **L1. Metadata leakage:** only NOISE_* frames padded; public MESSAGE/ANNOUNCE/FILE_TRANSFER leak exact sizes; every packet carries ms wall-clock timestamps (`mesh/BLEPacketPaddingPolicy.kt:11-17`, `protocol/BinaryProtocol.kt:42-43,76`).
- **L2. Signed MESSAGE/FILE_TRANSFER replayable after 5-min dedup expiry** — no freshness check (`mesh/SecurityManager.kt:55-73,101`).
- **L3. Silent identity regeneration on key-store corruption** — masks tampering, destroys identity on transient Keystore failure (`crypto/EncryptionService.kt:466-483`, `noise/NoiseEncryptionService.kt:85-119`).
- **L4. Channel KDF salt = channel name; PBKDF2 mislabeled as "Argon2id" in comments; passwords retained in memory; dead plaintext channel-key-sharing packet code** (`noise/NoiseChannelEncryption.kt:148-168,203-243`).
- **L5. Reverse geocoding sends precise GPS to OSM Nominatim (or Google Fused) with app-identifying UA** — rides Tor when on (`geohash/OpenStreetMapGeocoderProvider.kt:21-29`).
- **L6. Persistent account Nostr identity links all DM activity; `#p` filters announce pubkey ownership to relays** — mitigated by randomized gift-wrap timestamps (`nostr/NostrIdentity.kt:108-128`).
- **L7. Predictable subscription IDs (`sub-<millis>-<rand>`) aid per-session correlation** (`nostr/NostrRelayManager.kt:809-811`).
- **L8. NIP-17 validation gaps:** rumor kind not checked to be 14; replay window uses attacker-controlled `created_at`. Sender spoofing inside gift wraps *is* correctly prevented (`nostr/NostrProtocol.kt:77-96`).
- **L9. No FLAG_SECURE anywhere; Recents preview leaks chats on API < 33** (`MainActivity.kt:84-86`).
- **L10. Clipboard copies not marked sensitive** (`ui/ChatUserSheet.kt:87`, `ui/SecurityVerificationSheet.kt:411`).
- **L11. WebView with JS + file access + unescaped geohash interpolation into `evaluateJavascript`** — activity not exported, limited impact (`ui/GeohashPickerActivity.kt:104-148`).
- **L12. `packet.recipientID != SpecialRecipients.BROADCAST` is ByteArray reference comparison — always true** (`mesh/BluetoothPacketBroadcaster.kt:340`).
- **L13. GATT server ignores preparedWrite/offset; force-unwrap NPEs on hot paths degrade gracefully** (`mesh/BluetoothGattServerManager.kt:232-239`, `mesh/PacketProcessor.kt:127`).

---

## Verified positives

- SecureRandom everywhere; no weak RNG.
- Keys in EncryptedSharedPreferences (AES256-GCM Keystore master key); `allowBackup="false"` + backup rules.
- No plaintext message DB; messages in-memory only; message IDs in encrypted prefs.
- PeerID = SHA-256(static key)[:8] with session establishment refusing mismatches; session-generation binding defeats re-handshake downgrade.
- AEAD verify-before-decrypt, constant-time tag check, nonce-wraparound enforcement, rekey limits, zeroization on destroy.
- NIP-17 seal signature verified + `seal.pubkey == rumor.pubkey` enforced; Schnorr nonces use SecureRandom.
- Tor fail-closed (proxy set before bootstrap; clients rebuilt on mode change); no telemetry/analytics SDKs; all traffic wss/https.
- Filename sanitization blocks path traversal; FileProvider per-URI grants; no auto-opening URLs; links require explicit tap and are coerced to https.
- Fragment caps (256/ID, 1 MB/set, 4 MB global, 30 s timeout); dedup caches bounded; connection limits + backoff; presence heartbeats only at city-level precision with jitter.

## Top remediations (priority order)

1. Strip all key/content logging (`SymmetricState`, `MessageHandler.kt:88`); add ProGuard `assumenosideeffects` for `android.util.Log`. *(C1, H3)*
2. Hard-cap `originalSize` in `CompressionUtil.decompress` (~1–2 MB); tighten ratio to ≈1032:1. *(C2)*
3. Fix replay-window shift direction + unit tests; reject low-order points / all-zero DH in `mixDH`. *(H1, M1)*
4. Verify `isValidSignature()` on all incoming Nostr events; pin/sign the relay directory. *(H5, M9)*
5. Cross-sign Ed key with Noise key (proof-of-possession) in announcements. *(H2)*
6. Bound PacketProcessor actor map (validate before actor creation), bound queues, authenticate/rate-limit FRAGMENT & REQUEST_SYNC relay, budget sync responses. *(H9–H11)*
7. Key fragment reassembly by (senderID, fragmentID); rotate the advertised peerID. *(M4, M3)*
8. Gate auto delivery/read receipts behind a privacy setting; warn about location-note precision/permanence. *(H6, H7)*
9. Lock-screen-redact DM notifications; image decode bounds checks off main thread. *(H4, M6)*
10. Bound Nostr `messageQueue` (drain after send) and `GeohashRepository` caches. *(M11, M12)*
