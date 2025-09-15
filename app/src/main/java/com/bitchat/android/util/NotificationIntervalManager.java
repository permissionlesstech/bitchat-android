package com.bitchat.android.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Gère les intervalles de temps pour les notifications afin d'éviter le spam.
 * Mémorise l'heure de la dernière notification réseau et les pairs récemment vus.
 */
public class NotificationIntervalManager {

    private long lastNetworkNotificationTime = 0L;
    private final Set<String> recentlySeenPeers = new HashSet<>();

    /**
     * Obtient l'heure de la dernière notification réseau.
     * @return Le timestamp (en millisecondes) de la dernière notification.
     */
    public long getLastNetworkNotificationTime() {
        return lastNetworkNotificationTime;
    }

    /**
     * Définit l'heure de la dernière notification réseau.
     * @param notificationTime Le timestamp (en millisecondes) de la notification.
     */
    public void setLastNetworkNotificationTime(long notificationTime) {
        this.lastNetworkNotificationTime = notificationTime;
    }

    /**
     * Obtient l'ensemble des identifiants des pairs vus récemment.
     * Cet ensemble peut être modifié par l'appelant.
     * @return Un Set mutable contenant les identifiants des pairs.
     */
    public Set<String> getRecentlySeenPeers() {
        return recentlySeenPeers;
    }
}
