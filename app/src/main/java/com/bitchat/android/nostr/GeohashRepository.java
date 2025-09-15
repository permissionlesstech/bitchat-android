package com.bitchat.android.nostr;

import android.app.Application;
import androidx.lifecycle.LiveData;
import com.bitchat.android.ui.ChatState;
import com.bitchat.android.ui.DataManager;
import com.bitchat.android.ui.GeoPerson;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GeohashRepository {
    private static final String TAG = "GeohashRepository";

    private final Application application;
    private final ChatState state;
    private final DataManager dataManager;

    private final Map<String, Map<String, Date>> geohashParticipants = new HashMap<>();
    private final Map<String, String> geoNicknames = new HashMap<>();
    private final Map<String, String> conversationGeohash = new HashMap<>();
    private final Map<String, String> nostrKeyMapping = new HashMap<>();
    private String currentGeohash;

    public GeohashRepository(Application application, ChatState state, DataManager dataManager) {
        this.application = application;
        this.state = state;
        this.dataManager = dataManager;
    }

    public void setConversationGeohash(String convKey, String geohash) {
        if (geohash != null && !geohash.isEmpty()) {
            conversationGeohash.put(convKey, geohash);
        }
    }

    public String getConversationGeohash(String convKey) {
        return conversationGeohash.get(convKey);
    }

    public String findPubkeyByNickname(String targetNickname) {
        for (Map.Entry<String, String> entry : geoNicknames.entrySet()) {
            String nickname = entry.getValue();
            String base = nickname.split("#")[0];
            if (base.equals(targetNickname)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public void setCurrentGeohash(String geo) {
        currentGeohash = geo;
    }

    public String getCurrentGeohash() {
        return currentGeohash;
    }

    public void clearAll() {
        geohashParticipants.clear();
        geoNicknames.clear();
        nostrKeyMapping.clear();
        state.setGeohashPeople(java.util.Collections.emptyList());
        state.setTeleportedGeo(java.util.Collections.emptySet());
        state.setGeohashParticipantCounts(java.util.Collections.emptyMap());
        currentGeohash = null;
    }

    public void cacheNickname(String pubkeyHex, String nickname) {
        String lower = pubkeyHex.toLowerCase();
        String previous = geoNicknames.get(lower);
        geoNicknames.put(lower, nickname);
        if (!nickname.equals(previous) && currentGeohash != null) {
            refreshGeohashPeople();
        }
    }

    public String getCachedNickname(String pubkeyHex) {
        return geoNicknames.get(pubkeyHex.toLowerCase());
    }

    public void markTeleported(String pubkeyHex) {
        java.util.Set<String> set = new java.util.HashSet<>(state.getTeleportedGeoValue());
        String key = pubkeyHex.toLowerCase();
        if (!set.contains(key)) {
            set.add(key);
            state.postTeleportedGeo(set);
        }
    }

    public boolean isPersonTeleported(String pubkeyHex) {
        return state.getTeleportedGeoValue().contains(pubkeyHex.toLowerCase());
    }

    public void updateParticipant(String geohash, String participantId, Date lastSeen) {
        Map<String, Date> participants = geohashParticipants.computeIfAbsent(geohash, k -> new HashMap<>());
        participants.put(participantId, lastSeen);
        if (geohash.equals(currentGeohash)) {
            refreshGeohashPeople();
        }
        updateReactiveParticipantCounts();
    }

    public int geohashParticipantCount(String geohash) {
        long cutoff = System.currentTimeMillis() - 5 * 60 * 1000;
        Map<String, Date> participants = geohashParticipants.get(geohash);
        if (participants == null) {
            return 0;
        }
        participants.entrySet().removeIf(entry -> entry.getValue().getTime() < cutoff);
        return (int) participants.keySet().stream().filter(key -> !dataManager.isGeohashUserBlocked(key)).count();
    }

    public void refreshGeohashPeople() {
        if (currentGeohash == null) {
            state.postGeohashPeople(java.util.Collections.emptyList());
            return;
        }
        long cutoff = System.currentTimeMillis() - 5 * 60 * 1000;
        Map<String, Date> participants = geohashParticipants.computeIfAbsent(currentGeohash, k -> new HashMap<>());
        participants.entrySet().removeIf(entry -> entry.getValue().getTime() < cutoff);

        List<GeoPerson> people = participants.entrySet().stream()
                .filter(entry -> !dataManager.isGeohashUserBlocked(entry.getKey()))
                .map(entry -> {
                    String pubkeyHex = entry.getKey();
                    Date lastSeen = entry.getValue();
                    String base;
                    try {
                        NostrIdentity myIdentity = NostrIdentityBridge.deriveIdentity(currentGeohash, application);
                        String myHex = myIdentity != null ? myIdentity.getPublicKeyHex() : null;
                        if (myHex != null && myHex.equalsIgnoreCase(pubkeyHex)) {
                            base = state.getNicknameValue() != null ? state.getNicknameValue() : "anon";
                        } else {
                            base = getCachedNickname(pubkeyHex);
                            if (base == null) base = "anon";
                        }
                    } catch (Exception e) {
                        base = getCachedNickname(pubkeyHex);
                        if (base == null) base = "anon";
                    }
                    return new GeoPerson(pubkeyHex.toLowerCase(), base, lastSeen);
                })
                .sorted((p1, p2) -> p2.getLastSeen().compareTo(p1.getLastSeen()))
                .collect(Collectors.toList());
        state.postGeohashPeople(people);
    }

    public void updateReactiveParticipantCounts() {
        long cutoff = System.currentTimeMillis() - 5 * 60 * 1000;
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Map<String, Date>> entry : geohashParticipants.entrySet()) {
            String gh = entry.getKey();
            Map<String, Date> participants = entry.getValue();
            int active = (int) participants.entrySet().stream()
                    .filter(e -> !dataManager.isGeohashUserBlocked(e.getKey()) && e.getValue().getTime() >= cutoff)
                    .count();
            counts.put(gh, active);
        }
        state.postGeohashParticipantCounts(counts);
    }

    public void putNostrKeyMapping(String tempKeyOrPeer, String pubkeyHex) {
        nostrKeyMapping.put(tempKeyOrPeer, pubkeyHex);
    }

    public Map<String, String> getNostrKeyMapping() {
        return new HashMap<>(nostrKeyMapping);
    }

    public String displayNameForNostrPubkey(String pubkeyHex) {
        String suffix = pubkeyHex.substring(pubkeyHex.length() - 4);
        String lower = pubkeyHex.toLowerCase();
        if (currentGeohash != null) {
            try {
                NostrIdentity my = NostrIdentityBridge.deriveIdentity(currentGeohash, application);
                if (my != null && my.getPublicKeyHex().equalsIgnoreCase(lower)) {
                    return (state.getNicknameValue() != null ? state.getNicknameValue() : "anon") + "#" + suffix;
                }
            } catch (Exception e) {
                // ignore
            }
        }
        String nick = geoNicknames.getOrDefault(lower, "anon");
        return nick + "#" + suffix;
    }

    public String displayNameForNostrPubkeyUI(String pubkeyHex) {
        String lower = pubkeyHex.toLowerCase();
        String suffix = pubkeyHex.substring(pubkeyHex.length() - 4);
        String base;
        try {
            if (currentGeohash != null) {
                NostrIdentity my = NostrIdentityBridge.deriveIdentity(currentGeohash, application);
                if (my != null && my.getPublicKeyHex().equalsIgnoreCase(lower)) {
                    base = state.getNicknameValue() != null ? state.getNicknameValue() : "anon";
                } else {
                    base = geoNicknames.getOrDefault(lower, "anon");
                }
            } else {
                base = geoNicknames.getOrDefault(lower, "anon");
            }
        } catch (Exception e) {
            base = geoNicknames.getOrDefault(lower, "anon");
        }

        if (currentGeohash == null) return base;

        try {
            long cutoff = System.currentTimeMillis() - 5 * 60 * 1000;
            Map<String, Date> participants = geohashParticipants.getOrDefault(currentGeohash, java.util.Collections.emptyMap());
            long count = participants.entrySet().stream()
                .filter(entry -> !dataManager.isGeohashUserBlocked(entry.getKey()))
                .filter(entry -> entry.getValue().getTime() >= cutoff)
                .map(entry -> {
                    String name = entry.getKey().equalsIgnoreCase(lower) ? base : geoNicknames.getOrDefault(entry.getKey().toLowerCase(), "anon");
                    return name;
                })
                .filter(name -> name.equalsIgnoreCase(base))
                .count();

            if (!participants.containsKey(lower)) {
                count++;
            }

            return count > 1 ? base + "#" + suffix : base;
        } catch (Exception e) {
            return base;
        }
    }

    public String displayNameForGeohashConversation(String pubkeyHex, String sourceGeohash) {
        String lower = pubkeyHex.toLowerCase();
        String suffix = pubkeyHex.substring(pubkeyHex.length() - 4);
        String base = geoNicknames.getOrDefault(lower, "anon");
        try {
            long cutoff = System.currentTimeMillis() - 5 * 60 * 1000;
            Map<String, Date> participants = geohashParticipants.getOrDefault(sourceGeohash, java.util.Collections.emptyMap());
             long count = participants.entrySet().stream()
                .filter(entry -> !dataManager.isGeohashUserBlocked(entry.getKey()))
                .filter(entry -> entry.getValue().getTime() >= cutoff)
                .map(entry -> {
                    String name = entry.getKey().equalsIgnoreCase(lower) ? base : geoNicknames.getOrDefault(entry.getKey().toLowerCase(), "anon");
                    return name;
                })
                .filter(name -> name.equalsIgnoreCase(base))
                .count();

            if (!participants.containsKey(lower)) {
                count++;
            }
            return count > 1 ? base + "#" + suffix : base;
        } catch (Exception e) {
            return base;
        }
    }
}
