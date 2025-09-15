package com.bitchat.android.nostr;

/**
 * Contient les constantes pour les types d'événements (kinds) Nostr.
 */
public final class NostrKind {
    private NostrKind() {} // Classe non instanciable

    public static final int METADATA = 0;
    public static final int TEXT_NOTE = 1;
    public static final int DIRECT_MESSAGE = 14;
    public static final int FILE_MESSAGE = 15;
    public static final int SEAL = 13;
    public static final int GIFT_WRAP = 1059;
    public static final int EPHEMERAL_EVENT = 20000;
}
