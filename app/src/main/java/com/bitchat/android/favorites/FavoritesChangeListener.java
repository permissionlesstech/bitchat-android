package com.bitchat.android.favorites;

/**
 * Interface pour écouter les changements dans les favoris.
 */
public interface FavoritesChangeListener {
    /**
     * Appelé lorsqu'un favori a changé.
     * @param noiseKeyHex La clé publique Noise (en hexadécimal) du pair qui a changé.
     */
    void onFavoriteChanged(String noiseKeyHex);

    /**
     * Appelé lorsque tous les favoris ont été effacés.
     */
    void onAllCleared();
}
