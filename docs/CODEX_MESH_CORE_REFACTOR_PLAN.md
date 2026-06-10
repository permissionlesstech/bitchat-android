# Mesh Core Refactor Plan

Goal: unify BLE + Wi-Fi Aware mesh services behind a shared core to reduce duplication, improve readability, and make auditing easier without changing runtime behavior.

## Phase 0: Inventory + Guardrails
- [x] Capture current public API surface used by UI/services (identify required methods/properties).
- [x] Identify transport-specific responsibilities vs shared mesh-core logic.
- [x] Decide how to share GossipSyncManager (keep existing holder behavior unless explicitly improved).

## Phase 1: Shared Types + Utilities
- [x] Introduce `MeshDelegate` in `com.bitchat.android.mesh` and make both BLE/Wi‑Fi delegates type-alias it.
- [x] Add `MeshTransport` interface to abstract local transport send/unicast and optional info hooks.
- [x] Extract `MeshPacketUtils` (hex id parsing, sha256 helper) used by both services.
- [x] Add unit tests for `MeshPacketUtils` (hex parsing + sha256 stability).

## Phase 2: MeshCore (Shared Coordinator)
- [x] Create `MeshCore` with shared managers (Peer/Fragment/Security/StoreForward/Message/Packet).
- [x] Move shared delegate wiring into `MeshCore` with hooks for transport-specific behavior:
  - onMessageReceived (BLE notification / AppStateStore)
  - onPeerIdBinding (BLE favorites mapping)
  - onAnnounceProcessed (BLE first-announce address map)
- [x] Centralize shared packet signing + announce/leave creation.
- [x] Centralize message/file/read-receipt senders (public + private).
- [x] Expose helper methods for peer info, session state, debug info, and panic-mode clearing.

## Phase 3: BluetoothMeshService Integration
- [x] Replace duplicated core fields with a single `MeshCore` instance.
- [x] Wire `BluetoothConnectionManager` callbacks into `MeshCore` packet ingestion.
- [x] Move periodic announce + gossip start/stop into `MeshCore` usage.
- [x] Preserve BLE-only behaviors (connection manager control, background notifications).
- [x] Keep `MeshServiceHolder` semantics intact (reusability + shared gossip manager).

## Phase 4: WifiAwareMeshService Integration
- [x] Replace duplicated core fields with a `MeshCore` instance.
- [x] Route Wi‑Fi socket RX into `MeshCore` packet ingestion.
- [x] Keep Wi‑Fi aware discovery/connection logic transport-specific.
- [x] Ensure gossip sync usage matches current shared behavior.

## Phase 5: Cleanup + Consistency
- [x] Remove legacy duplicate helpers + delegates from both services.
- [x] Ensure debug status output stays consistent (core + transport sections).
- [x] Confirm behavior parity for announce/leave + handshake flows.

## Phase 6: Tests + Verification
- [x] Run unit tests: `./gradlew test` (or targeted if full suite is too slow).
- [x] If new tests fail, iterate until green.

## Status Tracking
- [x] Phase 0 complete
- [x] Phase 1 complete
- [x] Phase 2 complete
- [x] Phase 3 complete
- [x] Phase 4 complete
- [x] Phase 5 complete
- [x] Phase 6 complete

## Post-Refactor Review Findings (2025-02-XX)

Findings (confirmed issues to fix):
- Shared `GossipSyncManager` is stopped when Wi‑Fi Aware stops, even if BLE is still active.
- Shared `GossipSyncManager` unicast (`sendPacketToPeer`) only reaches BLE peers; Wi‑Fi Aware peers miss initial sync.
- Read receipts are sent only via BLE in UI paths; Wi‑Fi Aware-only sessions never send receipts.

TODO (fix plan):
- [x] MeshCore: guard `gossipSyncManager.start/stop` so only the owner transport controls lifecycle.
- [x] TransportBridgeService: add unicast forwarding across transports; update BLE/Wi‑Fi transport layers to support it.
- [x] MeshCore: when owning gossip manager, forward unicast sync packets to other transports via bridge.
- [x] UI: route read receipts via BLE if session established, else via Wi‑Fi Aware when available.
- [x] Tests: add unit tests for `TransportBridgeService` unicast forwarding and run them.
