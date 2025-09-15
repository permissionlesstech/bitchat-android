package com.bitchat.android.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

/**
 * Lit les données d'un tableau de bytes de manière séquentielle.
 * Maintient un offset interne pour suivre la position de lecture actuelle.
 */
public class BinaryDataReader {
    private final byte[] data;
    private int offset = 0;

    public BinaryDataReader(byte[] data) {
        this.data = data;
    }

    /**
     * Lit un octet non signé (UByte) à la position actuelle.
     * @return Une valeur int représentant l'octet non signé, ou null si la lecture est impossible.
     */
    public Integer readUInt8() {
        if (offset >= data.length) return null;
        int value = data[offset] & 0xFF;
        offset += 1;
        return value;
    }

    /**
     * Lit un entier court non signé de 16 bits (UShort) en big-endian.
     * @return Une valeur int représentant l'entier non signé, ou null si la lecture est impossible.
     */
    public Integer readUInt16() {
        if (offset + 2 > data.length) return null;
        int value = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        offset += 2;
        return value;
    }

    /**
     * Lit un entier non signé de 32 bits (UInt) en big-endian.
     * @return Une valeur long représentant l'entier non signé, ou null si la lecture est impossible.
     */
    public Long readUInt32() {
        if (offset + 4 > data.length) return null;
        long value = ((long)(data[offset] & 0xFF) << 24) |
                     ((long)(data[offset + 1] & 0xFF) << 16) |
                     ((long)(data[offset + 2] & 0xFF) << 8) |
                     ((long)(data[offset + 3] & 0xFF));
        offset += 4;
        return value;
    }

    /**
     * Lit un entier non signé de 64 bits (ULong) en big-endian.
     * @return Une valeur long, ou null si la lecture est impossible. Attention: peut déborder si le bit de signe est utilisé.
     */
    public Long readUInt64() {
        if (offset + 8 > data.length) return null;
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        offset += 8;
        return value;
    }

    /**
     * Lit une chaîne de caractères préfixée par sa longueur.
     * @param maxLength La longueur maximale attendue pour les données de la chaîne.
     * @return La chaîne de caractères lue, ou null si la lecture est impossible.
     */
    public String readString(int maxLength) {
        Integer length;
        if (maxLength <= 255) {
            length = readUInt8();
        } else {
            length = readUInt16();
        }
        if (length == null) return null;

        if (offset + length > data.length) return null;

        String result = new String(data, offset, length, StandardCharsets.UTF_8);
        offset += length;
        return result;
    }

    /**
     * Lit un tableau de bytes préfixé par sa longueur.
     * @param maxLength La longueur maximale attendue pour les données.
     * @return Le tableau de bytes lu, ou null si la lecture est impossible.
     */
    public byte[] readData(int maxLength) {
        Integer length;
        if (maxLength <= 255) {
            length = readUInt8();
        } else {
            length = readUInt16();
        }
        if (length == null) return null;

        if (offset + length > data.length) return null;

        byte[] result = Arrays.copyOfRange(data, offset, offset + length);
        offset += length;
        return result;
    }

    /**
     * Lit une date (timestamp 64-bit).
     * @return L'objet Date, ou null si la lecture est impossible.
     */
    public Date readDate() {
        Long timestamp = readUInt64();
        if (timestamp == null) return null;
        return new Date(timestamp);
    }

    /**
     * Lit un UUID de 16 bytes.
     * @return La représentation en chaîne de l'UUID, ou null si la lecture est impossible.
     */
    public String readUUID() {
        byte[] uuidData = readFixedBytes(16);
        if (uuidData == null) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("%02x", uuidData[i]));
        }
        String hex = sb.toString();

        return String.format("%s-%s-%s-%s-%s",
                hex.substring(0, 8),
                hex.substring(8, 12),
                hex.substring(12, 16),
                hex.substring(16, 20),
                hex.substring(20, 32)).toUpperCase();
    }

    /**
     * Lit un nombre fixe d'octets.
     * @param count Le nombre d'octets à lire.
     * @return Le tableau d'octets, ou null si la lecture est impossible.
     */
    public byte[] readFixedBytes(int count) {
        if (offset + count > data.length) return null;
        byte[] result = Arrays.copyOfRange(data, offset, offset + count);
        offset += count;
        return result;
    }

    /**
     * @return L'offset (position) de lecture actuel.
     */
    public int getCurrentOffset() {
        return offset;
    }
}
