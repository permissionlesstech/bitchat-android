package com.bitchat.android.nostr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structure d'événement Nostr conforme à la NIP-01.
 */
public final class NostrEvent {

    public String id;
    public final String pubkey;
    public final long createdAt;
    public final int kind;
    public final List<List<String>> tags;
    public final String content;
    public String sig;

    public NostrEvent(String id, String pubkey, long createdAt, int kind, List<List<String>> tags, String content, String sig) {
        this.id = id;
        this.pubkey = pubkey;
        this.createdAt = createdAt;
        this.kind = kind;
        this.tags = tags;
        this.content = content;
        this.sig = sig;
    }

    public NostrEvent sign(String privateKeyHex) {
        if (this.id == null || this.id.isEmpty()) {
            calculateEventId();
        }
        this.sig = NostrCrypto.schnorrSign(this.id.getBytes(), privateKeyHex);
        return this;
    }

    private void calculateEventId() {
        try {
            List<Object> serialized = new ArrayList<>();
            serialized.add(0);
            serialized.add(pubkey);
            serialized.add(createdAt);
            serialized.add(kind);
            serialized.add(tags);
            serialized.add(content);

            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            String jsonString = gson.toJson(serialized);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(jsonString.getBytes());

            StringBuilder hexId = new StringBuilder();
            for (byte b : hash) {
                hexId.append(String.format("%02x", b));
            }
            this.id = hexId.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate event ID", e);
        }
    }

    public boolean isValidSignature() {
        if (sig == null) return false;
        String currentId = this.id;
        calculateEventId(); // Recalculate to ensure it's correct
        if (!Objects.equals(currentId, this.id)) return false;

        return NostrCrypto.schnorrVerify(this.id.getBytes(), this.sig, this.pubkey);
    }
}
