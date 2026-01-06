# Meshtastic Handoff Implementation Plan

This document outlines the plan to integrate Meshtastic devices as a transport layer for Bitchat. The goal is to use Meshtastic nodes as "amplifiers" or "relays" to broadcast Bitchat packets over LoRa, extending the range of the mesh.

## Overview

The integration will treat the Meshtastic device as a "dumb pipe".
- **Outgoing**: When Bitchat broadcasts a packet, it will also send it to the connected Meshtastic device via BLE. The Meshtastic device will broadcast it over LoRa to other Meshtastic nodes.
- **Incoming**: When the Meshtastic device receives a packet over LoRa (from another Bitchat+Meshtastic node), it will send it to the Android app via BLE. Bitchat will unwrap it and treat it as a normal mesh packet.

We will use the Meshtastic `PortNum` **`PRIVATE_APP` (256)** to encapsulate Bitchat packets, ensuring other Meshtastic traffic doesn't interfere.

## Architecture Changes

### 1. Dependencies
We need to add Protocol Buffers support to handles Meshtastic's BLE protocol.

**`gradle/libs.versions.toml`**:
```toml
[libraries]
protobuf-javalite = { module = "com.google.protobuf:protobuf-javalite", version = "3.25.1" }
```

**`app/build.gradle.kts`**:
```kotlin
plugins {
    id("com.google.protobuf") version "0.9.4"
}

dependencies {
    implementation(libs.protobuf.javalite)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
```

### 2. Protocol Buffers Definitions
The required Protobuf definitions have been downloaded to `app/src/main/proto/meshtastic/`.
- `mesh.proto`: Defines `ToRadio`, `FromRadio`, `MeshPacket`.
- `portnums.proto`: Defines `PortNum`.
- `config.proto` etc.: Dependencies.

### 3. New Component: `MeshtasticConnectionManager`
A new manager class similar to `BluetoothGattClientManager` but specialized for Meshtastic's service UUIDs and protocol.

**Responsibilities**:
- Scan for devices with Service UUID `6ba1b218-15a8-461f-9fa8-5dcae273eafd`.
- Connect and negotiate MTU.
- Subscribe to `FromRadio` characteristic (`2c55e69e-4993-11ed-b878-0242ac120002`).
- Write to `ToRadio` characteristic (`f75c76d2-129e-4dad-a1dd-7866124401e7`).
- Handle Protobuf serialization/deserialization.

**Key Logic**:
```kotlin
class MeshtasticConnectionManager(...) {
    // UUIDs
    val SERVICE_UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    val TO_RADIO_UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    val FROM_RADIO_UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    
    // Send Bitchat packet
    fun sendPacket(packet: BitchatPacket) {
        val payload = packet.toBinaryData()
        
        // Wrap in Meshtastic MeshPacket
        val meshPacket = MeshPacket.newBuilder()
            .setDecoded(Data.newBuilder()
                .setPortnum(PortNum.PRIVATE_APP)
                .setPayload(ByteString.copyFrom(payload))
            )
            .setTo(0xFFFFFFFF) // Broadcast
            .setWantAck(false) 
            .build()
            
        // Wrap in ToRadio
        val toRadio = ToRadio.newBuilder()
            .setPacket(meshPacket)
            .build()
            
        writeToCharacteristic(toRadio.toByteArray())
    }
    
    // Receive callback
    fun onCharacteristicChanged(data: ByteArray) {
        val fromRadio = FromRadio.parseFrom(data)
        if (fromRadio.hasPacket()) {
            val meshPacket = fromRadio.packet
            if (meshPacket.decoded.portnum == PortNum.PRIVATE_APP) {
                 val payload = meshPacket.decoded.payload.toByteArray()
                 val bitchatPacket = BitchatPacket.fromBinaryData(payload)
                 delegate.onPacketReceived(bitchatPacket)
            }
        }
    }
}
```

### 4. Integration into `BluetoothConnectionManager`
Modify `BluetoothConnectionManager` to instantiate and manage `MeshtasticConnectionManager`.

- Add `meshtasticManager` instance.
- Start/stop it in `startServices`/`stopServices`.
- In `broadcastPacket`, call `meshtasticManager.sendPacket(packet)`.
- Implement `MeshtasticDelegate` to route incoming packets to `packetProcessor`.

### 5. Constants
Update `AppConstants.kt` with Meshtastic UUIDs if not hardcoded in the manager.

## Implementation Steps

1.  **Dependencies**: Add Protobuf Gradle plugin and dependency to build files.
2.  **Manager Implementation**: Create `MeshtasticConnectionManager.kt`.
3.  **Integration**: Hook up to `BluetoothConnectionManager` and `BluetoothMeshService`.
4.  **UI (Optional)**: Add a simple setting to "Enable Meshtastic Handoff" or a scanning UI if needed.

## Risks & Mitigations
- **MTU Size**: Meshtastic LoRa packets have size limits (~237 bytes). Bitchat packets can be larger.
    - *Mitigation*: We rely on Bitchat's existing fragmentation (`FragmentManager`). However, `FragmentManager` is designed for BLE MTU. We might need to ensure fragments fit in LoRa packets.
- **Duty Cycle**: LoRa has strict duty cycle limits. Broadcasting too frequently will choke the channel.
    - *Mitigation*: We should only send high-priority or sparse packets, or implement a rate limiter in `MeshtasticConnectionManager` (e.g. only 1 packet per 10s).
- **Bandwidth**: LoRa bandwidth is very low.
    - *Mitigation*: Avoid sending file transfers `FILE_TRANSFER` or large media over Meshtastic. Filter to only `MESSAGE` and `ANNOUNCE` types in `MeshtasticConnectionManager`.
