package dev.yesplaymusic.probe;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Immutable public metadata carried from the WebView to the playback service. */
final class Track {
    final String id;
    final String title;
    final String artist;
    final List<String> artistAliases;
    final String album;
    final String artwork;
    final long duration;
    final String provider;
    final String qqMid;
    final String url;

    private Track(String id, String title, String artist, List<String> artistAliases, String album, String artwork,
                  long duration, String provider, String qqMid, String url) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.artistAliases = artistAliases;
        this.album = album;
        this.artwork = artwork;
        this.duration = duration;
        this.provider = provider;
        this.qqMid = qqMid;
        this.url = url;
    }

    static Track fromJson(JSONObject value) {
        if (value == null) {
            return null;
        }
        String id = clean(value.optString("id", ""), 256);
        if (id.isEmpty()) {
            return null;
        }
        String provider = clean(value.optString("provider", ""), 32).toLowerCase(java.util.Locale.ROOT);
        if (!"netease".equals(provider) && !"qq".equals(provider)) {
            provider = "";
        }
        // Catalog identity survives audio-source fallback and repairs pre-0.2.4 saved queues.
        if (id.startsWith("qq:")) provider = "qq";
        else if (id.matches("[0-9]+")) provider = "netease";
        long duration = value.optLong("duration", 0L);
        if (duration < 0L || duration > 24L * 60L * 60L * 1000L) {
            duration = 0L;
        }
        String title = clean(value.optString("title", ""), 512);
        String artist = clean(value.optString("artist", ""), 512);
        List<String> artistAliases = ArtistAliases.fromAliasArray(value.optJSONArray("artistAliases"), artist);
        return new Track(
                id,
                title,
                artist,
                artistAliases,
                clean(value.optString("album", ""), 512),
                clean(value.optString("artwork", ""), 2048),
                duration,
                provider,
                clean(value.optString("qqMid", ""), 256),
                clean(value.optString("url", ""), 8192));
    }

    Track withQqMid(String actualQqMid) {
        // Actual audio source is published separately as PlaybackService.currentSource.
        // Changing catalog provider here would save a NetEase song to the QQ account.
        String resolvedQqMid = clean(actualQqMid, 256);
        if (resolvedQqMid.isEmpty()) resolvedQqMid = qqMid;
        return new Track(id, title, artist, artistAliases, album, artwork, duration,
                provider, resolvedQqMid, url);
    }

    JSONObject toJson(boolean includeEphemeralUrl) {
        JSONObject value = new JSONObject();
        try {
            value.put("id", id);
            value.put("title", title);
            value.put("artist", artist);
            JSONArray aliases = ArtistAliases.toJsonArray(artistAliases);
            if (aliases.length() > 0) value.put("artistAliases", aliases);
            value.put("album", album);
            value.put("artwork", artwork);
            value.put("duration", duration);
            if (!provider.isEmpty()) {
                value.put("provider", provider);
            }
            if (!qqMid.isEmpty()) {
                value.put("qqMid", qqMid);
            }
            if (includeEphemeralUrl && !url.isEmpty()) {
                value.put("url", url);
            }
        } catch (JSONException ignored) {
        }
        return value;
    }

    private static String clean(String value, int maxLength) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            return normalized.substring(0, maxLength);
        }
        return normalized;
    }
}

