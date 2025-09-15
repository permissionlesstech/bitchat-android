package com.bitchat.android.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Énumération des types de messages, identique à la version iOS.
 * Inclut la prise en charge du protocole Noise.
 */
public enum MessageType {
    ANNOUNCE((byte) 0x01),
    MESSAGE((byte) 0x02),      // Tous les messages utilisateur (privés et diffusion)
    LEAVE((byte) 0x03),
    NOISE_HANDSHAKE((byte) 0x10),
    NOISE_ENCRYPTED((byte) 0x11),
    FRAGMENT((byte) 0x20),
    REQUEST_SYNC((byte) 0x21);

    private final byte value;
    private static final Map<Byte, MessageType> map = new HashMap<>();

    MessageType(byte value) { this.value = value; }

    static {
        for (MessageType type : MessageType.values()) {
            map.put(type.value, type);
        }
    }

    public byte getValue() {
        return value;
    }

    /**
     * Trouve un MessageType à partir de sa valeur byte.
     * @param value Le byte (en tant qu'int pour gérer la plage 0-255) à rechercher.
     * @return Le MessageType correspondant, ou null s'il n'est pas trouvé.
     */
    public static MessageType fromValue(int value) {
        return map.get((byte) value);
    }
}
