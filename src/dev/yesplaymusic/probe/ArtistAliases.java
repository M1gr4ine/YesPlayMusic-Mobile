package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Alternate artist names and per-member credits extracted from provider metadata. */
final class ArtistAliases {
    private static final String[] ALIAS_FIELDS = {"alias", "alia", "tns", "trans", "transNames"};

    private ArtistAliases() {}

    /** Ordered artist credit member with optional provider-scoped artist ID and official aliases. */
    static final class Member {
        final String name;
        final String providerId;
        final List<String> aliases;

        Member(String name, String providerId, List<String> aliases) {
            this.name = name.trim();
            String id = providerId.trim();
            this.providerId = "0".equals(id) ? "" : id;
            this.aliases = dedupeAliases(aliases, this.name);
        }
    }

    static List<Member> membersFromArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return Collections.emptyList();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = artistName(artist);
            if (name.isEmpty()) continue;
            List<String> aliases = new ArrayList<>();
            collectAliases(aliases, artist);
            members.add(new Member(name, extractProviderId(artist), aliases));
        }
        return members.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(members);
    }

    static List<Member> membersFromJson(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String name = stringField(item, "name");
            if (name.isEmpty()) continue;
            List<String> aliases = new ArrayList<>();
            collectStrings(aliases, item.opt("aliases"));
            members.add(new Member(name, stringField(item, "providerId"), aliases));
        }
        return members.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(members);
    }

    static JSONArray membersToJson(List<Member> members) throws JSONException {
        JSONArray array = new JSONArray();
        for (Member member : members) {
            JSONObject item = new JSONObject().put("name", member.name);
            if (!member.providerId.isEmpty()) item.put("providerId", member.providerId);
            if (!member.aliases.isEmpty()) item.put("aliases", toJsonArray(member.aliases));
            array.put(item);
        }
        return array;
    }

    static List<String> fromArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return Collections.emptyList();
        Set<String> canonical = new LinkedHashSet<>();
        List<String> rawAliases = new ArrayList<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            QQMatch.addCreditTokens(canonical, artistName(artist));
            collectAliases(rawAliases, artist);
        }
        return dedupeAliases(rawAliases, canonical);
    }

    static List<String> fromAliasArray(JSONArray values, String canonicalArtist) {
        if (values == null || values.length() == 0) return Collections.emptyList();
        List<String> rawAliases = new ArrayList<>();
        collectStrings(rawAliases, values);
        return dedupeAliases(rawAliases, canonicalArtist);
    }

    static JSONArray toJsonArray(List<String> aliases) {
        JSONArray values = new JSONArray();
        for (String alias : aliases) values.put(alias);
        return values;
    }

    private static String artistName(JSONObject artist) {
        String name = stringField(artist, "name");
        return name.isEmpty() ? stringField(artist, "singerName") : name;
    }

    private static void collectAliases(List<String> target, JSONObject artist) {
        for (String field : ALIAS_FIELDS) collectStrings(target, artist.opt(field));
    }

    private static void collectStrings(List<String> target, Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                if (item instanceof String) target.add((String) item);
            }
        } else if (value instanceof String) {
            target.add((String) value);
        }
    }

    private static List<String> dedupeAliases(List<String> rawAliases, String canonicalName) {
        if (rawAliases.isEmpty()) return Collections.emptyList();
        Set<String> canonical = new LinkedHashSet<>();
        QQMatch.addCreditTokens(canonical, canonicalName);
        return dedupeAliases(rawAliases, canonical);
    }

    private static List<String> dedupeAliases(List<String> rawAliases, Set<String> canonical) {
        if (rawAliases.isEmpty()) return Collections.emptyList();
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : rawAliases) {
            String trimmed = value.trim();
            String token = QQMatch.normalizeArtistToken(trimmed);
            if (!token.isEmpty() && !canonical.contains(token)) unique.putIfAbsent(token, trimmed);
        }
        return unique.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static String extractProviderId(JSONObject artist) {
        String providerId = stringField(artist, "providerId");
        if (!providerId.isEmpty() && !"0".equals(providerId)) return providerId;
        String mid = stringField(artist, "mid");
        if (!mid.isEmpty() && !"0".equals(mid)) return mid;
        Object rawId = artist.opt("id");
        if (rawId instanceof Number) {
            long id = ((Number) rawId).longValue();
            return id > 0 ? Long.toString(id) : "";
        }
        return rawId instanceof String ? ((String) rawId).trim() : "";
    }

    private static String stringField(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? ((String) value).trim() : "";
    }
}
