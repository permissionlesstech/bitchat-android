# Direct Message TLV: 1-byte to 2-byte (Backward-Compatible)

This change enables sending and receiving Cashu ecash tokens over Direct Messages by introducing a 2-byte TLV format for DMs while keeping backward compatibility.

**Cashu Integration**: This change enables [bitpoints.me](https://github.com/bitpoints-cashu/bitpoints.me), a Cashu ecash wallet that integrates bitchat protocol for offline peer-to-peer Bitcoin payments over Bluetooth mesh.

Key points:
- Receive path accepts both 1-byte and 2-byte TLV without negotiation (adaptive parser).
- Send path uses 2-byte TLV only when the peer advertises feature bit `DM_TLV_2BYTE` in announcements.
- Broadcast path unchanged.

Formats:
- Legacy (1B): [type:u8][length:u8][value]
- New (2B): [type:u16][length:u16][value]

Capability Signaling:
- Announcements include optional `FEATURES` TLV (type 0x04) carrying a big-endian bitmask.
- Bit 0 (`DM_TLV_2BYTE`) means peer supports 2-byte TLV for DMs.

Security:
- Noise/AES-GCM unchanged; strict bounds checks on TLV length.

Interop:
- Older peers ignore unknown `FEATURES` TLV and continue receiving DMs in legacy format.
