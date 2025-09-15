package com.bitchat.android.protocol;

/**
 * Contient les identifiants de destinataires spéciaux.
 * Identique à la version iOS.
 */
public final class SpecialRecipients {

    /**
     * Constructeur privé pour empêcher l'instanciation de cette classe utilitaire.
     */
    private SpecialRecipients() {}

    /**
     * L'identifiant pour un message de diffusion (broadcast).
     * Un tableau de 8 bytes, tous à 0xFF.
     */
    public static final byte[] BROADCAST = new byte[] {
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };
}
