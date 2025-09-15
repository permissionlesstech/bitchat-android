package com.bitchat.android.nostr;

import android.app.Application;
import android.util.Log;
import com.bitchat.android.model.BitchatMessage;
import com.bitchat.android.ui.ChatState;
import com.bitchat.android.ui.DataManager;
import com.bitchat.android.ui.MessageManager;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

public class GeohashMessageHandler {
    private static final String TAG = "GeohashMessageHandler";

    private final Application application;
    private final ChatState state;
    private final MessageManager messageManager;
    private final GeohashRepository repo;
    private final Executor scope;
    private final DataManager dataManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ArrayDeque<String> processedIds = new ArrayDeque<>();
    private final HashSet<String> seen = new HashSet<>();
    private final int max = 2000;

    public GeohashMessageHandler(
            Application application,
            ChatState state,
            MessageManager messageManager,
            GeohashRepository repo,
            Executor scope,
            DataManager dataManager
    ) {
        this.application = application;
        this.state = state;
        this.messageManager = messageManager;
        this.repo = repo;
        this.scope = scope;
        this.dataManager = dataManager;
    }

    private boolean dedupe(String id) {
        if (seen.contains(id)) return true;
        seen.add(id);
        processedIds.addLast(id);
        if (processedIds.size() > max) {
            String old = processedIds.removeFirst();
            seen.remove(old);
        }
        return false;
    }

    public void onEvent(NostrEvent event, String subscribedGeohash) {
        scope.execute(() -> {
            try {
                if (event.getKind() != 20000) return;
                String tagGeo = event.getTags().stream()
                        .filter(tag -> tag.size() >= 2 && tag.get(0).equals("g"))
                        .map(tag -> tag.get(1))
                        .findFirst().orElse(null);

                if (tagGeo == null || !tagGeo.equalsIgnoreCase(subscribedGeohash)) return;
                if (dedupe(event.getId())) return;

                PoWPreferenceManager.PoWSettings pow = PoWPreferenceManager.getCurrentSettings();
                if (pow.isEnabled() && pow.getDifficulty() > 0) {
                    if (!NostrProofOfWork.validateDifficulty(event, pow.getDifficulty())) return;
                }

                if (dataManager.isGeohashUserBlocked(event.getPubkey())) return;

                repo.updateParticipant(subscribedGeohash, event.getPubkey(), new Date(event.getCreatedAt() * 1000L));
                event.getTags().stream()
                        .filter(tag -> tag.size() >= 2 && tag.get(0).equals("n"))
                        .findFirst()
                        .ifPresent(tag -> repo.cacheNickname(event.getPubkey(), tag.get(1)));
                event.getTags().stream()
                        .filter(tag -> tag.size() >= 2 && tag.get(0).equals("t") && tag.get(1).equals("teleport"))
                        .findFirst()
                        .ifPresent(tag -> repo.markTeleported(event.getPubkey()));

                try {
                    GeohashAliasRegistry.put("nostr_" + event.getPubkey().substring(0, 16), event.getPubkey());
                } catch (Exception e) {
                    // Ignore
                }

                NostrIdentity my = NostrIdentityBridge.deriveIdentity(subscribedGeohash, application);
                if (my.getPublicKeyHex().equalsIgnoreCase(event.getPubkey())) return;

                boolean isTeleportPresence = event.getTags().stream()
                        .anyMatch(tag -> tag.size() >= 2 && tag.get(0).equals("t") && tag.get(1).equals("teleport"))
                        && event.getContent().trim().isEmpty();
                if (isTeleportPresence) return;

                String senderName = repo.displayNameForNostrPubkeyUI(event.getPubkey());
                boolean hasNonce = false;
                try {
                    hasNonce = NostrProofOfWork.hasNonce(event);
                } catch (Exception e) {
                    // Ignore
                }

                Integer powDifficulty = null;
                if (hasNonce) {
                    try {
                        int difficulty = NostrProofOfWork.calculateDifficulty(event.getId());
                        if (difficulty > 0) {
                            powDifficulty = difficulty;
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                }

                BitchatMessage msg = new BitchatMessage(
                        event.getId(),
                        senderName,
                        event.getContent(),
                        new Date(event.getCreatedAt() * 1000L),
                        false,
                        repo.displayNameForNostrPubkey(event.getPubkey()),
                        "nostr:" + event.getPubkey().substring(0, 8),
                        null,
                        "#" + subscribedGeohash,
                        powDifficulty
                );
                mainHandler.post(() -> messageManager.addChannelMessage("geo:" + subscribedGeohash, msg));
            } catch (Exception e) {
                Log.e(TAG, "onEvent error: " + e.getMessage());
            }
        });
    }
}
