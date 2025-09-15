package com.bitchat.android.geohash;

import java.util.Objects;

/**
 * Identifiant pour le canal de discussion public actuel (soit le maillage, soit un geohash de localisation).
 * Port direct de l'implémentation iOS.
 */
public abstract class ChannelID {
    private ChannelID() {}

    public static final class Mesh extends ChannelID {
        public static final Mesh INSTANCE = new Mesh();
        private Mesh() {}
    }

    public static final class Location extends ChannelID {
        public final GeohashChannel channel;
        public Location(GeohashChannel channel) {
            this.channel = channel;
        }
    }

    /**
     * Nom lisible par l'homme pour l'interface utilisateur.
     */
    public String getDisplayName() {
        if (this instanceof Mesh) {
            return "Mesh";
        } else if (this instanceof Location) {
            return ((Location) this).channel.getDisplayName();
        }
        return "";
    }

    /**
     * Valeur du tag Nostr pour le scoping (geohash), le cas échéant.
     */
    public String getNostrGeohashTag() {
        if (this instanceof Location) {
            return ((Location) this).channel.getGeohash();
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null) return false;
        if (this instanceof Mesh && other instanceof Mesh) return true;
        if (this instanceof Location && other instanceof Location) {
            return Objects.equals(((Location) this).channel, ((Location) other).channel);
        }
        return false;
    }

    @Override
    public int hashCode() {
        if (this instanceof Mesh) {
            return "mesh".hashCode();
        } else if (this instanceof Location) {
            return ((Location) this).channel.hashCode();
        }
        return 0;
    }
}
