package com.bitchat.android.nostr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import kotlin.Pair;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
public class NostrRelayManager {

    private static final String TAG = "NostrRelayManager";
    private static final long INITIAL_BACKOFF_INTERVAL = 1000L;
    private static final long MAX_BACKOFF_INTERVAL = 300000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final long SUBSCRIPTION_VALIDATION_INTERVAL = 30000L;

    private static final List<String> DEFAULT_RELAYS = Arrays.asList(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr21.com"
    );

    private static final Set<String> pendingGiftWrapIDs = ConcurrentHashMap.newKeySet();

    public static final NostrRelayManager shared = new NostrRelayManager();

    public static NostrRelayManager getInstance(Context context) {
        return shared;
    }

    public static void registerPendingGiftWrap(String id) {
        pendingGiftWrapIDs.add(id);
    }

    public static List<String> defaultRelays() {
        return DEFAULT_RELAYS;
    }

    private final MutableLiveData<List<Relay>> _relays = new MutableLiveData<>();
    public final LiveData<List<Relay>> relays = _relays;

    private final MutableLiveData<Boolean> _isConnected = new MutableLiveData<>();
    public final LiveData<Boolean> isConnected = _isConnected;

    private final List<Relay> relaysList = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, WebSocket> connections = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, NostrMessageHandler> messageHandlers = new ConcurrentHashMap<>();
    private final Map<String, SubscriptionInfo> activeSubscriptions = new ConcurrentHashMap<>();
    private final NostrEventDeduplicator eventDeduplicator = NostrEventDeduplicator.getInstance();
    private final List<Pair<NostrEvent, List<String>>> messageQueue = Collections.synchronizedList(new ArrayList<>());
    private final Object messageQueueLock = new Object();

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ExecutorService subscriptionExecutor = Executors.newSingleThreadExecutor();
    private Future<?> subscriptionValidationFuture;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Gson gson = NostrRequest.createGson();
    private final Map<String, Set<String>> geohashToRelays = new ConcurrentHashMap<>();

    private NostrRelayManager() {
        try {
            List<String> defaultRelayUrls = Arrays.asList(
                    "wss://relay.damus.io",
                    "wss://relay.primal.net",
                    "wss://offchain.pub",
                    "wss://nostr21.com"
            );
            for (String url : defaultRelayUrls) {
                relaysList.add(new Relay(url));
            }
            _relays.postValue(new ArrayList<>(relaysList));
            updateConnectionStatus();
            Log.d(TAG, "✅ NostrRelayManager initialized with " + relaysList.size() + " default relays");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize NostrRelayManager: " + e.getMessage(), e);
            _relays.postValue(Collections.emptyList());
            _isConnected.postValue(false);
        }
    }

    public void ensureGeohashRelaysConnected(String geohash, int nRelays, boolean includeDefaults) {
        try {
            Set<String> nearest = new HashSet<>(RelayDirectory.closestRelaysForGeohash(geohash, nRelays));
            Set<String> selected;
            if (includeDefaults) {
                selected = new HashSet<>(nearest);
                selected.addAll(defaultRelays());
            } else {
                selected = nearest;
            }

            if (selected.isEmpty()) {
                Log.w(TAG, "No relays selected for geohash=" + geohash);
                return;
            }
            geohashToRelays.put(geohash, selected);
            Log.i(TAG, "🌐 Geohash " + geohash + " using " + selected.size() + " relays: " + String.join(", ", selected));
            ensureConnectionsFor(selected);
        } catch (Exception e) {
            Log.e(TAG, "Failed to ensure relays for " + geohash + ": " + e.getMessage());
        }
    }

    public List<String> getRelaysForGeohash(String geohash) {
        Set<String> relays = geohashToRelays.get(geohash);
        return relays != null ? new ArrayList<>(relays) : Collections.emptyList();
    }

