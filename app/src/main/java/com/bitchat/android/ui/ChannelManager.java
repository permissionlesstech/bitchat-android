package com.bitchat.android.ui;

import com.bitchat.android.model.BitchatMessage;
import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gère la gestion des canaux, y compris la création, l'adhésion, la sortie et le chiffrement.
 */
public class ChannelManager {

    private final ChatState state;
    private final MessageManager messageManager;
    private final DataManager dataManager;
    private final ExecutorService executor;

    private final Map<String, SecretKeySpec> channelKeys = new HashMap<>();
    private final Map<String, String> channelPasswords = new HashMap<>();

    public ChannelManager(ChatState state, MessageManager messageManager, DataManager dataManager, ExecutorService executor) {
        this.state = state;
        this.messageManager = messageManager;
        this.dataManager = dataManager;
        this.executor = executor;
    }

    public boolean joinChannel(String channel, String password, String myPeerID) {
        String channelTag = channel.startsWith("#") ? channel : "#" + channel;

        if (state.getJoinedChannelsValue().contains(channelTag)) {
            // ... La logique de re-vérification du mot de passe irait ici ...
            switchToChannel(channelTag);
            return true;
        }

        Set<String> updatedChannels = new HashSet<>(state.getJoinedChannelsValue());
        updatedChannels.add(channelTag);
        state.setJoinedChannels(updatedChannels);

        if (!dataManager.getChannelCreators().containsKey(channelTag) && !state.getPasswordProtectedChannelsValue().contains(channelTag)) {
            dataManager.addChannelCreator(channelTag, myPeerID);
        }

        if (!state.getChannelMessagesValue().containsKey(channelTag)) {
            Map<String, List<BitchatMessage>> updatedMessages = new HashMap<>(state.getChannelMessagesValue());
            updatedMessages.put(channelTag, Collections.emptyList());
            state.setChannelMessages(updatedMessages);
        }

        switchToChannel(channelTag);
        saveChannelData();
        return true;
    }

    public void leaveChannel(String channel) {
        Set<String> updatedChannels = new HashSet<>(state.getJoinedChannelsValue());
        updatedChannels.remove(channel);
        state.setJoinedChannels(updatedChannels);

        if (channel.equals(state.getCurrentChannelValue())) {
            state.setCurrentChannel(null);
        }

        // ... La logique de nettoyage (messages, membres, etc.) irait ici ...
        saveChannelData();
    }

    public void switchToChannel(String channel) {
        state.setCurrentChannel(channel);
        state.setSelectedPrivateChatPeer(null);
        // ... La logique de nettoyage des messages non lus irait ici ...
    }

    private SecretKeySpec deriveChannelKey(String password, String channelName) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(password.toCharArray(), channelName.getBytes(StandardCharsets.UTF_8), 100000, 256);
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String decryptChannelMessage(byte[] encryptedContent, String channel) {
        SecretKeySpec key = channelKeys.get(channel);
        if (key == null) return null;

        try {
            if (encryptedContent.length < 12) return null; // IV GCM

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = Arrays.copyOfRange(encryptedContent, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(encryptedContent, 12, encryptedContent.length);

            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);

            byte[] decryptedData = cipher.doFinal(ciphertext);
            return new String(decryptedData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void addChannelMessage(String channel, BitchatMessage message, String senderPeerID) {
        messageManager.addChannelMessage(channel, message);
        // ... La logique de gestion des membres du canal irait ici ...
    }

    public boolean hasChannelKey(String channel) {
        return channelKeys.containsKey(channel);
    }

    private void saveChannelData() {
        dataManager.saveChannelData(state.getJoinedChannelsValue(), state.getPasswordProtectedChannelsValue());
    }

    public void clearAllChannels() {
        state.setJoinedChannels(Collections.emptySet());
        state.setCurrentChannel(null);
        state.setPasswordProtectedChannels(Collections.emptySet());
        state.setShowPasswordPrompt(false);
        state.setPasswordPromptChannel(null);
        channelKeys.clear();
        channelPasswords.clear();
    }
}
