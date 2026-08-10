---
name: ble-mesh
description: Standards and practices for Bluetooth Low Energy (BLE) scanning, advertising, GATT service management, and peer-to-peer mesh routing.
---

# BLE Mesh Skill

## Core Principles
- Physical device testing is mandatory for BLE discovery, advertising, and connection stability; emulators do NOT accurately model BLE behavior.
- Fast advertising and scanning duty cycles must be balanced with battery consumption and Android OS background restrictions.
- Support fragmentation and packet reassembly over GATT MTU limits gracefully.
- Handle state transitions (connected, disconnecting, re-connecting, peer lost) fail-closed and cleanly clean up BluetoothGatt instances.
