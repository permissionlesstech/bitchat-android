package com.bitchat.android.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Types de charges utiles intégrées dans les messages chiffrés avec Noise.
 * Correspond exactement à l'enum NoisePayloadType sur iOS.
 */
public enum NoisePayloadType {
    PRIVATE_MESSAGE((byte) 0x01),
    READ_RECEIPT((byte) 0x02),
    DELIVERED((byte) 0x03);

    private final byte value;
    private static final Map<Byte, NoisePayloadType> map = new HashMap<>();

    NoisePayloadType(byte value) {
        this.value = value;
    }

    static {
        for (NoisePayloadType type : NoisePayloadType.values()) {
            map.put(type.value, type);
        }
    }

    public byte getValue() {
        return this.value;
    }

    /**
     * Trouve un NoisePayloadType à partir de sa valeur byte.
     * @param value Le byte (sous forme d'int pour gérer la plage 0-255) à rechercher.
     * @return Le NoisePayloadType correspondant, ou null s'il n'est pas trouvé.
     */
    public static NoisePayloadType fromValue(int value) {
        return map.get((byte)value);
    }
}
