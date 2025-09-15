package com.bitchat.android.nostr;

import android.app.Application;
import android.util.Log;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * NostrSubscriptionManager
 * - Encapsulates subscription lifecycle with NostrRelayManager
 */
public class NostrSubscriptionManager {
    private static final String TAG = "NostrSubscriptionManager";

    private final Application application;
    private final Executor scope;
    private NostrRelayManager relayManager;

    public NostrSubscriptionManager(Application application, Executor scope) {
        this.application = application;
        this.scope = scope;
    }

    private NostrRelayManager getRelayManager() {
        if (relayManager == null) {
            relayManager = NostrRelayManager.getInstance(application);
        }
        return relayManager;
    }

    public void connect() {
        scope.execute(() -> {
            try {
                getRelayManager().connect();
            } catch (Exception e) {
                Log.e(TAG, "connect failed: " + e.getMessage());
            }
        });
    }

    public void disconnect() {
        scope.execute(() -> {
            try {
                getRelayManager().disconnect();
            } catch (Exception e) {
                Log.e(TAG, "disconnect failed: " + e.getMessage());
            }
        });
    }

    public void subscribeGiftWraps(String pubkey, long sinceMs, String id, Consumer<NostrEvent> handler) {
        scope.execute(() -> {
            NostrFilter filter = NostrFilter.giftWrapsFor(pubkey, sinceMs);
            getRelayManager().subscribe(filter, id, handler::accept, null);
        });
    }

    public void subscribeGeohash(String geohash, long sinceMs, int limit, String id, Consumer<NostrEvent> handler) {
        scope.execute(() -> {
            NostrFilter filter = NostrFilter.geohashEphemeral(geohash, sinceMs, limit);
            getRelayManager().subscribeForGeohash(geohash, filter, id, handler::accept, false, 5);
        });
    }

    public void unsubscribe(String id) {
        scope.execute(() -> {
            try {
                getRelayManager().unsubscribe(id);
            } catch (Exception e) {
                // ignore
            }
        });
    }
}
