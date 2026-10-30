package com.bitchat.android.protocol

/**
 * Receive-side ceilings on how large a packet's payload may be, per type.
 *
 * A compressed frame declares its original size, and raw DEFLATE lets ~1 KB on
 * air stand for ~1 MB decoded; fragment reassembly lets a large uncompressed
 * frame arrive as a stream of small, compressed fragments. Accepted packets are
 * then held at their decoded size, relayed, and re-served by gossip sync, so a
 * single frame-wide ceiling turns airtime into memory on every node.
 * [BinaryProtocol] enforces these caps on every decoded payload, compressed or
 * not. Only the types that carry media keep the framed-file ceiling; every other
 * type is capped at no less than twice the largest payload any shipping iOS or
 * Android encoder produces for it. Receive-side only: senders are unchanged.
 *
 * Values match iOS `PacketPayloadLimits` (bitchat#1719), except that media types
 * keep Android's own [com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH].
 *
 * | Type                           | Largest legitimate payload                           | Cap                |
 * |--------------------------------|------------------------------------------------------|--------------------|
 * | FILE_TRANSFER, NOISE_ENCRYPTED | file + name/MIME TLVs (+ Noise framing for private)  | MAX_PAYLOAD_LENGTH |
 * | MESSAGE, group message         | 65,535 B: public messages are v1 frames              | 128 KiB            |
 * | courier envelope, Nostr carrier| 16 KiB ciphertext/event + TLVs (iOS only)            | 64 KiB             |
 * | REQUEST_SYNC                   | ~2.1 KB: 1,024 B GCS filter + fragment-ID filter     | 8 KiB              |
 * | FRAGMENT                       | <= 513 B: 13 B header + chunk                        | 1,280 B            |
 * | ANNOUNCE, LEAVE, NOISE_HANDSHAKE, VOICE_FRAME, board post, prekey bundle, ping, pong | <= ~750 B | 4 KiB |
 * | unknown / future types         | a v1 frame (65,535 B) stays relayable                | 128 KiB            |
 *
 * A future type that needs more than a v1 frame must be added here before it
 * ships, or current receivers will drop it at decode.
 */
internal object PacketPayloadLimits {
    /**
     * Raw DEFLATE's hard expansion ceiling: every 258-byte match costs at least a
     * 1-bit length code plus a 1-bit distance code. A declared original size
     * beyond this multiple of the compressed bytes cannot be produced by any
     * stream, so it is rejected before the output buffer is allocated.
     */
    const val MAX_DEFLATE_RATIO = 1032

    private const val FRAGMENT_BYTES = 1_280
    private const val CONTROL_BYTES = 4 * 1024
    private const val REQUEST_SYNC_BYTES = 8 * 1024
    private const val ENVELOPE_BYTES = 64 * 1024
    private const val TWICE_V1_FRAME_BYTES = 128 * 1024

    // iOS-only types this client does not decode yet; capped the same way iOS caps them
    // so they stay relayable without becoming a memory amplifier.
    private const val COURIER_ENVELOPE: UByte = 0x04u
    private const val BOARD_POST: UByte = 0x23u
    private const val PREKEY_BUNDLE: UByte = 0x24u
    private const val GROUP_MESSAGE: UByte = 0x25u
    private const val PING: UByte = 0x26u
    private const val PONG: UByte = 0x27u
    private const val NOSTR_CARRIER: UByte = 0x28u

    /** Largest decoded payload accepted for a packet of [type], whether it arrived compressed or not. */
    fun maxPayloadBytes(type: UByte): Int {
        when (MessageType.fromValue(type)) {
            MessageType.FILE_TRANSFER, MessageType.NOISE_ENCRYPTED ->
                return com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH
            MessageType.MESSAGE -> return TWICE_V1_FRAME_BYTES
            MessageType.REQUEST_SYNC -> return REQUEST_SYNC_BYTES
            MessageType.FRAGMENT -> return FRAGMENT_BYTES
            MessageType.ANNOUNCE, MessageType.LEAVE, MessageType.NOISE_HANDSHAKE,
            MessageType.VOICE_FRAME -> return CONTROL_BYTES
            null -> Unit
        }
        return when (type) {
            GROUP_MESSAGE -> TWICE_V1_FRAME_BYTES
            COURIER_ENVELOPE, NOSTR_CARRIER -> ENVELOPE_BYTES
            BOARD_POST, PREKEY_BUNDLE, PING, PONG -> CONTROL_BYTES
            else -> TWICE_V1_FRAME_BYTES
        }
    }
}
