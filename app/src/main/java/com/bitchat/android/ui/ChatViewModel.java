package com.bitchat.android.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import com.bitchat.android.favorites.FavoritesPersistenceService;
import com.bitchat.android.mesh.BluetoothMeshService;
import com.bitchat.android.mesh.NoiseSessionDelegate;
import com.bitchat.android.model.BitchatMessage;
import com.bitchat.android.nostr.NostrService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewModel principal refactorisé - Coordinateur principal pour les fonctionnalités de bitchat.
 */
public class ChatViewModel extends AndroidViewModel {

    private final ChatState state = new ChatState();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Les managers
    private final DataManager dataManager;
    private final MessageManager messageManager;
    private final ChannelManager channelManager;
    private final PrivateChatManager privateChatManager;

    // Le service de maillage
    private final BluetoothMeshService meshService;

    // Le service Nostr
    private final NostrService nostrService;

    // LiveData exposé pour l'UI
    public final LiveData<List<BitchatMessage>> messages = state.messages;
    public final LiveData<List<String>> connectedPeers = state.connectedPeers;
    public final LiveData<String> nickname = state.nickname;
    // ... et tous les autres LiveData de ChatState ...

    public ChatViewModel(@NonNull Application application) {
        super(application);

        // TODO: This is not secure. The password should be obtained from a secure source at runtime.
        try {
            this.meshService = new BluetoothMeshService(application, "password");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize BluetoothMeshService", e);
        }

        FavoritesPersistenceService favoritesService = FavoritesPersistenceService.getInstance();
        this.nostrService = new NostrService(favoritesService);

        this.dataManager = new DataManager(application);
        this.messageManager = new MessageManager(state);
        this.channelManager = new ChannelManager(state, messageManager, dataManager, executor);

        NoiseSessionDelegate noiseSessionDelegate = new NoiseSessionDelegate() {
            @Override public boolean hasEstablishedSession(String peerID) { return meshService.hasEstablishedSession(peerID); }
            @Override public void initiateHandshake(String peerID) { /* meshService.initiateNoiseHandshake(peerID); */ }
            @Override public String getMyPeerID() { return meshService.getMyPeerID(); }
        };
        this.privateChatManager = new PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate, nostrService);

        loadAndInitialize();
    }

    private void loadAndInitialize() {
        executor.execute(() -> {
            String nick = dataManager.loadNickname();
            state.setNickname(nick);

            meshService.startServices();
        });
    }

    public void sendMessage(String content) {
        executor.execute(() -> {
            if (content.startsWith("/")) {
                channelManager.handleCommand(content);
            } else {
                String currentChannel = state.getCurrentChannel();
                if (currentChannel != null) {
                    if (currentChannel.startsWith("#")) {
                        // La logique pour envoyer au canal geohash irait ici
                    } else { // Private message
                        privateChatManager.sendPrivateMessage(content, currentChannel, null, null, null, (c, p, rn, m) -> {
                            meshService.sendPrivateMessage(c, p, rn, m);
                        });
                    }
                }
            }
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        meshService.stopServices();
        executor.shutdown();
    }
}
