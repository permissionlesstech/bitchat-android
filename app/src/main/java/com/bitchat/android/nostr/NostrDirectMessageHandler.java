package com.bitchat.android.nostr;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.bitchat.android.model.BitchatMessage;
import com.bitchat.android.ui.MessageManager;
import java.util.Date;
import java.util.concurrent.Executor;

public class NostrDirectMessageHandler {
    private static final String TAG = "NostrDirectMessageHandler";

    private final NostrIdentity identity;
    private final MessageManager messageManager;
    private final GeohashRepository repo;
    private final Executor scope;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public NostrDirectMessageHandler(
            NostrIdentity identity,
            MessageManager messageManager,
            GeohashRepository repo,
            Executor scope
    ) {
        this.identity = identity;
        this.messageManager = messageManager;
        this.repo = repo;
        this.scope = scope;
    }

    public void handle(NostrEvent event) {
        scope.execute(() -> {
            try {
                NostrEvent rumor = NostrProtocol.decryptRumor(event, identity);
                if (rumor == null) {
                    Log.w(TAG, "Failed to decrypt rumor from gift wrap: " + event.getId());
                    return;
                }

                String senderPubkey = rumor.getPubkey();
                String recipientPubkey = rumor.getTags().stream()
                        .filter(tag -> tag.size() >= 2 && "p".equals(tag.get(0)))
                        .map(tag -> tag.get(1))
                        .findFirst()
                        .orElse(null);

                if (recipientPubkey == null) {
                    Log.w(TAG, "Rumor has no recipient p-tag: " + rumor.getId());
                    return;
                }

                boolean isToMe = recipientPubkey.equalsIgnoreCase(identity.getPublicKeyHex());
                boolean isFromMe = senderPubkey.equalsIgnoreCase(identity.getPublicKeyHex());

                if (!isToMe && !isFromMe) {
                    Log.d(TAG, "Ignoring DM not for me: " + rumor.getId());
                    return;
                }

                String otherParty = isToMe ? senderPubkey : recipientPubkey;
                String conversationKey = "nostr_" + otherParty.substring(0, 16);

                String sourceGeohash = GeohashConversationRegistry.get(conversationKey);

                BitchatMessage msg = new BitchatMessage(
                        rumor.getId(),
                        isFromMe ? "me" : repo.displayNameForNostrPubkeyUI(otherParty),
                        rumor.getContent(),
                        new Date(rumor.getCreatedAt() * 1000L),
                        false,
                        isFromMe,
                        repo.displayNameForNostrPubkey(otherParty),
                        "nostr:" + otherParty.substring(0, 8),
                        null,
                        sourceGeohash != null ? "#" + sourceGeohash : null
                );

                mainHandler.post(() -> messageManager.addDirectMessage(conversationKey, msg));

            } catch (Exception e) {
                Log.e(TAG, "Error handling direct message: " + e.getMessage());
            }
        });
    }
}
