package com.bitchat.android.geohash;

import java.util.Objects;

/**
 * Représente un identifiant de canal, qui peut être soit le réseau maillé global (Mesh),
 * soit un canal de géolocalisation spécifique.
 * Ceci est une simulation d'une "sealed class" Kotlin en Java.
 */
public abstract class ChannelID {
    // Constructeur privé pour empêcher d'autres sous-classes en dehors de ce fichier.
    private ChannelID() {}

    /**
     * Représente le canal de discussion principal sur le réseau maillé (mesh).
     */
    public static final class Mesh extends ChannelID {
        // Utilisation d'un singleton car il n'y a qu'une seule instance de Mesh.
        public static final Mesh INSTANCE = new Mesh();
        private Mesh() {}
    }

    /**
     * Représente un canal de discussion basé sur la géolocalisation (geohash).
     */
    public static final class Location extends ChannelID {
        public final String channel;

        public Location(String channel) {
            this.channel = channel;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Location location = (Location) o;
            return Objects.equals(channel, location.channel);
        }

        @Override
        public int hashCode() {
            return Objects.hash(channel);
        }
    }
}
