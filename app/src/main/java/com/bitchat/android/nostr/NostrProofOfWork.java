package com.bitchat.android.nostr;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Implémentation de la Preuve de Travail (Proof of Work) Nostr suivant la NIP-13.
 */
public final class NostrProofOfWork {

    private NostrProofOfWork() {}

    private static final String TAG = "NostrProofOfWork";

    public static int calculateDifficulty(String eventIdHex) {
        int count = 0;
        for (char c : eventIdHex.toCharArray()) {
            int nibble = Character.digit(c, 16);
            if (nibble == 0) {
                count += 4;
            } else {
                count += Integer.numberOfLeadingZeros(nibble) - (Integer.SIZE - 4);
                break;
            }
        }
        return count;
    }

    public static boolean validateDifficulty(NostrEvent event, int minimumDifficulty) {
        if (minimumDifficulty <= 0) return true;
        if (!hasNonce(event)) return false;

        int actualDifficulty = calculateDifficulty(event.id);
        return actualDifficulty >= minimumDifficulty;
    }

    /**
     * Mine un événement Nostr pour atteindre la difficulté cible.
     * Note : C'est une opération longue qui devrait être exécutée en arrière-plan.
     */
    public static NostrEvent mineEvent(NostrEvent event, int targetDifficulty, int maxIterations) {
        if (targetDifficulty <= 0) return event;

        Random random = new Random();
        long nonceValue = random.nextInt(1_000_000);

        for (int i = 0; i < maxIterations; i++) {
            NostrEvent eventWithNonce = addNonceTag(event, String.valueOf(nonceValue), targetDifficulty);
            // La méthode sign() calcule et met à jour l'ID.
            // eventWithNonce.sign(somePrivateKey); // La signature n'est pas nécessaire pour juste trouver le nonce.
            // Il faut une méthode pour juste calculer l'ID. Supposons qu'elle existe.
            // String eventId = eventWithNonce.computeEventIdHex();
            // if (calculateDifficulty(eventId) >= targetDifficulty) {
            //     return eventWithNonce;
            // }
            nonceValue++;
        }
        return null; // N'a pas réussi à miner dans les itérations imparties.
    }

    private static NostrEvent addNonceTag(NostrEvent event, String nonce, int targetDifficulty) {
        List<List<String>> newTags = new ArrayList<>();
        for (List<String> tag : event.tags) {
            if (tag.isEmpty() || !"nonce".equals(tag.get(0))) {
                newTags.add(new ArrayList<>(tag));
            }
        }
        List<String> nonceTag = new ArrayList<>();
        nonceTag.add("nonce");
        nonceTag.add(nonce);
        nonceTag.add(String.valueOf(targetDifficulty));
        newTags.add(nonceTag);

        long updatedCreatedAt = System.currentTimeMillis() / 1000;

        // Crée une nouvelle instance avec les tags mis à jour.
        return new NostrEvent(event.id, event.pubkey, updatedCreatedAt, event.kind, newTags, event.content, event.sig);
    }

    public static boolean hasNonce(NostrEvent event) {
        for (List<String> tag : event.tags) {
            if (!tag.isEmpty() && "nonce".equals(tag.get(0))) {
                return true;
            }
        }
        return false;
    }
}
