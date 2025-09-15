package com.bitchat.android.util;

import java.util.Arrays;

/**
 * Classe wrapper pour un tableau de bytes (ByteArray) afin de permettre son utilisation
 * comme clé dans des HashMaps. La classe ByteArray par défaut ne surcharge pas
 * equals() et hashCode() en se basant sur le contenu, ce qui rend cette classe nécessaire.
 */
public final class ByteArrayWrapper {

    private final byte[] bytes;

    /**
     * Construit un wrapper autour d'un tableau de bytes.
     * @param bytes Le tableau de bytes à encapsuler.
     */
    public ByteArrayWrapper(byte[] bytes) {
        this.bytes = bytes;
    }

    /**
     * Retourne le tableau de bytes encapsulé.
     * @return Le tableau de bytes.
     */
    public byte[] getBytes() {
        return bytes;
    }

    /**
     * Compare cet objet avec un autre pour vérifier l'égalité.
     * La comparaison est basée sur le contenu du tableau de bytes.
     * @param other L'autre objet à comparer.
     * @return true si les objets sont égaux, false sinon.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        ByteArrayWrapper that = (ByteArrayWrapper) other;
        return Arrays.equals(bytes, that.bytes);
    }

    /**
     * Calcule le code de hachage basé sur le contenu du tableau de bytes.
     * @return Le code de hachage.
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /**
     * Convertit le tableau de bytes en sa représentation hexadécimale.
     * @return Une chaîne de caractères représentant le tableau en hexadécimal.
     */
    public String toHexString() {
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
}
