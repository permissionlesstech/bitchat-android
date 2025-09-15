package com.bitchat.android.nostr;

import java.security.MessageDigest;
import java.util.Objects;

/**
 * Gère une identité Nostr (paire de clés secp256k1).
 */
public final class NostrIdentity {

    public final String privateKeyHex;
    public final String publicKeyHex;
    public final String npub;
    public final long createdAt;

    public NostrIdentity(String privateKeyHex, String publicKeyHex, String npub, long createdAt) {
        this.privateKeyHex = privateKeyHex;
        this.publicKeyHex = publicKeyHex;
        this.npub = npub;
        this.createdAt = createdAt;
    }

    public static NostrIdentity fromPrivateKey(String privateKeyHex) {
        // La logique de dérivation de clé publique irait ici, en utilisant NostrCrypto
        String publicKeyHex = ""; // Placeholder
        String npub = ""; // Placeholder
        return new NostrIdentity(privateKeyHex, publicKeyHex, npub, System.currentTimeMillis());
    }

    public static NostrIdentity fromSeed(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] privateKeyBytes = digest.digest(seed.getBytes());
            // Convertir bytes en hex
            StringBuilder hexString = new StringBuilder();
            for (byte b : privateKeyBytes) {
                hexString.append(String.format("%02x", b));
            }
            return fromPrivateKey(hexString.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create identity from seed", e);
        }
    }

    public NostrEvent signEvent(NostrEvent event) {
        return event.sign(privateKeyHex);
    }

    public String getShortNpub() {
        if (npub.length() > 16) {
            return npub.substring(0, 8) + "..." + npub.substring(npub.length() - 8);
        }
        return npub;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NostrIdentity that = (NostrIdentity) o;
        return createdAt == that.createdAt && privateKeyHex.equals(that.privateKeyHex) && publicKeyHex.equals(that.publicKeyHex) && npub.equals(that.npub);
    }

    @Override
    public int hashCode() {
        return Objects.hash(privateKeyHex, publicKeyHex, npub, createdAt);
    }
}
