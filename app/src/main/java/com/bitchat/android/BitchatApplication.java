package com.bitchat.android;

import android.app.Application;
import com.bitchat.android.favorites.FavoritesPersistenceService;
import com.bitchat.android.nostr.NostrIdentityBridge;
import com.bitchat.android.nostr.RelayDirectory;

/**
 * Classe principale de l'application pour bitchat Android.
 */
public class BitchatApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialiser le répertoire des relais (charge assets/nostr_relays.csv)
        RelayDirectory.initialize(this);

        // Initialiser la persistance des favoris tôt
        try {
            FavoritesPersistenceService.initialize(this);
        } catch (Exception e) {
            // Ignorer l'erreur
        }

        // Préchauffer l'identité Nostr pour s'assurer que npub est disponible
        try {
            NostrIdentityBridge.getCurrentNostrIdentity(this);
        } catch (Exception e) {
            // Ignorer l'erreur
        }

        // La logique pour initialiser le thème et le gestionnaire de préférences de débogage irait ici.
    }
}
