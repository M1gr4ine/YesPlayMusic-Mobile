package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Match-only alternate artist names and structured per-member credits taken from official provider metadata. */
final class ArtistAliases {
    static final int MAX_MEMBER_COUNT = 16;
    private static final int MAX_ALIAS_COUNT = 32;
    private static final int MAX_ALIAS_LENGTH = 128;
    private ArtistAliases() {}

    /** Ordered artist credit member with optional provider-scoped artist ID and member-scoped official aliases. */
    static final class Member {
        final String name;
        final String providerId;
        final List<String> aliases;

        Member(String name, String providerId, List<String> aliases) {
            this.name = clean(name);
            this.providerId = cleanProviderId(providerId);
            this.aliases = cleanMemberAliases(this.name, aliases);
        }

        Member withAddedAliases(List<String> extraAliases) {
            if (extraAliases == null || extraAliases.isEmpty() || name.isEmpty()) return this;
            List<String> combined = new ArrayList<>(aliases.size() + extraAliases.size());
            combined.addAll(aliases);
            combined.addAll(extraAliases);
            List<String> merged = cleanMemberAliases(name, combined);
            return merged.equals(aliases) ? this : new Member(name, providerId, merged);
        }
    }

    static List<Member> membersFromArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return Collections.emptyList();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < artists.length() && members.size() < MAX_MEMBER_COUNT; i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            String name = clean(artist.optString("name", ""));
            if (name.isEmpty()) name = clean(artist.optString("singerName", ""));
            if (name.isEmpty()) continue;
            String providerId = extractProviderId(artist);
            List<String> aliases = extractSingleArtistAliases(artist, name);
            members.add(new Member(name, providerId, aliases));
        }
        return members.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(members);
    }

    static List<Member> membersFromJson(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < array.length() && members.size() < MAX_MEMBER_COUNT; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String name = clean(item.optString("name", ""));
            if (name.isEmpty()) continue;
            String providerId = cleanProviderId(item.optString("providerId", ""));
            List<String> aliases = new ArrayList<>();
            JSONArray rawAliases = item.optJSONArray("aliases");
            for (int j = 0; rawAliases != null && j < rawAliases.length() && aliases.size() < MAX_ALIAS_COUNT; j++) {
                Object entry = rawAliases.opt(j);
                if (entry instanceof String) aliases.add((String) entry);
            }
            members.add(new Member(name, providerId, aliases));
        }
        return members.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(members);
    }

    static JSONArray membersToJson(List<Member> members) {
        JSONArray array = new JSONArray();
        if (members == null) return array;
        for (Member member : members) {
            if (member == null || member.name.isEmpty() || array.length() >= MAX_MEMBER_COUNT) continue;
            try {
                JSONObject item = new JSONObject().put("name", member.name);
                if (!member.providerId.isEmpty()) item.put("providerId", member.providerId);
                JSONArray aliases = toJsonArray(member.aliases);
                if (aliases.length() > 0) item.put("aliases", aliases);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        return array;
    }

    static List<String> fromArtists(JSONArray artists) {
        if (artists == null || artists.length() == 0) return Collections.emptyList();
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            addCanonical(canonicalNames, artist.optString("name", ""));
        }

        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            for (String field : MatchRules.ALIAS_FIELDS) addAliasValues(aliases, canonicalNames, artist.opt(field));
        }
        return immutableValues(aliases);
    }

    static List<String> fromAliasArray(JSONArray values, String canonicalArtist) {
        if (values == null || values.length() == 0) return Collections.emptyList();
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        addCanonical(canonicalNames, canonicalArtist);
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        addAliasValues(aliases, canonicalNames, values);
        return immutableValues(aliases);
    }

    static JSONArray toJsonArray(List<String> aliases) {
        JSONArray values = new JSONArray();
        if (aliases == null) return values;
        for (String alias : aliases) {
            String clean = clean(alias);
            if (!clean.isEmpty() && values.length() < MAX_ALIAS_COUNT) values.put(clean);
        }
        return values;
    }

    private static String extractProviderId(JSONObject artist) {
        String providerId = cleanProviderId(artist.optString("providerId", ""));
        if (!providerId.isEmpty()) return providerId;
        String mid = cleanProviderId(artist.optString("mid", ""));
        if (!mid.isEmpty()) return mid;
        Object rawId = artist.opt("id");
        if (rawId instanceof Number) {
            long id = ((Number) rawId).longValue();
            return id > 0 ? Long.toString(id) : "";
        }
        return rawId instanceof String ? cleanProviderId((String) rawId) : "";
    }

    private static List<String> extractSingleArtistAliases(JSONObject artist, String canonicalName) {
        if (MatchText.containsExplicitJoinSeparator(canonicalName) || MatchText.memberAtomNames(canonicalName).isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        addCanonicalAtom(canonicalNames, canonicalName);
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (String field : MatchRules.ALIAS_FIELDS) addAliasValues(aliases, canonicalNames, artist.opt(field));
        return immutableValues(aliases);
    }

    private static List<String> cleanMemberAliases(String memberName, List<String> rawAliases) {
        if (rawAliases == null || rawAliases.isEmpty()
                || MatchText.containsExplicitJoinSeparator(memberName) || MatchText.memberAtomNames(memberName).isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Boolean> canonicalNames = new LinkedHashMap<>();
        addCanonicalAtom(canonicalNames, memberName);
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (String value : rawAliases) addAlias(aliases, canonicalNames, value);
        return immutableValues(aliases);
    }

    private static void addCanonical(Map<String, Boolean> names, String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return;
        for (String token : MatchText.artistNames(clean)) names.put(token, Boolean.TRUE);
    }

    private static void addCanonicalAtom(Map<String, Boolean> names, String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return;
        for (String token : MatchText.memberAtomNames(clean)) names.put(token, Boolean.TRUE);
    }

    private static void addAliasValues(LinkedHashMap<String, String> aliases,
                                       Map<String, Boolean> canonicalNames, Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && aliases.size() < MAX_ALIAS_COUNT; i++) {
                Object item = array.opt(i);
                if (item instanceof String) addAlias(aliases, canonicalNames, (String) item);
            }
        } else if (value instanceof String) {
            addAlias(aliases, canonicalNames, (String) value);
        }
    }

    private static void addAlias(LinkedHashMap<String, String> aliases,
                                 Map<String, Boolean> canonicalNames, String value) {
        if (aliases.size() >= MAX_ALIAS_COUNT) return;
        String clean = clean(value);
        if (clean.isEmpty() || !MatchText.isSingleArtistCredit(clean)) return;
        String normalized = MatchText.normalizeArtistToken(clean);
        if (normalized.isEmpty() || canonicalNames.containsKey(normalized) || aliases.containsKey(normalized)) return;
        aliases.put(normalized, clean);
    }

    private static List<String> immutableValues(LinkedHashMap<String, String> values) {
        if (values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values.values()));
    }

    private static String clean(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.isEmpty() || "null".equalsIgnoreCase(normalized)) return "";
        if (normalized.length() > MAX_ALIAS_LENGTH) return "";
        return normalized;
    }

    private static String cleanProviderId(String value) {
        String clean = clean(value);
        if (clean.isEmpty() || "0".equals(clean)) return "";
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c <= 0x20 || c == 0x7f) return "";
        }
        return clean;
    }
}
