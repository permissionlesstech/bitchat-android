package com.bitchat.android.protocol;

/**
 * Interface pour les objets qui peuvent être sérialisés en données binaires.
 * Les classes implémentant cette interface peuvent être converties en un tableau de bytes
 * pour la transmission sur le réseau ou le stockage.
 */
public interface BinaryEncodable {
    /**
     * Sérialise l'objet en un tableau de bytes.
     * @return Le tableau de bytes représentant l'objet.
     */
    byte[] toBinaryData();
}
