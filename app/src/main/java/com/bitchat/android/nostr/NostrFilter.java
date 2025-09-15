package com.bitchat.android.nostr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Filtre d'événements Nostr pour les abonnements.
 */
public final class NostrFilter {

    public final List<String> ids;
    public final List<String> authors;
    public final List<Integer> kinds;
    public final Integer since;
    public final Integer until;
    public final Integer limit;
    private final Map<String, List<String>> tagFilters;

    public NostrFilter(List<String> ids, List<String> authors, List<Integer> kinds, Integer since, Integer until, Integer limit, Map<String, List<String>> tagFilters) {
        this.ids = ids;
        this.authors = authors;
        this.kinds = kinds;
        this.since = since;
        this.until = until;
        this.limit = limit;
        this.tagFilters = tagFilters;
    }

    public static NostrFilter giftWrapsFor(String pubkey, Long since) {
        Map<String, List<String>> tags = new HashMap<>();
        tags.put("p", Collections.singletonList(pubkey));
        Integer sinceInt = since != null ? (int) (since / 1000) : null;
        return new NostrFilter(null, null, Collections.singletonList(NostrKind.GIFT_WRAP), sinceInt, null, 100, tags);
    }

    public static class Builder {
        private List<String> ids;
        private List<String> authors;
        private List<Integer> kinds;
        private Integer since;
        private Integer until;
        private Integer limit;
        private final Map<String, List<String>> tagFilters = new HashMap<>();

        public Builder ids(String... ids) { this.ids = Arrays.asList(ids); return this; }
        public Builder authors(String... authors) { this.authors = Arrays.asList(authors); return this; }
        public Builder kinds(Integer... kinds) { this.kinds = Arrays.asList(kinds); return this; }
        public Builder since(long timestamp) { this.since = (int) (timestamp / 1000); return this; }
        public Builder limit(int count) { this.limit = count; return this; }
        public Builder tag(String name, String... values) { this.tagFilters.put(name, Arrays.asList(values)); return this; }

        public NostrFilter build() {
            return new NostrFilter(ids, authors, kinds, since, until, limit, tagFilters);
        }
    }

    public static class FilterSerializer implements JsonSerializer<NostrFilter> {
        @Override
        public JsonElement serialize(NostrFilter src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject jsonObject = new JsonObject();
            if (src.ids != null) jsonObject.add("ids", context.serialize(src.ids));
            if (src.authors != null) jsonObject.add("authors", context.serialize(src.authors));
            if (src.kinds != null) jsonObject.add("kinds", context.serialize(src.kinds));
            if (src.since != null) jsonObject.addProperty("since", src.since);
            if (src.until != null) jsonObject.addProperty("until", src.until);
            if (src.limit != null) jsonObject.addProperty("limit", src.limit);
            if (src.tagFilters != null) {
                for (Map.Entry<String, List<String>> entry : src.tagFilters.entrySet()) {
                    jsonObject.add("#" + entry.getKey(), context.serialize(entry.getValue()));
                }
            }
            return jsonObject;
        }
    }
}
