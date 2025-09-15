package com.bitchat.android.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

/**
 * Classe utilitaire pour l'encodage et le décodage binaire,
 * compatible avec la version iOS BinaryEncodingUtils.swift.
 * Contient des méthodes statiques pour la manipulation de bas niveau des tableaux de bytes.
 */
public final class BinaryEncodingUtils {

    private BinaryEncodingUtils() {
        // Classe utilitaire non instanciable
    }

    /**
     * Convertit un tableau de bytes en sa représentation hexadécimale.
     * @param bytes Le tableau à convertir.
     * @return La chaîne hexadécimale.
     */
    public static String hexEncodedString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Convertit une chaîne hexadécimale en tableau de bytes.
     * @param hex La chaîne hexadécimale à convertir.
     * @return Le tableau de bytes, ou null si la chaîne est invalide.
     */
    public static byte[] dataFromHexString(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            try {
                data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                     + Character.digit(hex.charAt(i+1), 16));
            } catch (Exception e) {
                return null;
            }
        }
        return data;
    }

    // Les méthodes suivantes imitent les fonctions d'extension Kotlin en utilisant
    // un tableau d'entiers 'at' comme pointeur d'offset mutable. at[0] est l'offset.

    public static Integer readUInt8(byte[] data, int[] at) {
        int offset = at[0];
        if (offset >= data.length) return null;
        int value = data[offset] & 0xFF;
        at[0] += 1;
        return value;
    }

    public static Integer readUInt16(byte[] data, int[] at) {
        int offset = at[0];
        if (offset + 2 > data.length) return null;
        int value = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        at[0] += 2;
        return value;
    }

    public static Long readUInt32(byte[] data, int[] at) {
        int offset = at[0];
        if (offset + 4 > data.length) return null;
        long value = ((long)(data[offset] & 0xFF) << 24) |
                     ((long)(data[offset + 1] & 0xFF) << 16) |
                     ((long)(data[offset + 2] & 0xFF) << 8) |
                     ((long)(data[offset + 3] & 0xFF));
        at[0] += 4;
        return value;
    }

    public static Long readUInt64(byte[] data, int[] at) {
        int offset = at[0];
        if (offset + 8 > data.length) return null;
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        at[0] += 8;
        return value;
    }

    public static String readString(byte[] data, int[] at, int maxLength) {
        Integer length;
        if (maxLength <= 255) {
            length = readUInt8(data, at);
        } else {
            length = readUInt16(data, at);
        }
        if (length == null) return null;

        int offset = at[0];
        if (offset + length > data.length) return null;

        String result = new String(data, offset, length, StandardCharsets.UTF_8);
        at[0] += length;
        return result;
    }

    public static byte[] readData(byte[] data, int[] at, int maxLength) {
        Integer length;
        if (maxLength <= 255) {
            length = readUInt8(data, at);
        } else {
            length = readUInt16(data, at);
        }
        if (length == null) return null;

        int offset = at[0];
        if (offset + length > data.length) return null;

        byte[] result = Arrays.copyOfRange(data, offset, offset + length);
        at[0] += length;
        return result;
    }

    public static Date readDate(byte[] data, int[] at) {
        Long timestamp = readUInt64(data, at);
        if (timestamp == null) return null;
        return new Date(timestamp);
    }

    public static String readUUID(byte[] data, int[] at) {
        int offset = at[0];
        if (offset + 16 > data.length) return null;

        byte[] uuidData = Arrays.copyOfRange(data, offset, offset + 16);
        at[0] += 16;

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

    public static byte[] readFixedBytes(byte[] data, int[] at, int count) {
        int offset = at[0];
        if (offset + count > data.length) return null;

        byte[] result = Arrays.copyOfRange(data, offset, offset + count);
        at[0] += count;

        return result;
    }
}
