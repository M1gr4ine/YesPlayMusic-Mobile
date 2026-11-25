package dev.yesplaymusic.probe;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Immutable public metadata carried from the WebView to the playback service. */
final class Track {
    static final int CURRENT_QQ_MATCH_VERSION = 2;
    final String id;
    final String title;
    final String artist;
    final List<String> artistAliases;
    final String album;
    final String artwork;
    final long duration;
    final String provider;
    final String qqMid;
    final String mediaMid;
    final int qqMidVersion;
    final String albumId;
    final List<String> artistIds;
    private final String artistRefsJson;
    final String url;

    private Track(String id, String title, String artist, List<String> artistAliases, String album, String artwork,
                  long duration, String provider, String qqMid, String mediaMid, int qqMidVersion,
                  String albumId, List<String> artistIds, String artistRefsJson, String url) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.artistAliases = artistAliases;
        this.album = album;
        this.artwork = artwork;
        this.duration = duration;
        this.provider = provider;
        this.qqMid = qqMid;
        this.mediaMid = mediaMid;
        this.qqMidVersion = qqMid.isEmpty() ? 0 : Math.max(0, qqMidVersion);
        this.albumId = albumId;
        this.artistIds = Collections.unmodifiableList(new ArrayList<>(artistIds));
        this.artistRefsJson = artistRefsJson;
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
        String qqMid = clean(value.optString("qqMid", ""), 256);
        String mediaMid = clean(value.optString("mediaMid", ""), 256);
        if (mediaMid.isEmpty()) mediaMid = clean(value.optString("media_mid", ""), 256);
        int version = Math.max(0, value.optInt("qqMidVersion", 0));
        if (id.startsWith("qq:")) {
            String nativeMid = clean(id.substring(3), 256);
            if (!qqMid.isEmpty() && !qqMid.equals(nativeMid)) {
                mediaMid = "";
                version = 0;
            }
            qqMid = nativeMid;
        }
        JSONArray refs = new JSONArray();
        List<String> artistIds = new ArrayList<>();
        JSONArray inputRefs = value.optJSONArray("artistRefs");
        if (inputRefs != null) {
            for (int i = 0; i < inputRefs.length() && i < 16; i++) {
                JSONObject ref = inputRefs.optJSONObject(i);
                if (ref == null) continue;
                String refId = clean(ref.optString("id", ""), 256);
                String name = clean(ref.optString("name", ""), 512);
                if (refId.isEmpty()) continue;
                if (!artistIds.contains(refId)) artistIds.add(refId);
                try { refs.put(new JSONObject().put("id", refId).put("name", name)); }
                catch (JSONException ignored) { }
            }
        }
        JSONArray inputIds = value.optJSONArray("artistIds");
        if (inputIds != null) {
            for (int i = 0; i < inputIds.length() && i < 16; i++) {
                String refId = clean(inputIds.optString(i, ""), 256);
                if (!refId.isEmpty() && !artistIds.contains(refId)) artistIds.add(refId);
            }
        }
        return new Track(
                id,
                title,
                artist,
                artistAliases,
                clean(value.optString("album", ""), 512),
                clean(value.optString("artwork", ""), 2048),
                duration,
                provider,
                qqMid, mediaMid, version, clean(value.optString("albumId", ""), 256),
                artistIds, refs.toString(), clean(value.optString("url", ""), 8192));
    }

    String effectiveQqMid() {
        return id.startsWith("qq:") ? clean(id.substring(3), 256) : qqMid;
    }

    boolean hasVerifiedQqMid() {
        return !effectiveQqMid().isEmpty() && qqMidVersion == CURRENT_QQ_MATCH_VERSION;
    }

    Track withQqMid(String actualQqMid) {
        String mid = clean(actualQqMid, 256);
        if (mid.isEmpty() || mid.equals(qqMid)) return this;
        if (id.startsWith("qq:")) return this;
        return copy(mid, "", 0);
    }

    Track withVerifiedQqMid(String actualQqMid, String actualMediaMid) {
        String mid = clean(actualQqMid, 256);
        if (mid.isEmpty()) return this;
        if (id.startsWith("qq:") && !mid.equals(effectiveQqMid())) return copy(effectiveQqMid(), "", 0);
        return copy(mid, clean(actualMediaMid, 256), CURRENT_QQ_MATCH_VERSION);
    }

    Track clearQqMid() {
        return "netease".equals(provider) ? copy("", "", 0) : this;
    }

    private Track copy(String mid, String fileMid, int version) {
        return new Track(id, title, artist, artistAliases, album, artwork, duration,
                provider, mid, fileMid, version, albumId, artistIds, artistRefsJson, url);
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
            if (!mediaMid.isEmpty()) value.put("mediaMid", mediaMid);
            if (!qqMid.isEmpty() && qqMidVersion > 0) value.put("qqMidVersion", qqMidVersion);
            if (!albumId.isEmpty()) value.put("albumId", albumId);
            JSONArray refs = new JSONArray(artistRefsJson);
            if (refs.length() > 0) value.put("artistRefs", refs);
            if (!artistIds.isEmpty()) {
                JSONArray ids = new JSONArray();
                for (String artistId : artistIds) ids.put(artistId);
                value.put("artistIds", ids);
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

