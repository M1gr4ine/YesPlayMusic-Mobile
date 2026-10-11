package dev.yesplaymusic.probe;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local-only history of tracks whose MediaPlayer.start() completed successfully. */
final class RecentPlaybackStore {
    static final int MAX_ENTRIES = 100;
    private static final int DEFAULT_LIMIT = 20;
    private static final String PREFS_NAME = "recent_playback";
    private static final String PREFS_KEY = "tracks";
    private static final Object LOCK = new Object();

    private final SharedPreferences preferences;

    private RecentPlaybackStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    static RecentPlaybackStore get(Context context) {
        Context app = context.getApplicationContext();
        return new RecentPlaybackStore(app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    void record(Track track) {
        record(track, System.currentTimeMillis());
    }

    void record(Track track, long playedAt) {
        Entry entry = fromTrack(track, playedAt);
        if (entry == null) return;
        synchronized (LOCK) {
            List<Entry> entries = merge(readEntriesLocked(), entry, MAX_ENTRIES);
            preferences.edit().putString(PREFS_KEY, encode(entries)).apply();
        }
    }

    JSONObject execute(JSONObject args) throws JSONException {
        int requested = args == null ? DEFAULT_LIMIT : args.optInt("limit", DEFAULT_LIMIT);
        int limit = requested <= 0 ? DEFAULT_LIMIT : Math.min(MAX_ENTRIES, requested);
        synchronized (LOCK) {
            List<Entry> entries = readEntriesLocked();
            JSONArray tracks = new JSONArray();
            for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                tracks.put(entries.get(i).toJson());
            }
            return new JSONObject().put("code", 200)
                    .put("tracks", tracks)
                    .put("total", entries.size());
        }
    }

    private List<Entry> readEntriesLocked() {
        String stored = preferences.getString(PREFS_KEY, "[]");
        ArrayList<Entry> parsed = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length() && i < 500; i++) {
                JSONObject value = array.optJSONObject(i);
                if (value == null) continue;
                Entry entry = new Entry(
                        value.optString("id", ""),
                        value.optString("provider", ""),
                        value.optString("title", ""),
                        value.optString("artist", ""),
                        value.optString("album", ""),
                        value.optString("artwork", ""),
                        value.optLong("duration", 0L),
                        value.optString("qqMid", ""),
                        value.optLong("lastPlayedAt", 0L));
                if (entry.isValid()) parsed.add(entry);
            }
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }
        return merge(parsed, null, MAX_ENTRIES);
    }

    static Entry fromTrack(Track track, long playedAt) {
        if (track == null || playedAt <= 0L) return null;
        String provider = track.provider;
        if (!"netease".equals(provider) && !"qq".equals(provider)) {
            if (track.id != null && track.id.matches("[0-9]+")) provider = "netease";
            else if (track.id != null && track.id.startsWith("qq:")) provider = "qq";
        }
        Entry entry = new Entry(track.id, provider, track.title, track.artist, track.album,
                track.artwork, track.duration, track.qqMid, playedAt);
        return entry.isValid() ? entry : null;
    }

    static List<Entry> merge(List<Entry> existing, Entry incoming, int maxEntries) {
        int limit = Math.max(0, Math.min(MAX_ENTRIES, maxEntries));
        ArrayList<Entry> all = new ArrayList<>();
        if (incoming != null && incoming.isValid()) all.add(incoming);
        if (existing != null) {
            for (Entry entry : existing) {
                if (entry != null && entry.isValid()) all.add(entry);
            }
        }
        Collections.sort(all, new Comparator<Entry>() {
            @Override public int compare(Entry left, Entry right) {
                return Long.compare(right.lastPlayedAt, left.lastPlayedAt);
            }
        });
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (Entry entry : all) unique.putIfAbsent(entry.key(), entry);
        ArrayList<Entry> result = new ArrayList<>(Math.min(limit, unique.size()));
        for (Entry entry : unique.values()) {
            if (result.size() >= limit) break;
            result.add(entry);
        }
        return result;
    }

    private static String encode(List<Entry> entries) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) out.append(',');
            out.append(entries.get(i).toJsonString());
        }
        return out.append(']').toString();
    }

    static final class Entry {
        final String id;
        final String provider;
        final String title;
        final String artist;
        final String album;
        final String artwork;
        final long duration;
        final String qqMid;
        final long lastPlayedAt;

        Entry(String id, String provider, String title, String artist, String album,
              String artwork, long duration, String qqMid, long lastPlayedAt) {
            this.id = clean(id, 256);
            this.provider = clean(provider, 32).toLowerCase(java.util.Locale.ROOT);
            this.title = clean(title, 512);
            this.artist = clean(artist, 512);
            this.album = clean(album, 512);
            this.artwork = clean(artwork, 2048);
            this.duration = duration < 0L || duration > 24L * 60L * 60L * 1000L ? 0L : duration;
            this.qqMid = clean(qqMid, 256);
            this.lastPlayedAt = lastPlayedAt;
        }

        boolean isValid() {
            return !id.isEmpty() && ("netease".equals(provider) || "qq".equals(provider))
                    && lastPlayedAt > 0L;
        }

        String key() {
            return provider + ":" + id;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject(toJsonString());
        }

        String toJsonString() {
            return "{\"id\":" + quote(id)
                    + ",\"provider\":" + quote(provider)
                    + ",\"title\":" + quote(title)
                    + ",\"artist\":" + quote(artist)
                    + ",\"album\":" + quote(album)
                    + ",\"artwork\":" + quote(artwork)
                    + ",\"duration\":" + duration
                    + ",\"qqMid\":" + quote(qqMid)
                    + ",\"lastPlayedAt\":" + lastPlayedAt + "}";
        }

        private static String quote(String value) {
            StringBuilder out = new StringBuilder(value.length() + 2).append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                        else out.append(c);
                }
            }
            return out.append('"').toString();
        }

        private static String clean(String value, int maxLength) {
            if (value == null || "null".equals(value)) return "";
            String normalized = value.trim();
            return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
        }
    }
}