    public String subscribeForGeohash(String geohash, NostrFilter filter, String id, NostrMessageHandler handler, boolean includeDefaults, int nRelays) {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults);
        List<String> relayUrls = getRelaysForGeohash(geohash);
        Log.d(TAG, "📡 Subscribing id=" + id + " for geohash=" + geohash + " on " + relayUrls.size() + " relays");
        String subscriptionId = subscribe(filter, id, handler, relayUrls);
        activeSubscriptions.computeIfPresent(subscriptionId, (k, v) -> {
            v.setOriginGeohash(geohash);
            return v;
        });
        return subscriptionId;
    }

    public void sendEventToGeohash(NostrEvent event, String geohash, boolean includeDefaults, int nRelays) {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults);
        List<String> relayUrls = getRelaysForGeohash(geohash);
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No target relays to send event for geohash=" + geohash + "; falling back to defaults");
            sendEvent(event, defaultRelays());
            return;
        }
        Log.v(TAG, "📤 Sending event kind=" + event.getKind() + " to " + relayUrls.size() + " relays for geohash=" + geohash);
        sendEvent(event, relayUrls);
    }

    private void ensureConnectionsFor(Set<String> relayUrls) {
        for (String url : relayUrls) {
            if (relaysList.stream().noneMatch(r -> r.getUrl().equals(url))) {
                relaysList.add(new Relay(url));
            }
        }
        updateRelaysList();

        executor.submit(() -> {
            for (String relayUrl : relayUrls) {
                executor.submit(() -> {
                    if (!connections.containsKey(relayUrl)) {
                        connectToRelay(relayUrl);
                    }
                });
            }
        });
    }

    public void connect() {
        Log.d(TAG, "🌐 Connecting to " + relaysList.size() + " Nostr relays");
        executor.submit(() -> {
            for (Relay relay : relaysList) {
                executor.submit(() -> connectToRelay(relay.getUrl()));
            }
        });
        startSubscriptionValidation();
    }

    public void disconnect() {
        Log.d(TAG, "Disconnecting from all relays");
        stopSubscriptionValidation();
        for (WebSocket webSocket : connections.values()) {
            webSocket.close(1000, "Manual disconnect");
        }
        connections.clear();
        subscriptions.clear();
        updateConnectionStatus();
    }

    public void sendEvent(NostrEvent event, List<String> relayUrls) {
        List<String> targetRelays = relayUrls != null ? relayUrls : relaysList.stream().map(Relay::getUrl).collect(Collectors.toList());
        synchronized (messageQueueLock) {
            messageQueue.add(new Pair<>(event, targetRelays));
        }

        executor.submit(() -> {
            for (String relayUrl : targetRelays) {
                WebSocket webSocket = connections.get(relayUrl);
                if (webSocket != null) {
                    sendToRelay(event, webSocket, relayUrl);
                }
            }
        });
    }

    public String subscribe(NostrFilter filter, String id, NostrMessageHandler handler, List<String> targetRelayUrls) {
        String subscriptionId = id != null ? id : generateSubscriptionId();
        SubscriptionInfo subscriptionInfo = new SubscriptionInfo(
                subscriptionId,
                filter,
                handler,
                targetRelayUrls != null ? new HashSet<>(targetRelayUrls) : null
        );
        activeSubscriptions.put(subscriptionId, subscriptionInfo);
        messageHandlers.put(subscriptionId, handler);
        Log.d(TAG, "📡 Subscribing to Nostr filter id=" + subscriptionId + " " + filter.getDebugDescription());
        sendSubscriptionToRelays(subscriptionInfo);
        return subscriptionId;
    }

    private void sendSubscriptionToRelays(SubscriptionInfo subscriptionInfo) {
        NostrRequest.Subscribe request = new NostrRequest.Subscribe(subscriptionInfo.getId(), Collections.singletonList(subscriptionInfo.getFilter()));
        String message = gson.toJson(request, NostrRequest.class);
        Log.v(TAG, "🔍 DEBUG: Serialized subscription message: " + message);

        executor.submit(() -> {
            List<String> targetRelays = subscriptionInfo.getTargetRelayUrls() != null ? new ArrayList<>(subscriptionInfo.getTargetRelayUrls()) : new ArrayList<>(connections.keySet());
            for (String relayUrl : targetRelays) {
                WebSocket webSocket = connections.get(relayUrl);
                if (webSocket != null) {
                    try {
                        boolean success = webSocket.send(message);
                        if (success) {
                            subscriptions.compute(relayUrl, (k, v) -> {
                                if (v == null) v = new HashSet<>();
                                v.add(subscriptionInfo.getId());
                                return v;
                            });
                            Log.v(TAG, "✅ Subscription '" + subscriptionInfo.getId() + "' sent to relay: " + relayUrl);
                        } else {
                            Log.w(TAG, "❌ Failed to send subscription to " + relayUrl + ": WebSocket send failed");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Failed to send subscription to " + relayUrl + ": " + e.getMessage());
                    }
                } else {
                    Log.v(TAG, "⏳ Relay " + relayUrl + " not connected, subscription will be sent on reconnection");
                }
            }
            if (connections.isEmpty()) {
                Log.w(TAG, "⚠️ No relay connections available for subscription, will retry on reconnection");
            }
        });
    }

    public void unsubscribe(String id) {
        SubscriptionInfo subscriptionInfo = activeSubscriptions.remove(id);
        messageHandlers.remove(id);

        if (subscriptionInfo == null) {
            Log.w(TAG, "⚠️ Attempted to unsubscribe from unknown subscription: " + id);
            return;
        }

        Log.d(TAG, "🚫 Unsubscribing from subscription: " + id);
        NostrRequest.Close request = new NostrRequest.Close(id);
        String message = gson.toJson(request, NostrRequest.class);

        executor.submit(() -> {
            for (Map.Entry<String, WebSocket> entry : connections.entrySet()) {
                String relayUrl = entry.getKey();
                WebSocket webSocket = entry.getValue();
                Set<String> currentSubs = subscriptions.get(relayUrl);
                if (currentSubs != null && currentSubs.contains(id)) {
                    try {
                        webSocket.send(message);
                        currentSubs.remove(id);
                        Log.v(TAG, "Unsubscribed '" + id + "' from relay: " + relayUrl);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to unsubscribe from " + relayUrl + ": " + e.getMessage());
                    }
                }
            }
        });
    }

    public void retryConnection(String relayUrl) {
        Relay relay = relaysList.stream().filter(r -> r.getUrl().equals(relayUrl)).findFirst().orElse(null);
        if (relay == null) return;

        relay.setReconnectAttempts(0);
        relay.setNextReconnectTime(null);

        WebSocket webSocket = connections.remove(relayUrl);
        if (webSocket != null) {
            webSocket.close(1000, "Manual retry");
        }

        executor.submit(() -> connectToRelay(relayUrl));
    }

    public void resetAllConnections() {
        disconnect();
        for (Relay relay : relaysList) {
            relay.setReconnectAttempts(0);
            relay.setNextReconnectTime(null);
            relay.setLastError(null);
        }
        connect();
    }

    public void reestablishAllSubscriptions() {
        Log.d(TAG, "🔄 Force re-establishing all " + activeSubscriptions.size() + " active subscriptions");
        executor.submit(() -> {
            for (Map.Entry<String, WebSocket> entry : connections.entrySet()) {
                restoreSubscriptionsForRelay(entry.getKey(), entry.getValue());
            }
        });
    }

    public void clearAllSubscriptions() {
        try {
            activeSubscriptions.clear();
            messageHandlers.clear();
            subscriptions.clear();
            geohashToRelays.clear();
            synchronized (messageQueueLock) {
                messageQueue.clear();
            }
            Log.i(TAG, "🧹 Cleared all Nostr subscriptions and routing caches");
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear subscriptions: " + e.getMessage());
        }
    }

    public List<Relay> getRelayStatuses() {
        return new ArrayList<>(relaysList);
    }

    public DeduplicationStats getDeduplicationStats() {
        return eventDeduplicator.getStats();
    }

    public void clearDeduplicationCache() {
        eventDeduplicator.clear();
        Log.i(TAG, "🧹 Cleared event deduplication cache");
    }

    public int getActiveSubscriptionCount() {
        return activeSubscriptions.size();
    }

    public Map<String, SubscriptionInfo> getActiveSubscriptions() {
        return new ConcurrentHashMap<>(activeSubscriptions);
    }

    public SubscriptionConsistencyReport validateSubscriptionConsistency() {
        Set<String> expectedSubs = activeSubscriptions.keySet();
        Map<String, Set<String>> actualSubsByRelay = new ConcurrentHashMap<>(subscriptions);
        List<String> inconsistencies = new ArrayList<>();

        for (String relayUrl : connections.keySet()) {
            Set<String> actualSubs = actualSubsByRelay.getOrDefault(relayUrl, Collections.emptySet());
            Set<String> expectedForRelay = expectedSubs.stream()
                    .filter(subId -> {
                        SubscriptionInfo subInfo = activeSubscriptions.get(subId);
                        return subInfo != null && (subInfo.getTargetRelayUrls() == null || subInfo.getTargetRelayUrls().contains(relayUrl));
                    })
                    .collect(Collectors.toSet());

            Set<String> missing = new HashSet<>(expectedForRelay);
            missing.removeAll(actualSubs);
            Set<String> extra = new HashSet<>(actualSubs);
            extra.removeAll(expectedForRelay);

            if (!missing.isEmpty()) {
                inconsistencies.add("Relay " + relayUrl + " missing subscriptions: " + missing);
            }
            if (!extra.isEmpty()) {
                inconsistencies.add("Relay " + relayUrl + " has extra subscriptions: " + extra);
            }
        }

        return new SubscriptionConsistencyReport(
                inconsistencies.isEmpty(),
                inconsistencies,
                activeSubscriptions.size(),
                connections.size()
        );
    }

    private void startSubscriptionValidation() {
        stopSubscriptionValidation();
        subscriptionValidationFuture = subscriptionExecutor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(SUBSCRIPTION_VALIDATION_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                try {
                    SubscriptionConsistencyReport report = validateSubscriptionConsistency();
                    if (!report.isConsistent() && report.getConnectedRelayCount() > 0) {
                        Log.w(TAG, "⚠️ Subscription inconsistencies detected: " + report.getInconsistencies());
                        for (Map.Entry<String, WebSocket> entry : connections.entrySet()) {
                            String relayUrl = entry.getKey();
                            WebSocket webSocket = entry.getValue();
                            Set<String> currentSubs = subscriptions.getOrDefault(relayUrl, Collections.emptySet());
                            Set<String> expectedSubs = activeSubscriptions.keySet().stream()
                                    .filter(subId -> {
                                        SubscriptionInfo subInfo = activeSubscriptions.get(subId);
                                        return subInfo != null && (subInfo.getTargetRelayUrls() == null || subInfo.getTargetRelayUrls().contains(relayUrl));
                                    })
                                    .collect(Collectors.toSet());

                            Set<String> missingSubs = new HashSet<>(expectedSubs);
                            missingSubs.removeAll(currentSubs);
                            if (!missingSubs.isEmpty()) {
                                Log.i(TAG, "🔧 Auto-repairing " + missingSubs.size() + " missing subscriptions for " + relayUrl);
                                restoreSubscriptionsForRelay(relayUrl, webSocket);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during subscription validation: " + e.getMessage());
                }
            }
        });
        Log.d(TAG, "🔄 Started periodic subscription validation (" + (SUBSCRIPTION_VALIDATION_INTERVAL / 1000) + "s interval)");
    }

    private void stopSubscriptionValidation() {
        if (subscriptionValidationFuture != null) {
            subscriptionValidationFuture.cancel(true);
            subscriptionValidationFuture = null;
        }
        Log.v(TAG, "⏹️ Stopped subscription validation");
    }

    private void connectToRelay(String urlString) {
        if (connections.containsKey(urlString)) {
            return;
        }
        Log.v(TAG, "Attempting to connect to Nostr relay: " + urlString);
        try {
            Request request = new Request.Builder().url(urlString).build();
            OkHttpClient client = com.bitchat.android.net.OkHttpProvider.webSocketClient();
            WebSocket webSocket = client.newWebSocket(request, new RelayWebSocketListener(urlString));
            connections.put(urlString, webSocket);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to create WebSocket connection to " + urlString + ": " + e.getMessage());
            handleDisconnection(urlString, e);
        }
    }

    private void sendToRelay(NostrEvent event, WebSocket webSocket, String relayUrl) {
        try {
            NostrRequest.Event request = new NostrRequest.Event(event);
            String message = gson.toJson(request, NostrRequest.class);
            Log.v(TAG, "📤 Sending Nostr event (kind: " + event.getKind() + ") to relay: " + relayUrl);
            boolean success = webSocket.send(message);
            if (success) {
                Relay relay = relaysList.stream().filter(r -> r.getUrl().equals(relayUrl)).findFirst().orElse(null);
                if (relay != null) {
                    relay.setMessagesSent(relay.getMessagesSent() + 1);
                    updateRelaysList();
                }
            } else {
                Log.e(TAG, "❌ Failed to send event to " + relayUrl + ": WebSocket send failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to send event to " + relayUrl + ": " + e.getMessage());
        }
    }

    private void handleMessage(String message, String relayUrl) {
        try {
            JsonArray jsonArray = JsonParser.parseString(message).getAsJsonArray();
            NostrResponse response = NostrResponse.fromJsonArray(jsonArray);

            if (response instanceof NostrResponse.Event) {
                NostrResponse.Event eventResponse = (NostrResponse.Event) response;
                Relay relay = relaysList.stream().filter(r -> r.getUrl().equals(relayUrl)).findFirst().orElse(null);
                if (relay != null) {
                    relay.setMessagesReceived(relay.getMessagesReceived() + 1);
                    updateRelaysList();
                }

                SubscriptionInfo subInfo = activeSubscriptions.get(eventResponse.getSubscriptionId());
                if (subInfo != null) {
                    try {
                        if (!subInfo.getFilter().matches(eventResponse.getEvent())) {
                            Log.v(TAG, "🚫 Dropping event " + eventResponse.getEvent().getId().substring(0, 16) + "... not matching filter for sub=" + eventResponse.getSubscriptionId());
                            return;
                        }
                    } catch (Exception e) {
                        // ignore, treat as match
                    }

                    boolean wasProcessed = eventDeduplicator.processEvent(eventResponse.getEvent(), event -> {
                        if (event.getKind() != NostrKind.GIFT_WRAP) {
                            String originGeo = subInfo.getOriginGeohash();
                            if (originGeo != null) {
                                Log.v(TAG, "📥 Processing event (kind=" + event.getKind() + ") from relay=" + relayUrl + " geo=" + originGeo + " sub=" + eventResponse.getSubscriptionId());
                            } else {
                                Log.v(TAG, "📥 Processing event (kind=" + event.getKind() + ") from relay=" + relayUrl + " sub=" + eventResponse.getSubscriptionId());
                            }
                        }

                        NostrMessageHandler handler = messageHandlers.get(eventResponse.getSubscriptionId());
                        if (handler != null) {
                            mainHandler.post(() -> handler.handle(event));
                        } else {
                            Log.w(TAG, "⚠️ No handler for subscription " + eventResponse.getSubscriptionId());
                        }
                    });
                     if (!wasProcessed) {
                        //Log.v(TAG, "🔄 Duplicate event ${response.event.id.take(16)}... from relay: $relayUrl")
                    }
                }

            } else if (response instanceof NostrResponse.EndOfStoredEvents) {
                Log.v(TAG, "End of stored events for subscription: " + ((NostrResponse.EndOfStoredEvents) response).getSubscriptionId());

            } else if (response instanceof NostrResponse.Ok) {
                NostrResponse.Ok okResponse = (NostrResponse.Ok) response;
                boolean wasGiftWrap = pendingGiftWrapIDs.remove(okResponse.getEventId());
                if (okResponse.isAccepted()) {
                    Log.d(TAG, "✅ Event accepted id=" + okResponse.getEventId().substring(0, 16) + "... by relay: " + relayUrl);
                } else {
                    int level = wasGiftWrap ? Log.WARN : Log.ERROR;
                    Log.println(level, TAG, "📮 Event " + okResponse.getEventId().substring(0, 16) + "... rejected by relay: " + (okResponse.getMessage() != null ? okResponse.getMessage() : "no reason"));
                }

            } else if (response instanceof NostrResponse.Notice) {
                Log.i(TAG, "📢 Notice from " + relayUrl + ": " + ((NostrResponse.Notice) response).getMessage());

            } else if (response instanceof NostrResponse.Unknown) {
                Log.v(TAG, "Unknown message type from " + relayUrl + ": " + ((NostrResponse.Unknown) response).getRaw());
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse message from " + relayUrl + ": " + e.getMessage());
        }
    }

    private void handleDisconnection(String relayUrl, Throwable error) {
        connections.remove(relayUrl);
        updateRelayStatus(relayUrl, false, error);

        String errorMessage = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        if (errorMessage.contains("hostname could not be found") ||
                errorMessage.contains("dns") ||
                errorMessage.contains("unable to resolve host")) {
            Relay relay = relaysList.stream().filter(r -> r.getUrl().equals(relayUrl)).findFirst().orElse(null);
            if (relay != null && relay.getLastError() == null) {
                Log.w(TAG, "Nostr relay DNS failure for " + relayUrl + " - not retrying");
            }
            return;
        }

        Relay relay = relaysList.stream().filter(r -> r.getUrl().equals(relayUrl)).findFirst().orElse(null);
        if (relay == null) return;
        relay.setReconnectAttempts(relay.getReconnectAttempts() + 1);

        if (relay.getReconnectAttempts() >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts (" + MAX_RECONNECT_ATTEMPTS + ") reached for " + relayUrl);
            return;
        }

        long backoffInterval = (long) Math.min(
                INITIAL_BACKOFF_INTERVAL * Math.pow(BACKOFF_MULTIPLIER, relay.getReconnectAttempts() - 1),
                MAX_BACKOFF_INTERVAL
        );
        relay.setNextReconnectTime(System.currentTimeMillis() + backoffInterval);
        Log.d(TAG, "Scheduling reconnection to " + relayUrl + " in " + (backoffInterval / 1000) + "s (attempt " + relay.getReconnectAttempts() + ")");

        executor.submit(() -> {
            try {
                Thread.sleep(backoffInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            connectToRelay(relayUrl);
        });
    }

    private void updateRelayStatus(String url, boolean isConnected, Throwable error) {
        Relay relay = relaysList.stream().filter(r -> r.getUrl().equals(url)).findFirst().orElse(null);
        if (relay == null) return;

        relay.setConnected(isConnected);
        relay.setLastError(error);

        if (isConnected) {
            relay.setLastConnectedAt(System.currentTimeMillis());
            relay.setReconnectAttempts(0);
            relay.setNextReconnectTime(null);
        } else {
            relay.setLastDisconnectedAt(System.currentTimeMillis());
        }

        updateRelaysList();
        updateConnectionStatus();
    }

    private void updateRelaysList() {
        _relays.postValue(new ArrayList<>(relaysList));
    }

    private void updateConnectionStatus() {
        boolean connected = relaysList.stream().anyMatch(Relay::isConnected);
        _isConnected.postValue(connected);
    }

    private String generateSubscriptionId() {
        return "sub-" + System.currentTimeMillis() + "-" + ((int) (Math.random() * 1000));
    }

    private void restoreSubscriptionsForRelay(String relayUrl, WebSocket webSocket) {
        List<SubscriptionInfo> subscriptionsToRestore = activeSubscriptions.values().stream()
                .filter(subInfo -> subInfo.getTargetRelayUrls() == null || subInfo.getTargetRelayUrls().contains(relayUrl))
                .collect(Collectors.toList());

        if (subscriptionsToRestore.isEmpty()) {
            Log.v(TAG, "🔄 No subscriptions to restore for relay: " + relayUrl);
            return;
        }

        Log.d(TAG, "🔄 Restoring " + subscriptionsToRestore.size() + " subscriptions for relay: " + relayUrl);

        for (SubscriptionInfo subscriptionInfo : subscriptionsToRestore) {
            try {
                NostrRequest.Subscribe request = new NostrRequest.Subscribe(subscriptionInfo.getId(), Collections.singletonList(subscriptionInfo.getFilter()));
                String message = gson.toJson(request, NostrRequest.class);
                boolean success = webSocket.send(message);
                if (success) {
                    subscriptions.compute(relayUrl, (k, v) -> {
                        if (v == null) v = new HashSet<>();
                        v.add(subscriptionInfo.getId());
                        return v;
                    });
                    Log.v(TAG, "✅ Restored subscription '" + subscriptionInfo.getId() + "' to relay: " + relayUrl);
                } else {
                    Log.w(TAG, "❌ Failed to restore subscription '" + subscriptionInfo.getId() + "' to " + relayUrl + ": WebSocket send failed");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to restore subscription '" + subscriptionInfo.getId() + "' to " + relayUrl + ": " + e.getMessage());
            }
        }
    }

    private class RelayWebSocketListener extends WebSocketListener {
        private final String relayUrl;

        public RelayWebSocketListener(String relayUrl) {
            this.relayUrl = relayUrl;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "✅ Connected to Nostr relay: " + relayUrl);
            updateRelayStatus(relayUrl, true, null);
            restoreSubscriptionsForRelay(relayUrl, webSocket);

            synchronized (messageQueueLock) {
                for (Pair<NostrEvent, List<String>> pair : messageQueue) {
                    if (pair.getSecond().contains(relayUrl)) {
                        sendToRelay(pair.getFirst(), webSocket, relayUrl);
                    }
                }
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text, relayUrl);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "WebSocket closing for " + relayUrl + ": " + code + " " + reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "WebSocket closed for " + relayUrl + ": " + code + " " + reason);
            handleDisconnection(relayUrl, new Exception("WebSocket closed: " + code + " " + reason));
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            Log.e(TAG, "❌ WebSocket failure for " + relayUrl + ": " + t.getMessage());
            handleDisconnection(relayUrl, t);
        }
    }

    public static class Relay {
        private final String url;
        private boolean isConnected;
        private Throwable lastError;
        private Long lastConnectedAt;
        private int messagesSent;
        private int messagesReceived;
        private int reconnectAttempts;
        private Long lastDisconnectedAt;
        private Long nextReconnectTime;

        public Relay(String url) {
            this.url = url;
        }

        public String getUrl() { return url; }
        public boolean isConnected() { return isConnected; }
        public void setConnected(boolean connected) { isConnected = connected; }
        public Throwable getLastError() { return lastError; }
        public void setLastError(Throwable lastError) { this.lastError = lastError; }
        public Long getLastConnectedAt() { return lastConnectedAt; }
        public void setLastConnectedAt(Long lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }
        public int getMessagesSent() { return messagesSent; }
        public void setMessagesSent(int messagesSent) { this.messagesSent = messagesSent; }
        public int getMessagesReceived() { return messagesReceived; }
        public void setMessagesReceived(int messagesReceived) { this.messagesReceived = messagesReceived; }
        public int getReconnectAttempts() { return reconnectAttempts; }
        public void setReconnectAttempts(int reconnectAttempts) { this.reconnectAttempts = reconnectAttempts; }
        public Long getLastDisconnectedAt() { return lastDisconnectedAt; }
        public void setLastDisconnectedAt(Long lastDisconnectedAt) { this.lastDisconnectedAt = lastDisconnectedAt; }
        public Long getNextReconnectTime() { return nextReconnectTime; }
        public void setNextReconnectTime(Long nextReconnectTime) { this.nextReconnectTime = nextReconnectTime; }
    }

    public static class SubscriptionInfo {
        private final String id;
        private final NostrFilter filter;
        private final NostrMessageHandler handler;
        private final Set<String> targetRelayUrls;
        private final long createdAt = System.currentTimeMillis();
        private String originGeohash;

        public SubscriptionInfo(String id, NostrFilter filter, NostrMessageHandler handler, Set<String> targetRelayUrls) {
            this.id = id;
            this.filter = filter;
            this.handler = handler;
            this.targetRelayUrls = targetRelayUrls;
        }

        public String getId() { return id; }
        public NostrFilter getFilter() { return filter; }
        public NostrMessageHandler getHandler() { return handler; }
        public Set<String> getTargetRelayUrls() { return targetRelayUrls; }
        public long getCreatedAt() { return createdAt; }
        public String getOriginGeohash() { return originGeohash; }
        public void setOriginGeohash(String originGeohash) { this.originGeohash = originGeohash; }
    }

    public static class SubscriptionConsistencyReport {
        private final boolean isConsistent;
        private final List<String> inconsistencies;
        private final int totalActiveSubscriptions;
        private final int connectedRelayCount;

        public SubscriptionConsistencyReport(boolean isConsistent, List<String> inconsistencies, int totalActiveSubscriptions, int connectedRelayCount) {
            this.isConsistent = isConsistent;
            this.inconsistencies = inconsistencies;
            this.totalActiveSubscriptions = totalActiveSubscriptions;
            this.connectedRelayCount = connectedRelayCount;
        }

        public boolean isConsistent() { return isConsistent; }
        public List<String> getInconsistencies() { return inconsistencies; }
        public int getTotalActiveSubscriptions() { return totalActiveSubscriptions; }
        public int getConnectedRelayCount() { return connectedRelayCount; }
    }

    @FunctionalInterface
    public interface NostrMessageHandler {
        void handle(NostrEvent event);
    }
}
