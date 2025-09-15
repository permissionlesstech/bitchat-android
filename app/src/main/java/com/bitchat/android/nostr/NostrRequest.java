package com.bitchat.android.nostr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Représente les messages de requête du protocole Nostr.
 */
public abstract class NostrRequest {
    private NostrRequest() {}

    public static final class Event extends NostrRequest {
        public final NostrEvent event;
        public Event(NostrEvent event) { this.event = event; }
    }

    public static final class Subscribe extends NostrRequest {
        public final String subscriptionId;
        public final List<NostrFilter> filters;
        public Subscribe(String subscriptionId, List<NostrFilter> filters) {
            this.subscriptionId = subscriptionId;
            this.filters = filters;
        }
    }

    public static final class Close extends NostrRequest {
        public final String subscriptionId;
        public Close(String subscriptionId) { this.subscriptionId = subscriptionId; }
    }

    public static class RequestSerializer implements JsonSerializer<NostrRequest> {
        @Override
        public JsonElement serialize(NostrRequest src, Type typeOfSrc, JsonSerializationContext context) {
            JsonArray array = new JsonArray();
            if (src instanceof Event) {
                array.add("EVENT");
                array.add(context.serialize(((Event) src).event));
            } else if (src instanceof Subscribe) {
                array.add("REQ");
                array.add(((Subscribe) src).subscriptionId);
                for (NostrFilter filter : ((Subscribe) src).filters) {
                    array.add(context.serialize(filter, NostrFilter.class));
                }
            } else if (src instanceof Close) {
                array.add("CLOSE");
                array.add(((Close) src).subscriptionId);
            }
            return array;
        }
    }

    public static String toJson(NostrRequest request) {
        Gson gson = new GsonBuilder()
            .registerTypeAdapter(NostrRequest.class, new RequestSerializer())
            .registerTypeAdapter(NostrFilter.class, new NostrFilter.FilterSerializer())
            .disableHtmlEscaping()
            .create();
        return gson.toJson(request);
    }
}
