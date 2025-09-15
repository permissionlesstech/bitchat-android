package com.bitchat.android.ui;

import android.content.Context;
import androidx.core.app.NotificationManagerCompat;
import com.bitchat.android.util.NotificationIntervalManager;

/**
 * Placeholder pour la classe NotificationManager.
 * La logique réelle de gestion des notifications sera implémentée plus tard.
 */
public class NotificationManager {

    public NotificationManager(Context context, NotificationManagerCompat compat, NotificationIntervalManager intervalManager) {
        // Constructeur
    }

    public void showPrivateMessageNotification(String senderPeerID, String senderNickname, String messageContent) {
        // Logique de notification
    }

    public void showActiveUserNotification(java.util.List<String> peers) {
        // Logique de notification
    }

    public void showMeshMentionNotification(String senderNickname, String messageContent, String senderPeerID) {
        // Logique de notification
    }

    public boolean getAppBackgroundState() {
        return false;
    }

    public String getCurrentPrivateChatPeer() {
        return null;
    }

    public void setAppBackgroundState(boolean isBackground) {}
    public void setCurrentPrivateChatPeer(String peerID) {}
    public void setCurrentGeohash(String geohash) {}
    public void clearNotificationsForSender(String peerID) {}
    public void clearNotificationsForGeohash(String geohash) {}
    public void clearMeshMentionNotifications() {}
    public void clearAllNotifications() {}
}
