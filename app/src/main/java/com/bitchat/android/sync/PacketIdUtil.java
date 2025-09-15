package com.bitchat.android.sync;

import com.bitchat.android.protocol.BitchatPacket;
import com.bitchat.android.util.BinaryEncodingUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Utilitaire pour les ID de paquets déterministes à des fins de synchronisation.
 * Utilise SHA-256 sur un sous-ensemble canonique de champs de paquets.
 */
public final class PacketIdUtil {

    private PacketIdUtil() {}

    public static byte[] computeIdBytes(BitchatPacket packet) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(packet.getType());
            md.update(packet.getSenderID());

            long ts = packet.getTimestamp();
            for (int i = 7; i >= 0; i--) {
                md.update((byte) ((ts >>> (i * 8)) & 0xFF));
            }

            md.update(packet.getPayload());
            byte[] digest = md.digest();
            return Arrays.copyOf(digest, 16); // ID de 128 bits
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String computeIdHex(BitchatPacket packet) {
        byte[] bytes = computeIdBytes(packet);
        return BinaryEncodingUtils.hexEncodedString(bytes);
    }
}
