package com.bitchat.android.nostr;

import android.util.Base64;
import com.bitchat.android.protocol.BitchatPacket;

/**
 * NostrEmbeddedBitchat
 * - Utilities for embedding Bitchat packets inside Nostr events
 */
public final class NostrEmbeddedBitChat {

    private NostrEmbeddedBitChat() {
        // Private constructor to prevent instantiation
    }

    /**
     * Encode a BitchatPacket into a base64url string suitable for Nostr event content
     */
    public static String encodeBitchatPacket(BitchatPacket packet) {
        byte[] binaryData = packet.toBinaryData();
        String base64 = Base64.encodeToString(binaryData, Base64.NO_WRAP);

        // Make it URL-safe
        return "bitchat1:" + base64.replace('+', '-').replace('/', '_').replaceAll("=", "");
    }

    /**
     * Decode a BitchatPacket from a Nostr event content string
     */
    public static BitchatPacket decodeBitchatPacket(String content) {
        if (!content.startsWith("bitchat1:")) {
            return null;
        }

        String base64Url = content.substring("bitchat1:".length());
        String base64 = base64Url.replace('-', '+').replace('_', '/');

        // Add padding
        String paddedBase64;
        switch (base64.length() % 4) {
            case 2:
                paddedBase64 = base64 + "==";
                break;
            case 3:
                paddedBase64 = base64 + "=";
                break;
            default:
                paddedBase64 = base64;
                break;
        }

        try {
            byte[] binaryData = Base64.decode(paddedBase64, Base64.DEFAULT);
            return BitchatPacket.fromBinaryData(binaryData);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
