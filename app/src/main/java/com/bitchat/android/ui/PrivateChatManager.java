package com.bitchat.android.ui;

import android.util.Log;

import com.bitchat.android.mesh.BluetoothMeshService;
import com.bitchat.android.mesh.PeerFingerprintManager;
import com.bitchat.android.model.BitchatMessage;
import com.bitchat.android.model.DeliveryStatus;
import com.bitchat.android.services.ConversationAliasResolver;
import com.bitchat.android.nostr.NostrService;


import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Gère la fonctionnalité de chat privé, y compris la gestion des pairs et le blocage.
 * Utilise le PeerFingerprintManager centralisé pour toutes les opérations d'empreinte.
 */
public class PrivateChatManager {

    private static final String TAG = "PrivateChatManager";

    private final ChatState state;
    private final MessageManager messageManager;
    private final DataManager dataManager;
    private final NoiseSessionDelegate noiseSessionDelegate;
    private final PeerFingerprintManager fingerprintManager;
    private final NostrService nostrService;

    public PrivateChatManager(ChatState state, MessageManager messageManager, DataManager dataManager, NoiseSessionDelegate noiseSessionDelegate, NostrService nostrService) {
        this.state = state;
        this.messageManager = messageManager;
        this.dataManager = dataManager;
        this.noiseSessionDelegate = noiseSessionDelegate;
        this.fingerprintManager = PeerFingerprintManager.getInstance();
        this.nostrService = nostrService;
    }

    public boolean startPrivateChat(String peerID, BluetoothMeshService meshService) {
        if (isPeerBlocked(peerID)) {
            // ... logique pour gérer l'utilisateur bloqué ...
            return false;
        }

        establishNoiseSessionIfNeeded(peerID, meshService);
        consolidateNostrTempConversationIfNeeded(peerID);

        state.setSelectedPrivateChatPeer(peerID);
        // ... logique pour marquer les messages comme lus, etc. ...
        return true;
    }

    public void endPrivateChat() {
        state.setSelectedPrivateChatPeer(null);
    }

    public void sendPrivateMessage(
        String content,
        String peerID,
        String recipientNickname,
        String senderNickname,
        String myPeerID,
        SendMessageCallback onSendMessage
    ) {
        if (isPeerBlocked(peerID)) {
            // ... logique de gestion de l'utilisateur bloqué ...
            return;
        }

        BitchatMessage message = new BitchatMessage(
            java.util.UUID.randomUUID().toString().toUpperCase(),
            senderNickname != null ? senderNickname : myPeerID,
            content,
            new Date(),
            false,
            null,
            true,
            recipientNickname,
            myPeerID,
            null,
            null,
            null,
            false,
            new DeliveryStatus.Sending(),
            null
        );

        messageManager.addPrivateMessage(peerID, message);
        onSendMessage.send(content, peerID, recipientNickname != null ? recipientNickname : "", message.getId());
    }

    public boolean isPeerBlocked(String peerID) {
        String fingerprint = fingerprintManager.getFingerprintForPeer(peerID);
        return fingerprint != null && dataManager.isUserBlocked(fingerprint);
    }

    public void toggleFavorite(String peerID) {
        String fingerprint = fingerprintManager.getFingerprintForPeer(peerID);
        if (fingerprint == null) {
            Log.w(TAG, "toggleFavorite: no fingerprint for peerID=" + peerID);
            return;
        }

        if (dataManager.isFavorite(fingerprint)) {
            dataManager.removeFavorite(fingerprint);
        } else {
            dataManager.addFavorite(fingerprint);
        }

        state.setFavoritePeers(new java.util.HashSet<>(dataManager.getFavoritePeers()));
    }

    public boolean isFavorite(String peerID) {
        String fingerprint = fingerprintManager.getFingerprintForPeer(peerID);
        return fingerprint != null && dataManager.isFavorite(fingerprint);
    }

    public Map<String, String> getAllPeerFingerprints() {
        return fingerprintManager.getAllPeerFingerprints();
    }

    private void establishNoiseSessionIfNeeded(String peerID, BluetoothMeshService meshService) {
        if (noiseSessionDelegate.hasEstablishedSession(peerID)) {
            return;
        }
        String myPeerID = noiseSessionDelegate.getMyPeerID();
        if (myPeerID.compareTo(peerID) < 0) {
            noiseSessionDelegate.initiateHandshake(peerID);
        } else {
            meshService.sendAnnouncementToPeer(peerID);
            noiseSessionDelegate.initiateHandshake(peerID);
        }
    }

    private void consolidateNostrTempConversationIfNeeded(String targetPeerID) {
        List<String> nostrAliases = state.getPrivateChatsValue().keySet().stream()
            .filter(key -> key.startsWith("nostr_"))
            .collect(Collectors.toList());

        String canonicalPeerID = ConversationAliasResolver.resolveCanonicalPeerID(
            targetPeerID,
            new ArrayList<>(state.getConnectedPeers().getValue()),
            peer -> nostrService.getNoiseKeyForPeer(peer),
            peer -> nostrService.hasPeer(peer),
            alias -> nostrService.getNostrPubHexForAlias(alias),
            npub -> nostrService.findNoiseKeyForNostr(npub)
        );

        if (!canonicalPeerID.equals(targetPeerID)) {
            List<String> keysToMerge = new ArrayList<>();
            keysToMerge.add(targetPeerID);
            keysToMerge.addAll(nostrAliases);
            ConversationAliasResolver.unifyChatsIntoPeer(state, canonicalPeerID, keysToMerge);
            state.setSelectedPrivateChatPeer(canonicalPeerID);
        }
    }

    public void clearAllPrivateChats() {
        state.setSelectedPrivateChatPeer(null);
        state.setUnreadPrivateMessages(new java.util.HashSet<>());
        // La logique de nettoyage des messages non lus irait ici.
    }

    // Interface de callback pour l'envoi de message, pour éviter une dépendance cyclique ou directe.
    @FunctionalInterface
    public interface SendMessageCallback {
        void send(String content, String peerID, String recipientNickname, String messageId);
    }
}
