# Device Monitoring Manager — Design and Integration

This change introduces a lean DeviceMonitoringManager to strictly manage BLE device connections while keeping the existing code structure intact.

## Goals

- Maintain a blocklist of device MAC addresses to deny incoming/outgoing connections.
- Drop and block connections that never ANNOUNCE within 15 seconds of establishment.
- Drop and block connections that go silent (no packets) for over 60 seconds.
- Block devices that experience 5 error disconnects within a 5-minute window.
- Auto-unblock devices after 15 minutes.

## Implementation Overview

File: `app/src/main/java/com/bitchat/android/mesh/DeviceMonitoringManager.kt`

- Thread-safe maps with coroutine-based timers.
- Minimal surface area: a few clearly named entry points to hook into existing flows.
- Callbacks to perform disconnects without coupling to GATT APIs.

Key logic:
- `isBlocked(address)`: check if a MAC is blocked (auto-clears on expiry).
- `block(address, reason)`: add MAC to blocklist (15m), disconnect via callback, auto-unblock later.
- `onConnectionEstablished(address)`: start 15s “first ANNOUNCE” timer and a 60s inactivity timer.
- `onAnnounceReceived(address)`: cancel the 15s ANNOUNCE timer for that device.
- `onAnyPacketReceived(address)`: refresh 60s inactivity timer.
- `onDeviceDisconnected(address, status)`: track error disconnects and block on 5 within 5 minutes.

Timers:
- ANNOUNCE timer: 15 seconds from connection establishment.
- Inactivity timer: resets on any packet; fires after 60 seconds of silence.
- Blocklist TTL: 15 minutes per device (auto-unblock job per entry).

## Wiring Points (Minimal Changes)

1) Connection Manager
- File: `BluetoothConnectionManager.kt`
- Added a `DeviceMonitoringManager` instance and provided a `disconnectCallback` that:
  - disconnects client GATT connections via `BluetoothConnectionTracker`.
  - cancels server connections via `BluetoothGattServer.cancelConnection`.
- Exposed `noteAnnounceReceived(address)` as a small helper for higher layers.
- Updated `componentDelegate.onPacketReceived` to notify per-device activity to the monitor.

2) GATT Client
- File: `BluetoothGattClientManager.kt`
- Constructor now receives `deviceMonitor`.
- Before attempting any outgoing connection (from scan or direct connect), deny if blocked.
- On connection setup complete (after CCCD enable), call `deviceMonitor.onConnectionEstablished(addr)`.
- On incoming packet (`onCharacteristicChanged`), call `deviceMonitor.onAnyPacketReceived(addr)`.
- On disconnect, call `deviceMonitor.onDeviceDisconnected(addr, status)` to track error bursts.

3) GATT Server
- File: `BluetoothGattServerManager.kt`
- Constructor now receives `deviceMonitor`.
- On incoming connection, immediately deny (cancelConnection) if blocked, before tracking it.
- On connection setup complete (descriptor enable) and also after initial connect, start monitoring via `onConnectionEstablished(addr)`.
- On packet write, call `deviceMonitor.onAnyPacketReceived(addr)`.
- On disconnect, call `deviceMonitor.onDeviceDisconnected(addr, status)`.

4) ANNOUNCE Binding
- File: `BluetoothMeshService.kt` (in the ANNOUNCE handler where we first map device → peer)
- After mapping a device address to a peer on first verified ANNOUNCE, call `connectionManager.noteAnnounceReceived(address)` to cancel the 15s timer for that device.

## Behavior Summary

- Blocked devices:
  - Outgoing: client will not initiate connections.
  - Incoming: server cancels the connection immediately.
  - Existing connection: monitor disconnects instantly and blocks for 15 minutes.

- No ANNOUNCE within 15s of connection:
  - Connection is dropped and device is blocked for 15 minutes.

- No packets for >60s:
  - Connection is dropped and device is blocked for 15 minutes.

- >=5 error disconnects within 5 minutes:
  - Device is blocked for 15 minutes.

- Auto-unblock:
  - Every block entry automatically expires after 15 minutes.

## Debug Logging

- The manager emits chat-visible debug messages through `DebugSettingsManager` (SystemMessage), e.g.:
  - Blocking decisions and reasons
  - Auto-unblock events
  - ANNOUNCE wait start/cancel
  - Inactivity timer set and inactivity-triggered blocks
  - Burst error disconnect threshold reached
- Additional enforcement logs are added in GATT client/server when a blocked device is denied.
- Logs appear in the chat when verbose logging is enabled in Debug settings.

## Panic Triple-Tap

- Triple-tapping the title now also clears the device blocklist and all device tracking:
  - Calls `BluetoothMeshService.clearAllInternalData()` which triggers `BluetoothConnectionManager.clearDeviceMonitoringAndTracking()`.
  - This disconnects active connections, clears the monitor’s blocklist and timers, and resets the `BluetoothConnectionTracker` state.

## Notes and Rationale

- The monitoring manager is intentionally decoupled from GATT specifics via a disconnect callback. This keeps responsibilities separate and avoids plumbing GATT instances through unrelated classes.
- Packet activity is captured in both client and server data paths as early as possible to ensure the inactivity timer is accurate even before higher-level processing.
- The “first ANNOUNCE” check uses the same mapping event that sets `addressPeerMap` to avoid false positives on unverified announces.

## Touched Files

- Added: `mesh/DeviceMonitoringManager.kt`
- Updated: `mesh/BluetoothConnectionManager.kt`
- Updated: `mesh/BluetoothGattClientManager.kt`
- Updated: `mesh/BluetoothGattServerManager.kt`
- Updated: `mesh/BluetoothMeshService.kt`

These changes are small, local, and respect existing structure without broad refactors.
