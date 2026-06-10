# Wi‑Fi Aware + BLE Mesh Fix Plan

## Goals
- One canonical, merged peer list across BLE + Wi‑Fi Aware.
- Packets relay seamlessly across transports without flapping or missing peers.
- ANNOUNCE and peer mapping remain consistent, regardless of transport.

## TODO Tracker
- [x] Add a centralized mesh peer registry and merge BLE + Wi‑Fi Aware peer lists.
- [x] Route peer list updates through the registry (BLE + Wi‑Fi Aware).
- [x] Remove UI state overwrites of connected peers; use merged list for cleanup/notifications/outbox.
- [x] Bridge relay across transports (BLE → Wi‑Fi Aware and Wi‑Fi Aware → BLE).
- [ ] Verify peer list stability and cross‑transport routing with mixed peers.

## Implementation Notes
- Registry should own the merged list and update `AppStateStore` only from the merged result.
- Relay bridging should occur at `PacketProcessorDelegate.relayPacket(...)` to keep relay logic centralized.
- Avoid new loops/duplication: reuse existing TTL/relay rules and do not mutate the routed packet.
