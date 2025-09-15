package com.bitchat.android.ui;

import java.util.Objects;

/**
 * Représente une personne dans un canal de géolocalisation (geohash).
 * Ceci est une classe de données simple (POJO).
 * Note: Les champs sont des suppositions basées sur l'utilisation typique.
 * La définition exacte dépendra de la conversion de la logique Geohash.
 */
public final class GeoPerson {
    private final String pubkeyHex;
    private final String nickname;
    private final long lastSeen;

    public GeoPerson(String pubkeyHex, String nickname, long lastSeen) {
        this.pubkeyHex = pubkeyHex;
        this.nickname = nickname;
        this.lastSeen = lastSeen;
    }

    public String getPubkeyHex() {
        return pubkeyHex;
    }

    public String getNickname() {
        return nickname;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GeoPerson geoPerson = (GeoPerson) o;
        return lastSeen == geoPerson.lastSeen &&
               Objects.equals(pubkeyHex, geoPerson.pubkeyHex) &&
               Objects.equals(nickname, geoPerson.nickname);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pubkeyHex, nickname, lastSeen);
    }
}
