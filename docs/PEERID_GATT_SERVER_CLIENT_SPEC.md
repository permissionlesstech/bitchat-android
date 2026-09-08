# Bitchat Mesh: Direct Client Identity Signaling & Connection Deduplication Spec

## 1. Overview
This specification details the mechanism for Bitchat nodes to signal their persistent **Peer Identity** over Bluetooth Low Energy (BLE) to prevent duplicate connections. 

In a privacy-preserving mesh network, devices frequently rotate their MAC addresses. This creates a problem where two nodes might establish multiple redundant connections to each other (thinking they are new devices), wasting limited connection slots and battery power.

This spec defines a dual-strategy approach:
1.  **Passive Signaling:** Inclusion of Peer ID in BLE Scan Responses (Server-side).
2.  **Active Signaling:** A dedicated GATT "Identity Characteristic" for clients to write their ID immediately upon connection.

**Target Audience:** Android & iOS Engineering Teams.

---

## 2. Protocol Constants

### 2.1 UUIDs
All Bitchat BLE operations use the following 128-bit UUIDs.

| Name | UUID | Usage |
|------|------|-------|
| **Service UUID** | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C` | The main Bitchat Mesh Service. |
| **Data Characteristic** | `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D` | Main channel for `BitchatPacket` transfer (Notify/Write). |
| **Identity Characteristic** | `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5E` | **(NEW)** For clients to signal identity (Write/WriteNr). |

### 2.2 Data Formats
*   **Peer ID Truncation:** For BLE signaling, the full 64-char hex Peer ID is truncated to the **first 8 bytes** (16 hex characters).
*   **Byte Order:** Big-Endian (Network Byte Order).

---

## 3. Passive Signaling (Advertisements)
*Already implemented on Android and iOS.*

When acting as a Peripheral (Server), the node **MUST** include its truncated Peer ID in the **Scan Response** packet.

*   **Data Type:** Service Data (0x16)
*   **UUID:** `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C` (16-bit or 128-bit representation depending on space)
*   **Payload:** First 8 bytes of Peer ID.

**Behavior:**
*   Scanners (Clients) can map the ephemeral MAC address to this Peer ID *before* connecting.
*   If the Scanner is already connected to this Peer ID (via a different MAC), it **MUST NOT** initiate a new connection.

---

## 4. Active Signaling (Identity Characteristic)
*New implementation requirement.*

When acting as a Central (Client), the node cannot advertise its ID to the Server if the Server is not scanning. Therefore, the Client **MUST** write its identity to the Server immediately after connection establishment.

### 4.1 Client Implementation Spec
1.  **Connect** to the Peripheral.
2.  **Discover Services**.
3.  Check for the existence of `Identity Characteristic` (`...C5E`).
4.  **Action:** If present, write the local truncated Peer ID (8 bytes) to this characteristic.
    *   **Write Type:** `WRITE_TYPE_NO_RESPONSE` (preferred for speed) or `WRITE_TYPE_DEFAULT`.
5.  **Timing:** This MUST be the **first** action after service discovery, before enabling notifications on the Data Characteristic.

#### Android Reference (Client)
```kotlin
// In onServicesDiscovered()
val identityChar = service.getCharacteristic(IDENTITY_CHARACTERISTIC_UUID)
if (identityChar != null) {
    // 1. Prepare 8-byte ID
    val idBytes = myPeerID.chunked(2).map { it.toInt(16).toByte() }
        .toByteArray().take(8).toByteArray()
    
    // 2. Write to characteristic
    identityChar.value = idBytes
    identityChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    gatt.writeCharacteristic(identityChar)
}
```

### 4.2 Server Implementation Spec
1.  **Add Characteristic:** The GATT Server MUST include the `Identity Characteristic` in its service definition.
    *   **Properties:** `Write` | `Write No Response`
    *   **Permissions:** `Write`
2.  **Handle Write Request:** When a write request is received on this UUID:
    *   **Extract** the 8-byte Peer ID.
    *   **Bind** the identity to the current connection handle (MAC address).
    *   **Execute Deduplication Policy** (see Section 5).

#### Android Reference (Server)
```kotlin
// In onCharacteristicWriteRequest()
if (characteristic.uuid == IDENTITY_CHARACTERISTIC_UUID) {
    val peerID = value.joinToString("") { "%02x".format(it) }
    
    // 1. Update internal mapping (MAC -> PeerID)
    connectionTracker.setDevicePeerID(device.address, peerID)
    
    // 2. Deduplication Check
    val duplicate = connectionTracker.getConnectedDevices().values.firstOrNull { 
        it.peerID == peerID && it.device.address != device.address 
    }
    
    if (duplicate != null) {
        // Peer is already connected via a different MAC
        Log.w(TAG, "Deduplication: Rejecting new connection from $peerID")
        
        // Respond success to complete the transaction gracefully
        if (responseNeeded) {
             gattServer.sendResponse(device, requestId, GATT_SUCCESS, 0, null)
        }
        
        // Terminate the NEW connection
        gattServer.cancelConnection(device)
        return
    }
}
```

---

## 5. Deduplication Policy

The goal is to allow only **one** logical connection between any two peers, regardless of direction (Client/Server) or MAC address rotation.

### 5.1 Rules
1.  **Peer ID Primacy:** The persistent Peer ID is the source of truth, not the MAC address.
2.  **First-Come-First-Served:** The *existing* stable connection is preferred. A new incoming connection attempting to claim the same Peer ID will be rejected.
3.  **Cross-Role Deduplication:**
    *   If Node A is connected to Node B (A=Client, B=Server).
    *   And Node B tries to connect to Node A (B=Client, A=Server).
    *   If both sides identify correctly, one link is redundant.
    *   *Current Policy:* Bitchat currently treats dual-direction links as acceptable but suboptimal. However, strict deduplication via **Active Signaling** will treat the second link as a duplicate if the application layer maps them to the same ID.
    *   **Strict Mode (Recommended):** If `PeerID_X` is connected as a client, do NOT accept `PeerID_X` as a server.

### 5.2 Conflict Resolution Flow
1.  **New Connection (Incoming):** Device `AA:BB:CC` connects.
2.  **Identity Signal:** Device writes ID `12345678`.
3.  **Lookup:** Server checks list of *other* active connections.
    *   Is `12345678` connected via `DD:EE:FF`?
4.  **Decision:**
    *   **Yes:** Disconnect `AA:BB:CC` immediately. (Keep the old, stable link).
    *   **No:** Accept `AA:BB:CC` and update the map.

---

## 6. iOS Implementation Notes

### Swift Implementation Guidance
The iOS `CoreBluetooth` implementation should mirror the logic above.

**CBPeripheralManagerDelegate:**
In `didReceiveWrite requests`:
```swift
func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    for request in requests {
        if request.characteristic.uuid == CBUUID(string: "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5E") {
            let peerData = request.value
            // 1. Parse PeerID
            // 2. Check Connection Manager for existing PeerID
            // 3. If duplicate, cancel connection:
            //    peripheralManager.respond(to: request, withResult: .success)
            //    // Wait briefly or disconnect immediately?
            //    // CBCentralManager usually handles disconnects, but as a Peripheral, 
            //    // you simply stop processing. To force disconnect, you might need 
            //    // to let the Central handle the rejection or implement app-layer timeout.
            //    // NOTE: iOS Peripheral cannot force-disconnect a Central easily.
            //    // Alternative: Send an app-layer "Disconnect" packet or error response.
        }
    }
}
```

**Note on iOS Peripheral Disconnects:**
Unlike Android, iOS `CBPeripheralManager` does not have a direct `cancelConnection` for a specific central. 
*   **Strategy:** If a duplicate is detected, the iOS Peripheral should respond with a custom GATT Error (if possible) or simply ignore further data requests, causing the Central (if Bitchat) to timeout. 
*   **Preferred Strategy:** The Android/iOS **Client** (Central) is the one that typically manages the connection lifecycle. If the Server detects a duplicate, it could write a specific "Go Away" code to the characteristic or simply refuse to process further packets. 
*   **Protocol Refinement:** If the Server responds with `GATT_SUCCESS`, the Client assumes it's accepted. If the Server responds with an error (e.g., `CBATTError.insufficientResources` or a custom code), the Client should disconnect itself.

**Refined Server Logic (Cross-Platform):**
If the Server detects a duplicate:
1.  Respond to the Write Request with `GATT_SUCCESS` (to satisfy the protocol).
2.  Internally flag this `CBCentral` as "Rejected".
3.  Ignore any subsequent data writes from this Central.
4.  (Optional) If the Central subscribes to the Data Characteristic, send a single "Disconnect Command" packet and then stop notifying.

### Android Compliance
The Android implementation provided in the Reference section uses `gattServer.cancelConnection(device)`, which effectively terminates the link. iOS devices acting as Clients will see this as a disconnection and should handle it gracefully (auto-retry logic should respect the fact that it might be a purposeful disconnect, but standard backoff applies).
