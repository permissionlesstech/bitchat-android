package com.bitchat.android.nostr;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Représente les messages de réponse du protocole Nostr.
 */
public abstract class NostrResponse {
    private NostrResponse() {}

    public static final class Event extends NostrResponse {
        public final String subscriptionId;
        public final NostrEvent event;
        public Event(String subscriptionId, NostrEvent event) { this.subscriptionId = subscriptionId; this.event = event; }
    }

    public static final class EndOfStoredEvents extends NostrResponse {
        public final String subscriptionId;
        public EndOfStoredEvents(String subscriptionId) { this.subscriptionId = subscriptionId; }
    }

    public static final class Ok extends NostrResponse {
        public final String eventId;
        public final boolean accepted;
        public final String message;
        public Ok(String eventId, boolean accepted, String message) { this.eventId = eventId; this.accepted = accepted; this.message = message; }
    }

    public static final class Notice extends NostrResponse {
        public final String message;
        public Notice(String message) { this.message = message; }
    }

    public static final class Unknown extends NostrResponse {
        public final String raw;
        public Unknown(String raw) { this.raw = raw; }
    }

    public static NostrResponse fromJsonArray(JsonArray jsonArray) {
        try {
            String type = jsonArray.get(0).getAsString();
            switch (type) {
                case "EVENT":
                    if (jsonArray.size() >= 3) {
                        String subId = jsonArray.get(1).getAsString();
                        JsonObject eventJson = jsonArray.get(2).getAsJsonObject();
                        // La logique pour parser l'événement à partir de JSON irait ici.
                        // NostrEvent event = parseEventFromJson(eventJson);
                        // return new Event(subId, event);
                    }
                    break;
                case "EOSE":
                    if (jsonArray.size() >= 2) {
                        return new EndOfStoredEvents(jsonArray.get(1).getAsString());
                    }
                    break;
                // Autres cas...
            }
        } catch (Exception e) {
            // Ignorer l'erreur et retourner Unknown
        }
        return new Unknown(jsonArray.toString());
    }
}
