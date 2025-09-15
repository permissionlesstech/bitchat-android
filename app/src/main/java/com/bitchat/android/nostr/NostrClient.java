package com.bitchat.android.nostr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class NostrClient {
    private static final String TAG = "NostrClient";

    private static volatile NostrClient INSTANCE;

    private final Context context;
    private final NostrRelayManager relayManager = NostrRelayManager.shared;
    private NostrIdentity currentIdentity;

    private final MutableLiveData<Boolean> _isInitialized = new MutableLiveData<>();
    public final LiveData<Boolean> isInitialized = _isInitialized;

    private final MutableLiveData<String> _currentNpub = new MutableLiveData<>();
    public final LiveData<String> currentNpub = _currentNpub;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private NostrClient(Context context) {
        this.context = context.getApplicationContext();
        Log.d(TAG, "Initializing Nostr client");
    }

    public static NostrClient getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (NostrClient.class) {
                if (INSTANCE == null) {
                    INSTANCE = new NostrClient(context);
                }
            }
        }
        return INSTANCE;
    }

    public void initialize() {
        executor.submit(() -> {
            try {
                currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context);
                if (currentIdentity != null) {
                    mainHandler.post(() -> {
                        _currentNpub.setValue(currentIdentity.getNpub());
                        _isInitialized.setValue(true);
                    });
                    Log.i(TAG, "✅ Nostr identity loaded: " + currentIdentity.getShortNpub());
                    relayManager.connect();
                    Log.i(TAG, "✅ Nostr client initialized successfully");
                } else {
                    Log.e(TAG, "❌ Failed to load/create Nostr identity");
                    mainHandler.post(() -> _isInitialized.setValue(false));
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to initialize Nostr client: " + e.getMessage());
                mainHandler.post(() -> _isInitialized.setValue(false));
            }
        });
    }

    public void shutdown() {
        Log.d(TAG, "Shutting down Nostr client");
        relayManager.disconnect();
        _isInitialized.postValue(false);
    }

    public void sendPrivateMessage(String content, String recipientNpub, Runnable onSuccess, Consumer<String> onError) {
        if (currentIdentity == null) {
            if (onError != null) {
                onError.accept("Nostr client not initialized");
            }
            return;
        }

        executor.submit(() -> {
            try {
                Bech32.DecodedResult decoded = Bech32.decode(recipientNpub);
                if (!"npub".equals(decoded.hrp)) {
                    if (onError != null) {
                        mainHandler.post(() -> onError.accept("Invalid npub format"));
                    }
                    return;
                }
                String recipientPubkeyHex = NostrCrypto.bytesToHex(decoded.data);

                List<NostrEvent> giftWraps = NostrProtocol.createPrivateMessage(content, recipientPubkeyHex, currentIdentity);

                for (NostrEvent wrap : giftWraps) {
                    NostrRelayManager.registerPendingGiftWrap(wrap.getId());
                    relayManager.sendEvent(wrap, null);
                }

                Log.i(TAG, "📤 Sent private message to " + recipientNpub.substring(0, 16) + "...");
                if (onSuccess != null) {
                    mainHandler.post(onSuccess);
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to send private message: " + e.getMessage());
                if (onError != null) {
                    mainHandler.post(() -> onError.accept("Failed to send message: " + e.getMessage()));
                }
            }
        });
    }

    public void subscribeToPrivateMessages(PrivateMessageHandler handler) {
        if (currentIdentity == null) {
            Log.e(TAG, "Cannot subscribe to private messages: client not initialized");
            return;
        }

        NostrFilter filter = NostrFilter.giftWrapsFor(
                currentIdentity.getPublicKeyHex(),
                System.currentTimeMillis() - 172800000L
        );

        relayManager.subscribe(filter, "private-messages", giftWrap -> {
            executor.submit(() -> handlePrivateMessage(giftWrap, handler));
        }, null);

        Log.i(TAG, "🔑 Subscribed to private messages for: " + currentIdentity.getShortNpub());
    }

    public void sendGeohashMessage(String content, String geohash, String nickname, Runnable onSuccess, Consumer<String> onError) {
        executor.submit(() -> {
            try {
                NostrIdentity geohashIdentity = NostrIdentityBridge.deriveIdentity(geohash, context);
                NostrEvent event = NostrProtocol.createEphemeralGeohashEvent(content, geohash, geohashIdentity, nickname);
                relayManager.sendEvent(event, null);
                Log.i(TAG, "📤 Sent geohash message to #" + geohash);
                if (onSuccess != null) {
                    mainHandler.post(onSuccess);
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to send geohash message: " + e.getMessage());
                if (onError != null) {
                    mainHandler.post(() -> onError.accept("Failed to send message: " + e.getMessage()));
                }
            }
        });
    }

    public void subscribeToGeohash(String geohash, GeohashMessageHandler handler) {
        NostrFilter filter = NostrFilter.geohashEphemeral(geohash, System.currentTimeMillis() - 3600000L, 200);
        relayManager.subscribe(filter, "geohash-" + geohash, event -> {
            executor.submit(() -> handleGeohashMessage(event, handler));
        }, null);
        Log.i(TAG, "🌍 Subscribed to geohash channel: #" + geohash);
    }

    public void unsubscribeFromGeohash(String geohash) {
        relayManager.unsubscribe("geohash-" + geohash);
        Log.i(TAG, "Unsubscribed from geohash channel: #" + geohash);
    }

    public NostrIdentity getCurrentIdentity() {
        return currentIdentity;
    }

    public LiveData<Boolean> getRelayConnectionStatus() {
        return relayManager.isConnected;
    }

    public LiveData<List<NostrRelayManager.Relay>> getRelayInfo() {
        return relayManager.relays;
    }

    private void handlePrivateMessage(NostrEvent giftWrap, PrivateMessageHandler handler) {
        long messageAge = System.currentTimeMillis() / 1000 - giftWrap.getCreatedAt();
        if (messageAge > 173700) { // 48 hours + 15 minutes
            Log.v(TAG, "Ignoring old private message");
            return;
        }

        if (currentIdentity == null) return;

        try {
            NostrProtocol.DecryptResult decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, currentIdentity);
            if (decryptResult != null) {
                String senderNpub;
                try {
                    senderNpub = Bech32.encode("npub", decryptResult.senderPubkey.getBytes());
                } catch (Exception e) {
                    Log.w(TAG, "Failed to encode sender npub: " + e.getMessage());
                    senderNpub = "npub_decode_error";
                }
                Log.d(TAG, "📥 Received private message from " + senderNpub.substring(0, 16) + "...");
                String finalSenderNpub = senderNpub;
                mainHandler.post(() -> handler.handle(decryptResult.content, finalSenderNpub, decryptResult.timestamp));
            } else {
                Log.w(TAG, "Failed to decrypt private message");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling private message: " + e.getMessage());
        }
    }

    private void handleGeohashMessage(NostrEvent event, GeohashMessageHandler handler) {
        try {
            PoWPreferenceManager.PoWSettings powSettings = PoWPreferenceManager.getCurrentSettings();
            if (powSettings.isEnabled() && powSettings.getDifficulty() > 0) {
                if (!NostrProofOfWork.validateDifficulty(event, powSettings.getDifficulty())) {
                    Log.w(TAG, "🚫 Rejecting geohash event " + event.getId().substring(0, 8) + "... due to insufficient PoW (required: " + powSettings.getDifficulty() + ")");
                    return;
                }
                Log.v(TAG, "✅ PoW validation passed for geohash event " + event.getId().substring(0, 8) + "...");
            }

            String nickname = event.getTags().stream()
                    .filter(tag -> tag.size() >= 2 && "n".equals(tag.get(0)))
                    .map(tag -> tag.get(1))
                    .findFirst().orElse(null);

            Log.v(TAG, "📥 Received geohash message from " + event.getPubkey().substring(0, 16) + "...");
            mainHandler.post(() -> handler.handle(event.getContent(), event.getPubkey(), nickname, (int) event.getCreatedAt()));
        } catch (Exception e) {
            Log.e(TAG, "Error handling geohash message: " + e.getMessage());
        }
    }

    @FunctionalInterface
    public interface PrivateMessageHandler {
        void handle(String content, String senderNpub, int timestamp);
    }

    @FunctionalInterface
    public interface GeohashMessageHandler {
        void handle(String content, String senderPubkey, String nickname, int timestamp);
    }
}
