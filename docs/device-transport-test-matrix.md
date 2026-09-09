# Physical transport validation matrix

This is the device-only companion to the deterministic Milestone 4 transport
suite. It validates that the fake adapters, Robolectric behavior, and pure
state machines match Android framework behavior. It is also consumed by the
Milestone 10 release gate.

## Required device set

- Two physical Android devices from different manufacturers.
- At least one Android 13+ device for `NEARBY_WIFI_DEVICES`.
- At least one device that supports Wi-Fi Aware.
- Bluetooth LE central and peripheral support on both devices.
- A build from the exact commit under test installed on both devices.
- Clean app data before the first run; retain a second run for restart tests.

Record device models, API levels, build commit, negotiated MTUs, and timestamps
in the release artifact. Do not record user names, device serials, Bluetooth
addresses, IP addresses, peer IDs, message contents, or other identifying
values.

## BLE discovery and recovery

- [ ] Start both clients and confirm each begins scanning and advertising.
- [ ] Stop and restart the foreground service; confirm exactly one scanner and
  advertiser generation remains active.
- [ ] Toggle Bluetooth off during scanning, then on; confirm scanning,
  advertising, announcements, and peer discovery recover without process
  restart.
- [ ] Disable and re-enable the BLE debug transport; confirm the same service
  instance can recover without duplicate callbacks.
- [ ] Rotate the observed BLE address by restarting advertising; confirm the
  canonical peer remains singular.
- [ ] Trigger a transient scan failure or Android Bluetooth process restart;
  confirm bounded retry and watchdog recovery.
- [ ] Confirm permission denial reports unavailable state without a crash,
  prompt loop, or active radio work.

## GATT setup and teardown

- [ ] Connect in both directions simultaneously and confirm one canonical link
  survives.
- [ ] Record the negotiated MTU and repeat at 23, 247, and 517 where the device
  or test peripheral allows it.
- [ ] Remove the service, characteristic, or CCCD in a test peripheral and
  confirm setup fails closed.
- [ ] Reject notification registration and descriptor writes; confirm the peer
  is never published ready.
- [ ] Disconnect during MTU negotiation, service discovery, subscription,
  client write, and server notification; confirm no stale ready callback.
- [ ] Leave setup incomplete for more than 30 seconds; confirm timeout and
  resource closure.
- [ ] Connect beyond configured client, server, and total limits; confirm
  deterministic oldest-link eviction.

## Packet delivery and fragmentation

- [ ] Send directed and broadcast packets over client and server roles.
- [ ] Saturate each link faster than radio completion callbacks; confirm one
  outstanding operation, bounded backpressure, and no reordered frames.
- [ ] Inject a failed `onCharacteristicWrite` and `onNotificationSent`; confirm
  queued work is discarded and the failed generation is cleaned up.
- [ ] Transfer payloads immediately below and above the fragmentation boundary.
- [ ] Transfer a maximum admitted private-media payload at negotiated MTU 517.
- [ ] At MTU 247 and 23, confirm oversized frames are rejected rather than
  partially sent. Adaptive per-link fragmentation remains tracked as
  `TDB-023`.
- [ ] Disconnect and reconnect halfway through a fragmented transfer; confirm
  incomplete state expires and a fresh transfer can finish.
- [ ] Cancel a queued transfer and stop the service during another; confirm no
  later fragments or progress callbacks.

## Wi-Fi Aware

- [ ] Confirm unsupported hardware and temporarily unavailable radio states are
  distinct.
- [ ] Deny and grant `NEARBY_WIFI_DEVICES`; confirm publish/subscribe work only
  after grant.
- [ ] Start and stop publish and subscribe sessions repeatedly; confirm no
  duplicate discovery callbacks.
- [ ] Authenticate a provisional socket and promote it to the canonical peer.
- [ ] Replace a socket while authentication is in flight; confirm the stale
  socket cannot promote or deliver.
- [ ] Toggle Wi-Fi, location, and airplane mode; confirm rediscovery and bounded
  reconnect after availability returns.
- [ ] Stop the service with active sockets, server sockets, and network
  callbacks; confirm all are closed or unregistered.

## Unified transport and failover

- [ ] Connect the same peer over BLE and Wi-Fi Aware; confirm one peer-list row.
- [ ] Send with both transports active; confirm the preferred transport is used.
- [ ] Drop the preferred transport during a transfer and confirm defined
  failover behavior without duplicate application delivery.
- [ ] Relay between transports and confirm TTL decreases once per hop.
- [ ] Reflect a bridged packet back over the other transport; confirm loop and
  duplicate suppression.
- [ ] Stop the foreground service; confirm scans, advertisements, sessions,
  sockets, operation queues, transfer jobs, and callbacks all terminate.

## Evidence template

| Field | Value |
|---|---|
| Commit | |
| Device/API classes | |
| BLE central/peripheral | Pass / Fail |
| MTU cases | Pass / Fail / Unsupported |
| BLE recovery | Pass / Fail |
| Wi-Fi Aware lifecycle | Pass / Fail / Unsupported |
| Cross-transport failover | Pass / Fail |
| Shutdown leak check | Pass / Fail |
| Bugs filed | |
