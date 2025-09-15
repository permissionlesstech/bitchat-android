package com.bitchat.android.services;

import com.bitchat.android.ui.ChatState;
import com.bitchat.android.model.BitchatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Implémentation de la logique pour résoudre les alias de conversation et fusionner les chats.
 */
public class ConversationAliasResolver {

    public static String resolveCanonicalPeerID(
        String selectedPeerID,
        List<String> connectedPeers,
        Function<String, byte[]> meshNoiseKeyForPeer,
        Predicate<String> meshHasPeer,
        Function<String, String> nostrPubHexForAlias,
        Function<String, byte[]> findNoiseKeyForNostr
    ) {
        if (nostrPubHexForAlias.apply(selectedPeerID) != null) {
            return selectedPeerID;
        }
        for (String peer : connectedPeers) {
            if (nostrPubHexForAlias.apply(peer) != null) {
                return peer;
            }
        }
        return selectedPeerID;
    }

    public static void unifyChatsIntoPeer(ChatState state, String targetPeerID, List<String> keysToMerge) {
        List<BitchatMessage> mergedMessages = new ArrayList<>();
        for (String key : keysToMerge) {
            List<BitchatMessage> messages = state.getPrivateChatsValue().get(key);
            if (messages != null) {
                mergedMessages.addAll(messages);
            }
        }

        List<BitchatMessage> targetMessages = state.getPrivateChatsValue().get(targetPeerID);
        if (targetMessages != null) {
            mergedMessages.addAll(targetMessages);
        }

        Collections.sort(mergedMessages, (m1, m2) -> m1.getTimestamp().compareTo(m2.getTimestamp()));

        state.getPrivateChatsValue().put(targetPeerID, mergedMessages);

        for (String key : keysToMerge) {
            state.getPrivateChatsValue().remove(key);
        }
    }
}
