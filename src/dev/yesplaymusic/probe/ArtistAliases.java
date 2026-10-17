package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Match-only alternate artist names taken from the same provider's metadata. */
final class ArtistAliases {
    private static final int MAX_ALIAS_COUNT = 32;
    private static final int MAX_ALIAS_LENGTH = 128;
    private static final int MAX_SEARCH_RETRIES = 2;
    private static final String[] ALIAS_FIELDS = {"alias", "alia", "tns", "trans"};
    private static final Pattern COLLABORATION =
            Pattern.compile("\\b(feat\\.?|ft\\.?|featuring|with|vs\\.?)\\b|\\s+x\\s+");

    private ArtistAliases() {}

    static int score(String title, String artist, List<String> artistAliases, long duration,
                     String candidateTitle, String candidateArtist, List<String> candidateAliases,
                     long candidateDuration) {
        int strict = QQMatch.score(title, artist, duration, candidateTitle, candidateArtist, candidateDuration);
        if (strict >= 0) return strict;
        if (!isSingleArtistCredit(artist) || !isSingleArtistCredit(candidateArtist)) return -1;

        Map<String, String> right = namesForMatching(candidateArtist, candidateAliases);
        int best = -1;
        for (Map.Entry<String, String> left : namesForMatching(artist, artistAliases).entrySet()) {
            String rightName = right.get(left.getKey());
            if (rightName == null) continue;
            best = Math.max(best, QQMatch.score(title, left.getValue(), duration,
                    candidateTitle, rightName, candidateDuration));
        }
        return best;
    }

    static List<String> searchQueries(String title, String canonicalArtist, List<String> aliases) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanArtist = canonicalArtist == null ? "" : canonicalArtist.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 200 || !isSingleArtistCredit(cleanArtist)) {
            return Collections.emptyList();
        }
        String normalizedCanonical = QQMatch.normalize(cleanArtist);
        if (normalizedCanonical.isEmpty() || aliases == null || aliases.isEmpty()) return Collections.emptyList();

        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String alias : aliases) {
            String cleanAlias = clean(alias);
            if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
            String normalizedAlias = QQMatch.normalize(cleanAlias);
            if (normalizedAlias.isEmpty() || normalizedAlias.equals(normalizedCanonical)
                    || unique.containsKey(normalizedAlias)) continue;
            unique.put(normalizedAlias, cleanAlias);
            if (unique.size() >= MAX_SEARCH_RETRIES) break;
        }

        if (unique.isEmpty()) return Collections.emptyList();
        List<String> queries = new ArrayList<>(unique.size());
        for (String alias : unique.values()) queries.add(cleanTitle + " " + alias);
        return Collections.unmodifiableList(queries);
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
            for (String field : ALIAS_FIELDS) addAliasValues(aliases, canonicalNames, artist.opt(field));
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

    static List<String> fromExactNeteaseSong(JSONObject response, String songId) {
        if (response == null || songId == null || songId.trim().isEmpty()) return Collections.emptyList();
        JSONObject result = response.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            Object foundId = song.opt("id");
            if (foundId != null && songId.equals(String.valueOf(foundId))) {
                return fromArtists(song.optJSONArray("ar"));
            }
        }
        return Collections.emptyList();
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

    /** Single-credit names keyed by their normalized form, canonical name first. */
    private static Map<String, String> namesForMatching(String canonical, List<String> aliases) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        addName(names, canonical);
        if (aliases != null) {
            for (String alias : aliases) addName(names, alias);
        }
        return names;
    }

    private static void addName(Map<String, String> names, String value) {
        String clean = clean(value);
        if (clean.isEmpty() || !isSingleArtistCredit(clean)) return;
        String normalized = QQMatch.normalize(clean);
        if (!normalized.isEmpty() && !names.containsKey(normalized)) names.put(normalized, clean);
    }

    private static void addCanonical(Map<String, Boolean> names, String value) {
        String clean = clean(value);
        String normalized = QQMatch.normalize(clean);
        if (!normalized.isEmpty()) names.put(normalized, Boolean.TRUE);
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
        if (clean.isEmpty() || !isSingleArtistCredit(clean)) return;
        String normalized = QQMatch.normalize(clean);
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

    private static boolean isSingleArtistCredit(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '/' || c == '\\' || c == ',' || c == '\u3001' || c == '\uFF0C'
                    || c == '&' || c == '\uFF06' || c == ';' || c == '\n' || c == '\r') return false;
        }
        return !COLLABORATION.matcher(clean.toLowerCase(Locale.ROOT)).find();
    }
}
