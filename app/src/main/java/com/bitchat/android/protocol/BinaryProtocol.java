package com.bitchat.android.protocol;

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Implémentation du protocole binaire.
 * Gère l'encodage et le décodage des BitchatPackets, y compris la compression et le padding.
 * Le format est identique à celui de la version iOS.
 */
public final class BinaryProtocol {

    private BinaryProtocol() {} // Classe utilitaire non instanciable

    private static final int HEADER_SIZE = 13;
    private static final int SENDER_ID_SIZE = 8;
    private static final int RECIPIENT_ID_SIZE = 8;
    private static final int SIGNATURE_SIZE = 64;

    public static final class Flags {
        public static final int HAS_RECIPIENT = 0x01;
        public static final int HAS_SIGNATURE = 0x02;
        public static final int IS_COMPRESSED = 0x04;
    }

    public static byte[] encode(BitchatPacket packet) {
        try {
            byte[] payload = packet.getPayload();
            Integer originalPayloadSize = null;
            boolean isCompressed = false;

            if (CompressionUtil.shouldCompress(payload)) {
                byte[] compressedPayload = CompressionUtil.compress(payload);
                if (compressedPayload != null) {
                    originalPayloadSize = payload.length;
                    payload = compressedPayload;
                    isCompressed = true;
                }
            }

            ByteBuffer buffer = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);

            buffer.put(packet.getVersion());
            buffer.put(packet.getType());
            buffer.put(packet.getTtl());
            buffer.putLong(packet.getTimestamp());

            int flags = 0;
            if (packet.getRecipientID() != null) {
                flags |= Flags.HAS_RECIPIENT;
            }
            if (packet.getSignature() != null) {
                flags |= Flags.HAS_SIGNATURE;
            }
            if (isCompressed) {
                flags |= Flags.IS_COMPRESSED;
            }
            buffer.put((byte) flags);

            int payloadDataSize = payload.length + (isCompressed ? 2 : 0);
            buffer.putShort((short) payloadDataSize);

            byte[] senderBytes = Arrays.copyOf(packet.getSenderID(), SENDER_ID_SIZE);
            buffer.put(senderBytes);

            if (packet.getRecipientID() != null) {
                byte[] recipientBytes = Arrays.copyOf(packet.getRecipientID(), RECIPIENT_ID_SIZE);
                buffer.put(recipientBytes);
            }

            if (isCompressed && originalPayloadSize != null) {
                buffer.putShort(originalPayloadSize.shortValue());
            }
            buffer.put(payload);

            if (packet.getSignature() != null) {
                byte[] signatureBytes = Arrays.copyOf(packet.getSignature(), SIGNATURE_SIZE);
                buffer.put(signatureBytes);
            }

            byte[] result = new byte[buffer.position()];
            buffer.rewind();
            buffer.get(result);

            int optimalSize = MessagePadding.optimalBlockSize(result.length);
            return MessagePadding.pad(result, optimalSize);

        } catch (Exception e) {
            Log.e("BinaryProtocol", "Erreur lors de l'encodage du paquet type " + packet.getType() + ": " + e.getMessage());
            return null;
        }
    }

    public static BitchatPacket decode(byte[] data) {
        BitchatPacket packet = decodeCore(data);
        if (packet != null) {
            return packet;
        }

        byte[] unpadded = MessagePadding.unpad(data);
        if (Arrays.equals(unpadded, data)) return null; // Rien n'a été retiré, échec déjà constaté

        return decodeCore(unpadded);
    }

    private static BitchatPacket decodeCore(byte[] raw) {
        try {
            if (raw.length < HEADER_SIZE + SENDER_ID_SIZE) return null;

            ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

            byte version = buffer.get();
            if (version != 1) return null;

            byte type = buffer.get();
            byte ttl = buffer.get();
            long timestamp = buffer.getLong();
            int flags = buffer.get() & 0xFF;

            boolean hasRecipient = (flags & Flags.HAS_RECIPIENT) != 0;
            boolean hasSignature = (flags & Flags.HAS_SIGNATURE) != 0;
            boolean isCompressed = (flags & Flags.IS_COMPRESSED) != 0;

            int payloadLength = buffer.getShort() & 0xFFFF;

            int expectedSize = HEADER_SIZE + SENDER_ID_SIZE + payloadLength;
            if (hasRecipient) expectedSize += RECIPIENT_ID_SIZE;
            if (hasSignature) expectedSize += SIGNATURE_SIZE;
            if (raw.length < expectedSize) return null;

            byte[] senderID = new byte[SENDER_ID_SIZE];
            buffer.get(senderID);

            byte[] recipientID = null;
            if (hasRecipient) {
                recipientID = new byte[RECIPIENT_ID_SIZE];
                buffer.get(recipientID);
            }

            byte[] payload;
            if (isCompressed) {
                if (payloadLength < 2) return null;
                int originalSize = buffer.getShort() & 0xFFFF;
                byte[] compressedPayload = new byte[payloadLength - 2];
                buffer.get(compressedPayload);
                payload = CompressionUtil.decompress(compressedPayload, originalSize);
                if (payload == null) return null;
            } else {
                payload = new byte[payloadLength];
                buffer.get(payload);
            }

            byte[] signature = null;
            if (hasSignature) {
                signature = new byte[SIGNATURE_SIZE];
                buffer.get(signature);
            }

            return new BitchatPacket(version, type, senderID, recipientID, timestamp, payload, signature, ttl);

        } catch (Exception e) {
            Log.e("BinaryProtocol", "Erreur lors du décodage du paquet: " + e.getMessage());
            return null;
        }
    }
}
