package com.bitchat.android.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Construit un tableau de bytes de manière séquentielle.
 * Fournit des méthodes pour ajouter différents types de données en format binaire (big-endian).
 */
public class BinaryDataBuilder {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * Ajoute un octet non signé (8 bits).
     * @param value La valeur (en tant que int pour représenter 0-255) à ajouter.
     */
    public void appendUInt8(int value) {
        buffer.write(value & 0xFF);
    }

    /**
     * Ajoute un entier court non signé (16 bits) en big-endian.
     * @param value La valeur (en tant que int) à ajouter.
     */
    public void appendUInt16(int value) {
        buffer.write((value >> 8) & 0xFF);
        buffer.write(value & 0xFF);
    }

    /**
     * Ajoute un entier non signé (32 bits) en big-endian.
     * @param value La valeur (en tant que long) à ajouter.
     */
    public void appendUInt32(long value) {
        buffer.write((int)((value >> 24) & 0xFF));
        buffer.write((int)((value >> 16) & 0xFF));
        buffer.write((int)((value >> 8) & 0xFF));
        buffer.write((int)(value & 0xFF));
    }

    /**
     * Ajoute un entier long non signé (64 bits) en big-endian.
     * @param value La valeur (en tant que long) à ajouter.
     */
    public void appendUInt64(long value) {
        for (int i = 7; i >= 0; i--) {
            buffer.write((int)((value >> (i * 8)) & 0xFF));
        }
    }

    /**
     * Ajoute une chaîne de caractères, préfixée par sa longueur.
     * @param string La chaîne à ajouter.
     * @param maxLength La longueur maximale de la chaîne (tronquée si plus longue).
     */
    public void appendString(String string, int maxLength) {
        byte[] data = string.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(data.length, maxLength);

        if (maxLength <= 255) {
            appendUInt8(length);
        } else {
            appendUInt16(length);
        }

        try {
            buffer.write(data, 0, length);
        } catch (IOException e) {
            // Ne devrait jamais arriver avec ByteArrayOutputStream
        }
    }

    /**
     * Ajoute un tableau de bytes, préfixé par sa longueur.
     * @param data Les données à ajouter.
     * @param maxLength La longueur maximale des données (tronquées si plus longues).
     */
    public void appendData(byte[] data, int maxLength) {
        int length = Math.min(data.length, maxLength);

        if (maxLength <= 255) {
            appendUInt8(length);
        } else {
            appendUInt16(length);
        }

        try {
            buffer.write(data, 0, length);
        } catch (IOException e) {
            // Ne devrait jamais arriver avec ByteArrayOutputStream
        }
    }

    /**
     * Ajoute une date sous forme de timestamp 64 bits (millisecondes).
     * @param date La date à ajouter.
     */
    public void appendDate(Date date) {
        appendUInt64(date.getTime());
    }

    /**
     * Ajoute un UUID (16 bytes).
     * @param uuid La chaîne de caractères de l'UUID à ajouter.
     */
    public void appendUUID(String uuid) {
        String cleanUUID = uuid.replace("-", "");
        if (cleanUUID.length() != 32) {
            // Gérer l'erreur, peut-être en ajoutant un UUID nul ou en levant une exception
            byte[] nullUUID = new byte[16];
            try {
                buffer.write(nullUUID);
            } catch (IOException e) { /* impossible */ }
            return;
        }
        byte[] uuidData = new byte[16];
        for (int i = 0; i < 16; i++) {
            uuidData[i] = (byte) Integer.parseInt(cleanUUID.substring(i * 2, i * 2 + 2), 16);
        }
        try {
            buffer.write(uuidData);
        } catch (IOException e) {
            // Ne devrait jamais arriver avec ByteArrayOutputStream
        }
    }

    /**
     * Retourne le tableau de bytes construit.
     * @return Le tableau de bytes final.
     */
    public byte[] toByteArray() {
        return buffer.toByteArray();
    }
}
