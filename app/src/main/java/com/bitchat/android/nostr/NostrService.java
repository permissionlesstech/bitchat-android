package com.bitchat.android.nostr;

import com.bitchat.android.favorites.FavoritesPersistenceService;
import com.bitchat.android.favorites.FavoriteRelationship;
import com.bitchat.android.util.BinaryEncodingUtils;

/**
 * Service that provides Nostr-related functionality.
 */
public class NostrService {

    private final GeohashAliasRegistry aliasRegistry;
    private final FavoritesPersistenceService favoritesService;

    public NostrService(FavoritesPersistenceService favoritesService) {
        this.aliasRegistry = GeohashAliasRegistry.getInstance();
        this.favoritesService = favoritesService;
    }

    public byte[] getNoiseKeyForPeer(String peer) {
        FavoriteRelationship favorite = favoritesService.getFavoriteStatus(BinaryEncodingUtils.dataFromHexString(peer));
        if (favorite != null) {
            return favorite.getPeerNoisePublicKey();
        }
        return null;
    }

    public boolean hasPeer(String peer) {
        return favoritesService.getFavoriteStatus(BinaryEncodingUtils.dataFromHexString(peer)) != null;
    }

    public String getNostrPubHexForAlias(String alias) {
        return aliasRegistry.get(alias);
    }

    public byte[] findNoiseKeyForNostr(String npub) {
        // This is a reverse lookup, which is not efficient.
        // For now, we iterate through all favorites.
        // A better solution would be to have a reverse mapping in FavoritesPersistenceService.
        for (FavoriteRelationship favorite : favoritesService.getAllFavorites()) {
            if (npub.equals(favorite.getPeerNostrPublicKey())) {
                return favorite.getPeerNoisePublicKey();
            }
        }
        return null;
    }
}
