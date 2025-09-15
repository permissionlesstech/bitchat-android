package com.bitchat.android.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Énumération des types de messages utilisés dans le protocole binaire.
 * Chaque type de message a une valeur de byte unique qui l'identifie.
 */
public enum BinaryMessageType {
    DELIVERY_ACK((byte) 0x01),
    READ_RECEIPT((byte) 0x02),
    CHANNEL_KEY_VERIFY_REQUEST((byte) 0x03),
    CHANNEL_KEY_VERIFY_RESPONSE((byte) 0x04),
    CHANNEL_PASSWORD_UPDATE((byte) 0x05),
    CHANNEL_METADATA((byte) 0x06),
    VERSION_HELLO((byte) 0x07),
    VERSION_ACK((byte) 0x08),
    NOISE_IDENTITY_ANNOUNCEMENT((byte) 0x09),
    NOISE_MESSAGE((byte) 0x0A);

    private final byte value;
    private static final Map<Byte, BinaryMessageType> map = new HashMap<>();

    BinaryMessageType(byte value) {
        this.value = value;
    }

    static {
        for (BinaryMessageType type : BinaryMessageType.values()) {
            map.put(type.value, type);
        }
    }

    public byte getValue() {
        return value;
    }

    /**
     * Trouve un type de message à partir de sa valeur byte.
     * @param value Le byte à rechercher.
     * @return Le BinaryMessageType correspondant, ou null s'il n'est pas trouvé.
     */
    public static BinaryMessageType fromValue(byte value) {
        return map.get(value);
    }
}
