package dev.yesplaymusic.probe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Direct, on-device NetEase Music API client. Credentials stay in native AuthStore. */
public final class MusicApi {
    private static final String MUSIC_HOST = "https://music.163.com";
    private static final String API_HOST = "https://interface.music.163.com";
    private static final String PLAYLIST_EDIT_API_HOST = "https://interfacepc.music.163.com";
    private static final String ANDROID_APP_VERSION = "8.20.20.231215173437";
    private static final String ANDROID_CHANNEL = "xiaomi";
    private static final String WEAPI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";
    private static final int CONNECT_TIMEOUT_MS = 9_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static volatile MusicApi instance;

    private final Context appContext;
    private final AuthStore authStore;
    private final Object cookieLock = new Object();
    private final SessionGeneration sessionGeneration = new SessionGeneration();
    private final NetEaseLikedTrackCache likedTrackCache = new NetEaseLikedTrackCache();

    private MusicApi(Context context) {
        appContext = context.getApplicationContext();
        authStore = new AuthStore(appContext);
    }

    public static MusicApi get(Context context) {
        MusicApi current = instance;
        if (current == null) {
            synchronized (MusicApi.class) {
                current = instance;
                if (current == null) {
                    current = new MusicApi(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Native login flows call this with the received Set-Cookie values. Never expose it to WebView JS. */
    public void setCookie(String cookie) {
        synchronized (cookieLock) {
            advanceSessionGeneration();
            String clean = cleanCookieHeader(cookie);
            if (clean.isEmpty()) {
                authStore.clear("netease");
            } else {
                authStore.put("netease", clean);
            }
        }
    }

    /** Native-only accessor used to attach the authenticated NetEase session to HTTPS requests. */
    public String getCookie() {
        synchronized (cookieLock) {
            String value = authStore.get("netease");
            return value == null ? "" : value;
        }
    }

    public JSONObject execute(String method, JSONObject args) throws Exception {
        JSONObject input = args == null ? new JSONObject() : args;
        if ("home".equals(method)) return home();
        if ("search".equals(method)) return search(input);
        if ("playlist".equals(method)) return playlist(input);
        if ("album".equals(method)) return album(input);
        if ("artist".equals(method)) return artist(input);
        if ("artistAlbums".equals(method)) return artistAlbums(input);
        if ("songDetail".equals(method)) return songDetails(input);
        if ("userPlaylists".equals(method)) return userPlaylists(input);
        if ("loginStatus".equals(method)) return loginStatus();
        if ("likeStatus".equals(method)) return likeStatus(input);
        if ("setLiked".equals(method)) return setLiked(input);
        if ("ownedPlaylists".equals(method)) return ownedPlaylists();
        if ("playlistAddTrack".equals(method)) return playlistAddTrack(input);
        if ("playlistUpdate".equals(method)) return playlistUpdate(input);
        if ("playlistRemoveTracks".equals(method)) return playlistRemoveTracks(input);
        if ("playlistReorder".equals(method)) return playlistReorder(input);
        if ("matchTrack".equals(method)) return matchTrack(input);
        if ("sendCaptcha".equals(method)) return sendCaptcha(input);
        if ("loginCaptcha".equals(method)) return loginCaptcha(input);
        if ("lyrics".equals(method)) return lyrics(input);
        if ("logout".equals(method)) return logout();
        throw new IllegalArgumentException("Unknown NetEase method: " + method);
    }

    public JSONObject resolveTrackUrl(String id) throws Exception {
        String safeId = requireId(id);
        JSONObject params = new JSONObject();
        params.put("ids", "[" + safeId + "]");
        params.put("br", 999000);
        JSONObject result = requestEapi("/api/song/enhance/player/url", params);
        JSONArray data = result.optJSONArray("data");
        if (data == null || data.length() == 0) {
            throw apiError("歌曲没有可用的网易云播放地址", result);
        }
        JSONObject item = data.optJSONObject(0);
        String url = item == null ? null : item.optString("url", null);
        if (url == null || url.length() == 0 || "null".equals(url)) {
            throw apiError("这首歌当前没有可播放的网易云音源", result);
        }
        if (url.startsWith("http://")) url = "https://" + url.substring("http://".length());
        if (!url.startsWith("https://")) throw new IOException("网易云未提供安全的 HTTPS 播放地址");
        boolean trial = item.has("freeTrialInfo") && !item.isNull("freeTrialInfo")
                && !"null".equals(item.optString("freeTrialInfo"));
        JSONObject playable = new JSONObject();
        playable.put("id", safeId);
        playable.put("url", url);
        playable.put("source", "netease");
        playable.put("duration", item.optLong("time", 0L));
        playable.put("trial", trial);
        if (item.has("br")) playable.put("br", item.optLong("br"));
        if (item.has("type")) playable.put("type", item.optString("type"));
        return playable;
    }

    private JSONObject home() throws Exception {
        JSONObject playlistParams = new JSONObject();
        playlistParams.put("limit", 18);
        playlistParams.put("total", true);
        playlistParams.put("n", 1000);
        JSONObject songParams = new JSONObject();
        songParams.put("limit", 10);
        JSONObject playlists = null;
        JSONObject songsResponse = null;
        JSONArray playlistItems = null;
        JSONArray songs = null;
        String playlistError = "";
        String songsError = "";
        java.util.concurrent.ExecutorService requests = java.util.concurrent.Executors.newFixedThreadPool(2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(22);
        Future<JSONObject> playlistRequest = requests.submit(
                () -> requestWeapi("/api/personalized/playlist", playlistParams));
        Future<JSONObject> songRequest = requests.submit(
                () -> requestWeapi("/api/personalized/newsong", songParams));
        Future<JSONObject> fallbackRequest = null;
        try {
            try {
                playlists = RequestDeadline.await(playlistRequest, deadline);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                playlistError = "推荐歌单暂时不可用";
                playlistRequest.cancel(true);
                songRequest.cancel(true);
            } catch (Exception error) {
                playlistError = "推荐歌单暂时不可用";
                playlistRequest.cancel(true);
            }
            if (Thread.currentThread().isInterrupted()) {
                songRequest.cancel(true);
                if (songsError.isEmpty()) songsError = "新歌推荐暂时不可用";
            } else {
                try {
                    songsResponse = RequestDeadline.await(songRequest, deadline);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    songsError = "新歌推荐暂时不可用";
                    songRequest.cancel(true);
                } catch (Exception error) {
                    songsError = "新歌推荐暂时不可用";
                    songRequest.cancel(true);
                }
            }

            playlistItems = playlists == null ? null : playlists.optJSONArray("result");
            if (!Thread.currentThread().isInterrupted() && playlists != null
                    && playlists.optInt("code", 200) == 200
                    && (playlistItems == null || playlistItems.length() == 0)) {
                fallbackRequest = requests.submit(
                        () -> requestWeapi("/api/v1/discovery/recommend/resource", new JSONObject()));
                try {
                    JSONObject publicResources = RequestDeadline.await(fallbackRequest, deadline);
                    JSONArray fallback = publicResources.optJSONArray("recommend");
                    if (fallback != null && fallback.length() > 0) playlistItems = fallback;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    playlistError = "推荐歌单暂时不可用";
                    fallbackRequest.cancel(true);
                } catch (Exception error) {
                    if (playlistError.isEmpty()) playlistError = "推荐歌单暂时不可用";
                    fallbackRequest.cancel(true);
                }
            }
            songs = songsResponse == null ? null : songsResponse.optJSONArray("result");
        } finally {
            if (!playlistRequest.isDone()) playlistRequest.cancel(true);
            if (!songRequest.isDone()) songRequest.cancel(true);
            if (fallbackRequest != null && !fallbackRequest.isDone()) fallbackRequest.cancel(true);
            requests.shutdownNow();
        }

        JSONObject result = new JSONObject();
        result.put("playlists", playlistItems == null ? new JSONArray() : playlistItems);
        result.put("songs", songs == null ? new JSONArray() : songs);
        if (playlists != null) result.put("playlistResponse", sanitize(playlists));
        if (songsResponse != null) result.put("songsResponse", sanitize(songsResponse));
        result.put("guest", getCookie().isEmpty());
        if (playlists != null && playlists.optInt("code", 200) != 200 && playlistError.isEmpty()) {
            playlistError = playlists.optString("msg", "推荐歌单暂时不可用");
        }
        if (songsResponse != null && songsResponse.optInt("code", 200) != 200 && songsError.isEmpty()) {
            songsError = songsResponse.optString("msg", "新歌推荐暂时不可用");
        }
        if (!playlistError.isEmpty()) result.put("playlistError", playlistError);
        if (!songsError.isEmpty()) result.put("songsError", songsError);
        result.put("code", 200);
        return result;
    }

    private JSONObject search(JSONObject args) throws Exception {
        String keywords = args.optString("keywords", args.optString("query", "")).trim();
        if (keywords.isEmpty()) throw new IllegalArgumentException("请输入搜索内容");
        JSONObject params = new JSONObject();
        params.put("s", keywords);
        params.put("type", args.optInt("type", 1));
        params.put("limit", clamp(args.optInt("limit", 30), 1, 100));
        params.put("offset", Math.max(0, args.optInt("offset", 0)));
        params.put("total", true);
        return sanitize(requestWeapi("/api/cloudsearch/pc", params));
    }

    List<String> findExactNeteaseArtistAliases(String songId, String title, String artist) throws Exception {
        String id = songId == null ? "" : songId.trim();
        String cleanTitle = title == null ? "" : title.trim();
        String cleanArtist = artist == null ? "" : artist.trim();
        if (!NetEaseAuthState.hasPositiveUserId(id) || cleanTitle.isEmpty() || cleanTitle.length() > 200
                || cleanArtist.length() > 200) return java.util.Collections.emptyList();
        JSONObject response = search(new JSONObject().put("keywords", (cleanTitle + " " + cleanArtist).trim())
                .put("type", 1).put("limit", 10));
        return ArtistAliases.fromExactNeteaseSong(response, id);
    }

    private static final int MAX_PLAYLIST_TRACKS = 500; // Keep in sync with PlaybackService.MAX_QUEUE_SIZE.
    private static final int MAX_PROVIDER_PLAYLIST_TRACKS = 100000; // Match the maximum playlist detail request size.
    private static final int SONG_DETAILS_BATCH_SIZE = 100;
    private static final int PLAYLIST_REQUEST_DEADLINE_SECONDS = 22;

    private JSONObject playlist(JSONObject args) throws Exception {
        String id = requireId(args.optString("id", args.optString("playlistId", "")));
        JSONObject params = new JSONObject();
        params.put("id", id);
        params.put("n", clamp(args.optInt("limit", 100000), 1, 100000));
        params.put("s", 8);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PLAYLIST_REQUEST_DEADLINE_SECONDS);
        java.util.concurrent.ExecutorService requests = java.util.concurrent.Executors.newFixedThreadPool(3);
        java.util.concurrent.Future<JSONObject> initialRequest = null;
        List<java.util.concurrent.Future<JSONObject>> detailRequests = new ArrayList<>();
        try {
            initialRequest = requests.submit(() -> requestEapi("/api/v6/playlist/detail", params));
            JSONObject result;
            try {
                result = RequestDeadline.await(initialRequest, deadline);
            } catch (InterruptedException error) {
                initialRequest.cancel(true);
                Thread.currentThread().interrupt();
                throw error;
            } catch (java.util.concurrent.TimeoutException error) {
                initialRequest.cancel(true);
                throw new IOException("\u6b4c\u5355\u52a0\u8f7d\u8d85\u65f6\uff0c\u8bf7\u91cd\u8bd5", error);
            } catch (java.util.concurrent.ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("Playlist request failed", cause);
            }

            JSONObject playlist = result.optJSONObject("playlist");
            if (playlist == null) return sanitize(result);
            JSONArray sourceTracks = playlist.optJSONArray("tracks");
            JSONArray trackIds = playlist.optJSONArray("trackIds");
            int totalTrackCount = trackIds != null && trackIds.length() > 0
                    ? trackIds.length() : sourceTracks == null ? 0 : sourceTracks.length();
            if (trackIds == null || trackIds.length() == 0) {
                JSONArray cappedTracks = new JSONArray();
                if (sourceTracks != null) {
                    for (int i = 0; i < Math.min(sourceTracks.length(), MAX_PLAYLIST_TRACKS); i++) {
                        cappedTracks.put(sourceTracks.opt(i));
                    }
                }
                playlist.put("tracks", cappedTracks);
                playlist.put("totalTrackCount", totalTrackCount);
                playlist.put("loadedTrackCount", cappedTracks.length());
                playlist.put("tracksTruncated", totalTrackCount > MAX_PLAYLIST_TRACKS);
                if (totalTrackCount > MAX_PLAYLIST_TRACKS) {
                    playlist.put("tracksLoadError", "\u8be5\u6b4c\u5355\u8d85\u8fc7500\u9996\uff0c\u4ec5\u52a0\u8f7d\u524d500\u9996");
                }
                return sanitize(result);
            }

            List<String> orderedIds = new ArrayList<>();
            for (int i = 0; i < trackIds.length(); i++) {
                JSONObject item = trackIds.optJSONObject(i);
                String trackId = item == null ? "" : item.optString("id", "");
                if (trackId.matches("[0-9]{1,24}")) orderedIds.add(trackId);
            }

            Map<String, JSONObject> tracksById = new LinkedHashMap<>();
            if (sourceTracks != null) {
                for (int i = 0; i < sourceTracks.length(); i++) {
                    JSONObject track = sourceTracks.optJSONObject(i);
                    if (track == null) continue;
                    String trackId = track.optString("id", "");
                    if (trackId.matches("[0-9]{1,24}") && !tracksById.containsKey(trackId)) {
                        tracksById.put(trackId, track);
                    }
                }
            }

            List<String> missingIds = PlaylistTrackOrder.missingIds(
                    orderedIds, tracksById.keySet(), MAX_PLAYLIST_TRACKS);
            for (int start = 0; start < missingIds.size(); start += SONG_DETAILS_BATCH_SIZE) {
                final List<String> batchIds = new ArrayList<>(missingIds.subList(
                        start, Math.min(start + SONG_DETAILS_BATCH_SIZE, missingIds.size())));
                detailRequests.add(requests.submit(() -> {
                    JSONObject input = new JSONObject();
                    input.put("ids", joinIds(batchIds));
                    return songDetails(input);
                }));
            }

            for (java.util.concurrent.Future<JSONObject> detailRequest : detailRequests) {
                try {
                    JSONObject details = RequestDeadline.await(detailRequest, deadline);
                    if (details.optInt("code", 200) != 200) continue;
                    JSONArray songs = details.optJSONArray("songs");
                    if (songs == null) continue;
                    for (int i = 0; i < songs.length(); i++) {
                        JSONObject song = songs.optJSONObject(i);
                        if (song == null) continue;
                        String songId = song.optString("id", "");
                        if (songId.matches("[0-9]{1,24}") && !tracksById.containsKey(songId)) {
                            tracksById.put(songId, song);
                        }
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ignored) {
                    // Preserve all tracks already available and report partial loading below.
                }
            }

            List<String> loadedIds = PlaylistTrackOrder.orderedAvailableIds(
                    orderedIds, tracksById.keySet(), MAX_PLAYLIST_TRACKS);
            JSONArray loadedTracks = new JSONArray();
            for (String trackId : loadedIds) loadedTracks.put(tracksById.get(trackId));
            int targetTrackCount = Math.min(totalTrackCount, MAX_PLAYLIST_TRACKS);
            playlist.put("tracks", loadedTracks);
            playlist.put("totalTrackCount", totalTrackCount);
            playlist.put("loadedTrackCount", loadedTracks.length());
            playlist.put("tracksTruncated", totalTrackCount > MAX_PLAYLIST_TRACKS);
            if (loadedTracks.length() < targetTrackCount) {
                playlist.put("tracksLoadError", "\u90e8\u5206\u6b4c\u66f2\u4fe1\u606f\u6682\u65f6\u65e0\u6cd5\u52a0\u8f7d");
            } else if (totalTrackCount > MAX_PLAYLIST_TRACKS) {
                playlist.put("tracksLoadError", "\u8be5\u6b4c\u5355\u8d85\u8fc7500\u9996\uff0c\u4ec5\u52a0\u8f7d\u524d500\u9996");
            }
            return sanitize(result);
        } finally {
            if (initialRequest != null && !initialRequest.isDone()) initialRequest.cancel(true);
            for (java.util.concurrent.Future<JSONObject> request : detailRequests) {
                if (!request.isDone()) request.cancel(true);
            }
            requests.shutdownNow();
        }
    }

    private static String joinIds(List<String> ids) {
        StringBuilder joined = new StringBuilder();
        for (String id : ids) {
            if (joined.length() > 0) joined.append(',');
            joined.append(id);
        }
        return joined.toString();
    }
    private JSONObject album(JSONObject args) throws Exception {
        String id = requireId(args.optString("id", args.optString("albumId", "")));
        return sanitize(requestWeapi("/api/v1/album/" + id, new JSONObject()));
    }

    /** Artist profile plus hot songs, as the desktop artist page loads them. */
    private JSONObject artist(JSONObject args) throws Exception {
        String id = requireId(args.optString("id", args.optString("artistId", "")));
        return sanitize(requestWeapi("/api/v1/artist/" + id, new JSONObject()));
    }

    private JSONObject artistAlbums(JSONObject args) throws Exception {
        String id = requireId(args.optString("id", args.optString("artistId", "")));
        JSONObject params = new JSONObject();
        params.put("limit", clamp(args.optInt("limit", 100), 1, 200));
        params.put("offset", Math.max(0, args.optInt("offset", 0)));
        params.put("total", true);
        return sanitize(requestWeapi("/api/artist/albums/" + id, params));
    }

    private JSONObject userPlaylists(JSONObject args) throws Exception {
        String uid = args.optString("uid", "");
        if (uid.isEmpty()) {
            JSONObject status = loginStatus();
            JSONObject profile = status.optJSONObject("profile");
            JSONObject account = status.optJSONObject("account");
            if (profile != null) {
                uid = profile.optString("userId", profile.optString("user_id", ""));
            }
            if (!NetEaseAuthState.hasPositiveUserId(uid) && account != null) {
                uid = account.optString("id", account.optString("userId", ""));
            }
            if (!NetEaseAuthState.hasPositiveUserId(uid)) {
                throw apiError("请先登录网易云音乐，再加载个人歌单", status);
            }
        }
        JSONObject params = new JSONObject();
        params.put("uid", requireId(uid));
        params.put("limit", clamp(args.optInt("limit", 30), 1, 100));
        params.put("offset", Math.max(0, args.optInt("offset", 0)));
        params.put("includeVideo", true);
        return sanitize(requestWeapi("/api/user/playlist", params));
    }

    private JSONObject loginStatus() throws Exception {
        long generation = sessionGeneration.current();
        JSONObject result = sanitize(requestWeapi("/api/w/nuser/account/get", new JSONObject(), generation));
        JSONObject profile = result.optJSONObject("profile");
        JSONObject account = result.optJSONObject("account");
        String profileId = profile == null ? ""
                : profile.optString("userId", profile.optString("user_id", ""));
        String accountId = account == null ? ""
                : account.optString("id", account.optString("userId", ""));
        String userId = NetEaseAuthState.hasPositiveUserId(profileId) ? profileId : accountId;
        boolean loggedIn = NetEaseAuthState.hasPositiveUserId(userId);
        synchronized (cookieLock) {
            if (sessionGeneration.accepts(generation)) {
                likedTrackCache.bindAccount(loggedIn ? userId : "", generation);
            }
        }
        result.put("loggedIn", loggedIn);
        return result;
    }

    private JSONObject likeStatus(JSONObject args) throws Exception {
        String id = requireId(args.optString("id", ""));
        LikeAccount account = accountForLikes();
        if (!account.loggedIn) {
            return favoriteResult(401, false, false, "\u8bf7\u5148\u767b\u5f55\u7f51\u6613\u4e91\u97f3\u4e50");
        }
        if (!sessionGeneration.accepts(account.generation)) {
            return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
        }
        long now = SystemClock.elapsedRealtime();
        final long requestRevision;
        synchronized (cookieLock) {
            if (!sessionGeneration.accepts(account.generation)) {
                return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            if (likedTrackCache.isFresh(account.userId, account.generation, now)) {
                return favoriteResult(200, true, likedTrackCache.contains(id), null);
            }
            requestRevision = likedTrackCache.revisionFor(account.userId, account.generation);
            if (requestRevision == Long.MIN_VALUE) {
                return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
        }

        JSONObject params = new JSONObject().put("uid", account.userId);
        JSONObject response = requestEapi("/api/song/like/get", params, account.generation);
        int code = response.optInt("code", -1);
        if (code != 200) {
            if (!sessionGeneration.accepts(account.generation)) {
                return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            if (code == 301 || code == 401) {
                synchronized (cookieLock) {
                    if (!sessionGeneration.accepts(account.generation)) {
                        return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
                    }
                    likedTrackCache.bindAccount("", account.generation);
                    return favoriteResult(401, false, false, "\u8bf7\u5148\u767b\u5f55\u7f51\u6613\u4e91\u97f3\u4e50");
                }
            }
            return favoriteResult(502, true, false, "\u8bfb\u53d6\u7f51\u6613\u4e91\u6536\u85cf\u72b6\u6001\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        JSONArray ids = response.optJSONArray("ids");
        if (ids == null) {
            return favoriteResult(502, true, false, "\u8bfb\u53d6\u7f51\u6613\u4e91\u6536\u85cf\u72b6\u6001\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        Set<String> likedIds = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            String likedId = ids.optString(i, "");
            if (NetEaseAuthState.hasPositiveUserId(likedId)) likedIds.add(likedId);
        }
        long fetchedAt = SystemClock.elapsedRealtime();
        synchronized (cookieLock) {
            if (!sessionGeneration.accepts(account.generation)
                    || !account.userId.equals(likedTrackCache.userIdForGeneration(account.generation))) {
                return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            if (!likedTrackCache.replaceIfRevision(account.userId, account.generation, requestRevision,
                    likedIds, fetchedAt)) {
                long latest = SystemClock.elapsedRealtime();
                if (likedTrackCache.isFresh(account.userId, account.generation, latest)) {
                    return favoriteResult(200, true, likedTrackCache.contains(id), null);
                }
                return favoriteResult(409, true, false, "\u6536\u85cf\u72b6\u6001\u5df2\u66f4\u65b0\uff0c\u8bf7\u91cd\u8bd5");
            }
            return favoriteResult(200, true, likedIds.contains(id), null);
        }
    }

    private JSONObject setLiked(JSONObject args) throws Exception {
        String id = requireId(args.optString("id", ""));
        Object likedValue = args.opt("liked");
        if (!(likedValue instanceof Boolean)) {
            throw new IllegalArgumentException("liked must be a boolean");
        }
        boolean liked = (Boolean) likedValue;
        LikeAccount account = accountForLikes();
        if (!account.loggedIn) {
            return favoriteResult(401, false, false, "\u8bf7\u5148\u767b\u5f55\u7f51\u6613\u4e91\u97f3\u4e50");
        }
        if (!sessionGeneration.accepts(account.generation)) {
            return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
        }
        JSONObject params = new JSONObject().put("alg", "itembased")
                .put("trackId", id).put("like", liked).put("time", "3");
        JSONObject response = requestWeapi("/api/radio/like", params, account.generation);
        int code = response.optInt("code", -1);
        if (code == 301 || code == 401) {
            synchronized (cookieLock) {
                if (!sessionGeneration.accepts(account.generation)) {
                    return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
                }
                likedTrackCache.bindAccount("", account.generation);
                return favoriteResult(401, false, false, "\u8bf7\u5148\u767b\u5f55\u7f51\u6613\u4e91\u97f3\u4e50");
            }
        }
        if (code != 200) {
            if (!sessionGeneration.accepts(account.generation)) {
                return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            boolean currentLiked = likedTrackCache.isFresh(account.userId, account.generation,
                    SystemClock.elapsedRealtime()) && likedTrackCache.contains(id);
            return favoriteResult(502, true, currentLiked, "\u6536\u85cf\u64cd\u4f5c\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        synchronized (cookieLock) {
            if (!sessionGeneration.accepts(account.generation)
                    || !account.userId.equals(likedTrackCache.userIdForGeneration(account.generation))) {
                return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            if (!likedTrackCache.markMutationIfCurrent(account.userId, account.generation, id, liked,
                    SystemClock.elapsedRealtime())) {
                return favoriteResult(409, false, false, "\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            return favoriteResult(200, true, liked, null);
        }
    }

    /** Playlists the signed-in user created, excluding the liked-songs list that 收藏 manages. */
    private JSONObject ownedPlaylists() throws Exception {
        LikeAccount account = accountForLikes();
        if (!account.loggedIn) {
            return playlistResult(401, false, "请先登录网易云音乐");
        }
        JSONObject params = new JSONObject().put("uid", account.userId).put("limit", 1000)
                .put("offset", 0).put("includeVideo", true);
        JSONObject response = requestWeapi("/api/user/playlist", params, account.generation);
        int code = response.optInt("code", -1);
        if (code == 301 || code == 401) {
            return playlistResult(401, false, "请先登录网易云音乐");
        }
        JSONArray source = response.optJSONArray("playlist");
        if (code != 200 || source == null) {
            return playlistResult(502, true, "读取歌单失败，请稍后重试");
        }
        JSONArray owned = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null || item.optBoolean("subscribed", false) || item.optInt("specialType", 0) == 5) continue;
            JSONObject creator = item.optJSONObject("creator");
            String creatorId = creator == null ? item.optString("userId", "") : creator.optString("userId", "");
            String id = item.optString("id", "");
            if (!account.userId.equals(creatorId) || !NetEaseAuthState.hasPositiveUserId(id)) continue;
            owned.put(new JSONObject().put("id", id)
                    .put("name", item.optString("name", "未命名歌单"))
                    .put("artwork", item.optString("coverImgUrl", ""))
                    .put("count", Math.max(0, item.optInt("trackCount", 0))));
        }
        return playlistResult(200, true, null).put("playlists", owned);
    }

    private JSONObject playlistAddTrack(JSONObject args) throws Exception {
        String pid = requireId(args.optString("pid", ""));
        String id = requireId(args.optString("id", ""));
        LikeAccount account = accountForLikes();
        if (!account.loggedIn) {
            return playlistResult(401, false, "请先登录网易云音乐");
        }
        JSONObject params = new JSONObject().put("op", "add").put("pid", pid)
                .put("trackIds", new JSONArray().put(id).toString()).put("imme", "true");
        JSONObject response = requestEapi("/api/playlist/manipulate/tracks", params, account.generation);
        int code = response.optInt("code", -1);
        if (code == 512) {
            // Same retry as the desktop client's API package: the service accepts a repeated id.
            params.put("trackIds", new JSONArray().put(id).put(id).toString());
            response = requestEapi("/api/playlist/manipulate/tracks", params, account.generation);
            code = response.optInt("code", -1);
        }
        if (code == 200) return playlistResult(200, true, null).put("added", true);
        if (code == 502) {
            return playlistResult(200, true, "这首歌已经在歌单里了")
                    .put("added", false).put("duplicate", true);
        }
        if (code == 301 || code == 401) {
            return playlistResult(401, false, "请先登录网易云音乐");
        }
        String message = response.optString("message", response.optString("msg", ""));
        if (message.isEmpty() || message.length() > 60) {
            message = "添加到歌单失败，请稍后重试";
        }
        return playlistResult(code > 0 ? code : 502, true, message);
    }

    private JSONObject playlistUpdate(JSONObject args) throws Exception {
        try (PlaylistEditDeadline.Scope ignored = PlaylistEditDeadline.open()) {
            return playlistUpdateInScope(args);
        } catch (PlaylistEditDeadline.DeadlineExceededException timeout) {
            return playlistResult(504, false, timeout.getMessage()).put("updated", false)
                    .put("nameUpdated", false).put("descriptionUpdated", false)
                    .put("nameVerified", false).put("descriptionVerified", false)
                    .put("partial", false).put("resultUnknown", false);
        }
    }

    private JSONObject playlistUpdateInScope(JSONObject args) throws Exception {
        String pid = positivePlaylistId(args.optString("pid", ""));
        boolean updateName = args.has("name");
        boolean updateDescription = args.has("description");
        if (!updateName && !updateDescription) {
            throw new IllegalArgumentException("请至少填写歌单名称或简介");
        }
        String name = updateName ? args.optString("name", "").trim() : "";
        String description = updateDescription ? args.optString("description", "") : "";
        if (updateName && (name.isEmpty() || name.codePointCount(0, name.length()) > 40)) {
            throw new IllegalArgumentException("歌单名称需为 1 至 40 个字符");
        }
        if (updateDescription && description.codePointCount(0, description.length()) > 1000) {
            throw new IllegalArgumentException("歌单简介不能超过 1000 个字符");
        }

        LikeAccount account = accountForLikes();
        if (!account.loggedIn) return playlistResult(401, false, "请先登录网易云音乐").put("updated", false)
                .put("nameVerified", false).put("descriptionVerified", false);
        boolean nameUpdated = false;
        boolean descriptionUpdated = false;
        boolean nameAttempted = false;
        boolean descriptionAttempted = false;
        boolean nameVerified = false;
        boolean descriptionVerified = false;
        String activeStage = "playlist_preflight";
        try {
            editablePlaylist(pid, account);
            if (updateName) {
                nameAttempted = true;
                activeStage = "name_submit";
                JSONObject response = requestEapiAt(PLAYLIST_EDIT_API_HOST,
                        "/api/playlist/update/name", new JSONObject().put("id", pid).put("name", name),
                        account.generation);
                JSONObject failure = editResponseFailure(response, account, "歌单名称修改失败");
                if (failure != null) return mutationProviderFailure(failure, response, activeStage,
                        "歌单名称修改").put("updated", false).put("nameUpdated", false)
                        .put("descriptionUpdated", false).put("nameVerified", false)
                        .put("descriptionVerified", false).put("partial", false).put("resultUnknown", true);
                nameUpdated = true;
            }
            if (updateDescription) {
                descriptionAttempted = true;
                activeStage = "description_submit";
                JSONObject response = requestEapiAt(PLAYLIST_EDIT_API_HOST,
                        "/api/playlist/desc/update",
                        new JSONObject().put("id", pid).put("desc", description), account.generation);
                JSONObject failure = editResponseFailure(response, account, "歌单简介修改失败");
                if (failure != null) {
                    JSONObject result = mutationProviderFailure(failure, response, activeStage,
                            "歌单简介修改");
                    if (nameUpdated) {
                        result.put("message", playlistUpdateAcceptedPrefix(nameUpdated, descriptionUpdated)
                                + result.optString("message", ""));
                    }
                    return result.put("updated", false).put("nameUpdated", nameUpdated)
                            .put("descriptionUpdated", false).put("nameVerified", false)
                            .put("descriptionVerified", false)
                            .put("partial", playlistUpdatePartial(updateName, updateDescription,
                                    nameUpdated, descriptionUpdated, nameVerified, descriptionVerified))
                            .put("resultUnknown", nameAttempted || descriptionAttempted);
                }
                descriptionUpdated = true;
            }
            if (!sessionGeneration.accepts(account.generation)) {
                return playlistResult(409, false, playlistUpdateSessionChangedMessage(nameUpdated, descriptionUpdated))
                        .put("updated", false).put("nameUpdated", nameUpdated)
                        .put("descriptionUpdated", descriptionUpdated).put("nameVerified", false)
                        .put("descriptionVerified", false).put("partial", playlistUpdatePartial(updateName,
                                updateDescription, nameUpdated, descriptionUpdated, false, false))
                        .put("resultUnknown", nameAttempted || descriptionAttempted)
                        .put("stage", activeStage);
            }
            activeStage = "metadata_readback";
            JSONObject after = editablePlaylist(pid, account);
            nameVerified = updateName && name.equals(after.optString("name", ""));
            String savedDescription = after.optString("description", after.optString("desc", ""));
            descriptionVerified = updateDescription && description.equals(savedDescription);
            boolean allRequestedVerified = (!updateName || nameVerified) && (!updateDescription || descriptionVerified);
            if (!allRequestedVerified) {
                boolean partial = playlistUpdatePartial(updateName, updateDescription, nameUpdated, descriptionUpdated, nameVerified, descriptionVerified);
                String stage = metadataMismatchStage(updateName, updateDescription, nameVerified, descriptionVerified);
                return playlistResult(502, true, metadataMismatchMessage(updateName, updateDescription,
                        nameVerified, descriptionVerified))
                        .put("updated", false).put("nameUpdated", nameUpdated)
                        .put("descriptionUpdated", descriptionUpdated).put("nameVerified", nameVerified)
                        .put("descriptionVerified", descriptionVerified).put("partial", partial).put("stage", stage)
                        .put("readbackCode", 200).put("resultUnknown", true);
            }
            return playlistResult(200, true, null).put("updated", true)
                    .put("nameUpdated", nameUpdated).put("descriptionUpdated", descriptionUpdated)
                    .put("nameVerified", nameVerified).put("descriptionVerified", descriptionVerified)
                    .put("partial", false).put("resultUnknown", false);
        } catch (PlaylistEditFailure failure) {
            JSONObject result = failure.result();
            if ((nameAttempted && !nameVerified) || (descriptionAttempted && !descriptionVerified)) {
                result.put("message", playlistUpdateFailureMessage(activeStage, failure.code, nameUpdated, descriptionUpdated))
                        .put("stage", activeStage);
                if ("metadata_readback".equals(activeStage)) result.put("readbackCode", failure.code);
            }
            return result.put("updated", false).put("nameUpdated", nameUpdated)
                    .put("descriptionUpdated", descriptionUpdated).put("nameVerified", nameVerified)
                    .put("descriptionVerified", descriptionVerified)
                    .put("partial", playlistUpdatePartial(updateName, updateDescription, nameUpdated, descriptionUpdated, nameVerified, descriptionVerified))
                    .put("resultUnknown", (nameAttempted && !nameVerified) || (descriptionAttempted && !descriptionVerified));
        } catch (PlaylistEditDeadline.DeadlineExceededException timeout) {
            String message = nameAttempted || descriptionAttempted
                    ? playlistUpdateFailureMessage(activeStage, -1, nameUpdated, descriptionUpdated) : timeout.getMessage();
            JSONObject result = playlistResult(504, sessionGeneration.accepts(account.generation), message)
                    .put("updated", false).put("nameUpdated", nameUpdated)
                    .put("descriptionUpdated", descriptionUpdated).put("nameVerified", nameVerified)
                    .put("descriptionVerified", descriptionVerified)
                    .put("partial", playlistUpdatePartial(updateName, updateDescription, nameUpdated, descriptionUpdated, nameVerified, descriptionVerified))
                    .put("resultUnknown", (nameAttempted && !nameVerified) || (descriptionAttempted && !descriptionVerified))
                    .put("stage", activeStage);
            if (activeStage.endsWith("_submit")) result.put("providerCode", -1);
            else if ("metadata_readback".equals(activeStage)) result.put("readbackCode", -1);
            return result;
        } catch (Exception failure) {
            if (!sessionGeneration.accepts(account.generation)) {
                return playlistResult(409, false, playlistUpdateSessionChangedMessage(nameUpdated, descriptionUpdated))
                        .put("updated", false).put("nameUpdated", nameUpdated)
                        .put("descriptionUpdated", descriptionUpdated).put("nameVerified", nameVerified)
                        .put("descriptionVerified", descriptionVerified)
                        .put("partial", playlistUpdatePartial(updateName, updateDescription, nameUpdated, descriptionUpdated, nameVerified, descriptionVerified))
                        .put("resultUnknown", (nameAttempted && !nameVerified) || (descriptionAttempted && !descriptionVerified))
                        .put("stage", activeStage);
            }
            JSONObject result = playlistResult(502, true, nameAttempted || descriptionAttempted
                    ? playlistUpdateFailureMessage(activeStage, -1, nameUpdated, descriptionUpdated) : "歌单修改失败，请重试")
                    .put("updated", false).put("nameUpdated", nameUpdated)
                    .put("descriptionUpdated", descriptionUpdated).put("nameVerified", nameVerified)
                    .put("descriptionVerified", descriptionVerified)
                    .put("partial", playlistUpdatePartial(updateName, updateDescription, nameUpdated, descriptionUpdated, nameVerified, descriptionVerified))
                    .put("resultUnknown", (nameAttempted && !nameVerified) || (descriptionAttempted && !descriptionVerified))
                    .put("stage", activeStage);
            if (nameAttempted || descriptionAttempted) {
                if (activeStage.endsWith("_submit")) result.put("providerCode", -1);
                else if ("metadata_readback".equals(activeStage)) result.put("readbackCode", -1);
            }
            return result;
        }
    }

    private JSONObject playlistRemoveTracks(JSONObject args) throws Exception {
        try (PlaylistEditDeadline.Scope ignored = PlaylistEditDeadline.open()) {
            return playlistRemoveTracksInScope(args);
        } catch (PlaylistEditDeadline.DeadlineExceededException timeout) {
            return playlistResult(504, false, timeout.getMessage()).put("removed", false)
                    .put("resultUnknown", false);
        }
    }

    private JSONObject playlistRemoveTracksInScope(JSONObject args) throws Exception {
        String pid = positivePlaylistId(args.optString("pid", ""));
        List<String> ids = PlaylistEditPolicy.requirePositiveIds(args.optJSONArray("ids"), MAX_PLAYLIST_TRACKS);
        LikeAccount account = accountForLikes();
        if (!account.loggedIn) return playlistResult(401, false, "请先登录网易云音乐").put("removed", false);
        boolean removalAttempted = false;
        try {
            JSONObject before = editablePlaylist(pid, account);
            List<String> beforeIds;
            try {
                beforeIds = PlaylistEditPolicy.completeTrackIds(before, MAX_PROVIDER_PLAYLIST_TRACKS);
            } catch (IllegalArgumentException incomplete) {
                return playlistResult(409, true, "无法加载完整歌单曲目")
                        .put("removed", false);
            }
            if (!beforeIds.containsAll(ids)) {
                return playlistResult(409, true, "所选歌曲已不在该歌单中，请刷新后重试")
                        .put("removed", false);
            }
            JSONArray trackIds = new JSONArray();
            for (String id : ids) trackIds.put(id);
            JSONObject params = new JSONObject().put("op", "del").put("pid", pid)
                    .put("trackIds", trackIds.toString()).put("imme", "true");
            removalAttempted = true;
            JSONObject response = requestEapi("/api/playlist/manipulate/tracks", params, account.generation);
            JSONObject failure = editResponseFailure(response, account, "移除歌曲失败");
            if (failure != null) return failure.put("message", "网易云音乐未确认歌曲移除结果")
                    .put("removed", false).put("resultUnknown", true);
            if (!sessionGeneration.accepts(account.generation)) {
                return playlistResult(409, false, "登录状态已变化，移除结果未确认，请刷新歌单")
                        .put("removed", false).put("resultUnknown", true);
            }
            JSONObject after = editablePlaylist(pid, account);
            List<String> afterIds;
            try {
                afterIds = PlaylistEditPolicy.completeTrackIds(after, MAX_PROVIDER_PLAYLIST_TRACKS);
            } catch (IllegalArgumentException incomplete) {
                return playlistResult(502, true, "网易云音乐未确认歌曲移除结果")
                        .put("removed", false).put("resultUnknown", true);
            }
            if (!PlaylistEditPolicy.removalMatches(beforeIds, ids, afterIds)) {
                return playlistResult(502, true, "网易云音乐未确认歌曲移除结果")
                        .put("removed", false).put("resultUnknown", true);
            }
            return playlistResult(200, true, null).put("removed", true);
        } catch (PlaylistEditFailure failure) {
            JSONObject result = failure.result().put("removed", false);
            if (removalAttempted) result.put("message", "网易云音乐未确认歌曲移除结果")
                    .put("resultUnknown", true);
            return result;
        } catch (PlaylistEditDeadline.DeadlineExceededException timeout) {
            return playlistResult(504, sessionGeneration.accepts(account.generation), timeout.getMessage())
                    .put("removed", false).put("resultUnknown", removalAttempted);
        } catch (Exception failure) {
            if (!sessionGeneration.accepts(account.generation)) {
                return playlistResult(409, false, removalAttempted
                        ? "登录状态已变化，移除结果未确认，请刷新歌单" : "登录状态已变化，请重试")
                        .put("removed", false).put("resultUnknown", removalAttempted);
            }
            return playlistResult(502, true, removalAttempted
                    ? "网易云音乐未确认歌曲移除结果" : "移除歌曲失败，请重试")
                    .put("removed", false).put("resultUnknown", removalAttempted);
        }
    }

    private JSONObject playlistReorder(JSONObject args) throws Exception {
        try (PlaylistEditDeadline.Scope ignored = PlaylistEditDeadline.open()) {
            return playlistReorderInScope(args);
        } catch (PlaylistEditDeadline.DeadlineExceededException timeout) {
            return playlistResult(504, false, timeout.getMessage()).put("reordered", false)
                    .put("resultUnknown", false);
        }
    }

    private JSONObject playlistReorderInScope(JSONObject args) throws Exception {
        String pid = positivePlaylistId(args.optString("pid", ""));
        List<String> desiredIds = PlaylistEditPolicy.requirePositiveIds(
                args.optJSONArray("ids"), MAX_PLAYLIST_TRACKS);
        LikeAccount account = accountForLikes();
        if (!account.loggedIn) return playlistResult(401, false, "请先登录网易云音乐").put("reordered", false);
        boolean reorderAttempted = false;
        String activeStage = "playlist_preflight";
        try {
            JSONObject before = editablePlaylist(pid, account);
            List<String> currentIds;
            try {
                currentIds = PlaylistEditPolicy.completeTrackIds(before, MAX_PLAYLIST_TRACKS);
            } catch (IllegalArgumentException incomplete) {
                return playlistResult(409, true, "歌单超过 500 首或无法加载完整曲目，暂不支持排序")
                        .put("reordered", false);
            }
            if (!PlaylistEditPolicy.sameTrackIds(currentIds, desiredIds)) {
                return playlistResult(409, true, "排序必须包含歌单中的全部歌曲，且每首仅出现一次")
                        .put("reordered", false);
            }
            if (currentIds.equals(desiredIds)) {
                return playlistResult(200, true, null).put("reordered", true).put("unchanged", true);
            }
            StringBuilder ordered = new StringBuilder("[");
            for (String id : desiredIds) {
                if (ordered.length() > 1) ordered.append(',');
                ordered.append(id);
            }
            ordered.append(']');
            reorderAttempted = true;
            activeStage = "order_submit";
            JSONObject response = requestEapiAt(PLAYLIST_EDIT_API_HOST,
                    "/api/playlist/manipulate/tracks",
                    new JSONObject().put("pid", pid).put("trackIds", ordered.toString()).put("op", "update"),
                    account.generation);
            JSONObject failure = editResponseFailure(response, account, "歌单排序失败");
            if (failure != null) return mutationProviderFailure(failure, response, activeStage,
                    "歌单排序").put("reordered", false).put("resultUnknown", true);
            if (!sessionGeneration.accepts(account.generation)) {
                return playlistResult(409, false, "登录状态已变化，排序结果未确认，请刷新歌单")
                        .put("reordered", false).put("resultUnknown", true).put("stage", activeStage);
            }
            activeStage = "order_readback";
            JSONObject after = editablePlaylist(pid, account);
            List<String> savedIds;
            try {
                savedIds = PlaylistEditPolicy.completeTrackIds(after, MAX_PLAYLIST_TRACKS);
            } catch (IllegalArgumentException incomplete) {
                return playlistResult(502, true, "网易云音乐未确认歌单排序结果（排序回读不完整）")
                        .put("reordered", false).put("resultUnknown", true).put("stage", activeStage)
                        .put("readbackCode", 200);
            }
            if (!currentPlaylistId(after).equals(pid) || !desiredIds.equals(savedIds)) {
                return playlistResult(502, true, "网易云音乐未确认歌单排序结果（排序回读不一致）")
                        .put("reordered", false).put("resultUnknown", true).put("stage", activeStage)
                        .put("readbackCode", 200);
            }
            return playlistResult(200, true, null).put("reordered", true);
        } catch (PlaylistEditFailure failure) {
            JSONObject result = failure.result().put("reordered", false);
            if (reorderAttempted) {
                result.put("message", "网易云音乐未确认歌单排序结果（" + playlistStageLabel(activeStage)
                                + "，读取码 " + failure.code + "）")
                        .put("stage", activeStage).put("readbackCode", failure.code).put("resultUnknown", true);
            }
            return result;
        } catch (PlaylistEditDeadline.DeadlineExceededException timeout) {
            JSONObject result = playlistResult(504, sessionGeneration.accepts(account.generation),
                    reorderAttempted ? "网易云音乐未确认歌单排序结果（" + playlistStageLabel(activeStage)
                            + "，网络或响应异常）" : timeout.getMessage())
                    .put("reordered", false).put("resultUnknown", reorderAttempted).put("stage", activeStage);
            if (reorderAttempted) result.put("order_submit".equals(activeStage) ? "providerCode" : "readbackCode", -1);
            return result;
        } catch (Exception failure) {
            if (!sessionGeneration.accepts(account.generation)) {
                return playlistResult(409, false, reorderAttempted
                        ? "登录状态已变化，排序结果未确认，请刷新歌单" : "登录状态已变化，请重试")
                        .put("reordered", false).put("resultUnknown", reorderAttempted).put("stage", activeStage);
            }
            JSONObject result = playlistResult(502, true, reorderAttempted
                    ? "网易云音乐未确认歌单排序结果（" + playlistStageLabel(activeStage) + "，网络或响应异常）"
                    : "歌单排序失败，请重试")
                    .put("reordered", false).put("resultUnknown", reorderAttempted).put("stage", activeStage);
            if (reorderAttempted) result.put("order_submit".equals(activeStage) ? "providerCode" : "readbackCode", -1);
            return result;
        }
    }

    private JSONObject editablePlaylist(String pid, LikeAccount account) throws Exception {
        if (!sessionGeneration.accepts(account.generation)) {
            throw new PlaylistEditFailure(409, false, "登录状态已变化，请重试");
        }
        JSONObject params = new JSONObject().put("id", pid).put("n", 100000).put("s", 0);
        JSONObject response = requestEapi("/api/v6/playlist/detail", params, account.generation);
        if (!sessionGeneration.accepts(account.generation)) {
            throw new PlaylistEditFailure(409, false, "登录状态已变化，请重试");
        }
        int code = response.optInt("code", -1);
        if (code == 301 || code == 401) throw new PlaylistEditFailure(401, false, "请先登录网易云音乐");
        JSONObject playlist = response.optJSONObject("playlist");
        if (code != 200 || playlist == null) throw new PlaylistEditFailure(502, true, "无法读取歌单");
        if (!PlaylistEditPolicy.isOwnedPlaylist(playlist, account.userId)) {
            throw new PlaylistEditFailure(403, true, "只能编辑当前账号创建的歌单");
        }
        return playlist;
    }

    private JSONObject editResponseFailure(JSONObject response, LikeAccount account, String fallback) throws Exception {
        if (!sessionGeneration.accepts(account.generation)) {
            return playlistResult(409, false, "登录状态已变化，请重试");
        }
        int code = response.optInt("code", -1);
        if (code == 200) return null;
        if (code == 301 || code == 401) return playlistResult(401, false, "请先登录网易云音乐");
        String message = response.optString("message", response.optString("msg", ""));
        if (message.isEmpty() || message.length() > 100) message = fallback;
        return playlistResult(code > 0 ? code : 502, true, message);
    }

    private static JSONObject mutationProviderFailure(JSONObject failure, JSONObject response,
                                                       String stage, String operation) throws JSONException {
        int providerCode = response.optInt("code", -1);
        String message = providerCode == 301 || providerCode == 401
                ? "请先登录网易云音乐（" + playlistStageLabel(stage) + "，错误码 " + providerCode + "）"
                : "网易云音乐拒绝" + operation + "（" + playlistStageLabel(stage)
                        + "，错误码 " + providerCode + "）";
        return failure.put("message", message).put("stage", stage).put("providerCode", providerCode);
    }

    private static String playlistStageLabel(String stage) {
        if ("name_submit".equals(stage)) return "名称提交";
        if ("description_submit".equals(stage)) return "简介提交";
        if ("order_submit".equals(stage)) return "排序提交";
        if ("metadata_readback".equals(stage)) return "歌单信息回读";
        if ("name_readback_mismatch".equals(stage)) return "名称回读";
        if ("description_readback_mismatch".equals(stage)) return "简介回读";
        if ("metadata_readback_mismatch".equals(stage)) return "名称和简介回读";
        if ("order_readback".equals(stage)) return "排序回读";
        if ("playlist_preflight".equals(stage)) return "歌单权限读取";
        return "歌单修改";
    }

    private static String playlistUpdateFailureMessage(String stage, int providerCode,
                                                        boolean nameUpdated, boolean descriptionUpdated) {
        String detail = providerCode > 0 ? "错误码 " + providerCode : "网络或响应异常";
        String accepted = playlistUpdateAcceptedPrefix(nameUpdated, descriptionUpdated);
        if ("description_submit".equals(stage) && nameUpdated && !descriptionUpdated) {
            return accepted + "简介提交结果未确认（" + detail + "）";
        }
        if ("name_submit".equals(stage) || "description_submit".equals(stage)) {
            return accepted + "网易云音乐未确认歌单修改结果（"
                    + playlistStageLabel(stage) + "，" + detail + "）";
        }
        if ("metadata_readback".equals(stage)) {
            String readback = providerCode > 0 ? "读取码 " + providerCode : detail;
            return accepted + "歌单信息回读失败，修改结果未确认（"
                    + readback + "）";
        }
        return accepted + "歌单修改失败，请重试";
    }

    private static String playlistUpdateAcceptedPrefix(boolean nameUpdated, boolean descriptionUpdated) {
        if (nameUpdated && descriptionUpdated) {
            return "名称和简介提交均已被网易云音乐接受，但尚未回读核实；";
        }
        if (nameUpdated) {
            return "名称提交已被网易云音乐接受，但尚未回读核实；";
        }
        if (descriptionUpdated) {
            return "简介提交已被网易云音乐接受，但尚未回读核实；";
        }
        return "";
    }

    private static String playlistUpdateSessionChangedMessage(boolean nameUpdated, boolean descriptionUpdated) {
        return playlistUpdateAcceptedPrefix(nameUpdated, descriptionUpdated)
                + "登录状态已变化，修改结果尚未确认，请刷新歌单";
    }

    private static boolean playlistUpdatePartial(boolean updateName, boolean updateDescription,
                                                 boolean nameUpdated, boolean descriptionUpdated,
                                                 boolean nameVerified, boolean descriptionVerified) {
        return updateName && updateDescription
                && ((nameUpdated && !descriptionUpdated) || (nameVerified != descriptionVerified));
    }

    private static String metadataMismatchStage(boolean updateName, boolean updateDescription,
                                                boolean nameVerified, boolean descriptionVerified) {
        if (updateName && !nameVerified && updateDescription && !descriptionVerified) {
            return "metadata_readback_mismatch";
        }
        if (updateName && !nameVerified) return "name_readback_mismatch";
        return "description_readback_mismatch";
    }

    private static String metadataMismatchMessage(boolean updateName, boolean updateDescription,
                                                  boolean nameVerified, boolean descriptionVerified) {
        if (updateName && !nameVerified && updateDescription && !descriptionVerified) {
            return "网易云音乐未确认歌单修改结果（名称和简介回读不一致）";
        }
        if (updateName && !nameVerified) return "网易云音乐未确认歌单修改结果（名称回读不一致）";
        return "网易云音乐未确认歌单修改结果（简介回读不一致）";
    }

    private static String positivePlaylistId(String value) {
        String id = value == null ? "" : value.trim();
        if (!NetEaseAuthState.hasPositiveUserId(id)) throw new IllegalArgumentException("歌单 ID 必须为正整数");
        return id;
    }

    private static String currentPlaylistId(JSONObject playlist) {
        return playlist == null ? "" : playlist.optString("id", "");
    }

    private static final class PlaylistEditFailure extends Exception {
        final int code;
        final boolean loggedIn;
        final String message;

        PlaylistEditFailure(int code, boolean loggedIn, String message) {
            this.code = code;
            this.loggedIn = loggedIn;
            this.message = message;
        }

        JSONObject result() throws JSONException {
            return playlistResult(code, loggedIn, message);
        }
    }

    /** Finds the NetEase version of a QQ catalog song so it can be added to a NetEase playlist. */
    private JSONObject matchTrack(JSONObject args) throws Exception {
        String title = args.optString("title", "").trim();
        String artist = args.optString("artist", "").trim();
        long duration = Math.max(0L, args.optLong("duration", 0L));
        List<String> artistAliases = ArtistAliases.fromAliasArray(args.optJSONArray("artistAliases"), artist);
        if (title.isEmpty() || title.length() > 200 || artist.length() > 200) {
            throw new IllegalArgumentException("\u6b4c\u66f2\u4fe1\u606f\u4e0d\u8db3");
        }
        JSONObject query = new JSONObject().put("keywords", (title + " " + artist).trim())
                .put("type", 1).put("limit", 10);
        JSONObject response = search(query);
        JSONObject result = response.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        JSONObject best = null;
        String bestArtist = "";
        int bestScore = -1;
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null || !NetEaseAuthState.hasPositiveUserId(song.optString("id", ""))) continue;
            String names = artistNames(song.optJSONArray("ar"));
            int score = ArtistAliases.score(title, artist, artistAliases, duration,
                    song.optString("name", ""), names, ArtistAliases.fromArtists(song.optJSONArray("ar")),
                    song.optLong("dt", 0L));
            if (score > bestScore) {
                bestScore = score;
                best = song;
                bestArtist = names;
            }
        }
        if (best == null || bestScore < 0) return new JSONObject().put("code", 200).put("matched", false);
        JSONObject album = best.optJSONObject("al");
        return new JSONObject().put("code", 200).put("matched", true)
                .put("id", best.optString("id"))
                .put("title", best.optString("name", title))
                .put("artist", bestArtist)
                .put("album", album == null ? "" : album.optString("name", ""))
                .put("albumId", album == null ? "" : album.optString("id", ""))
                .put("artists", artistRefs(best.optJSONArray("ar")))
                .put("duration", best.optLong("dt", 0L));
    }

    private static JSONArray artistRefs(JSONArray artists) throws JSONException {
        JSONArray refs = new JSONArray();
        for (int i = 0; artists != null && i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null || artist.optString("name", "").isEmpty()) continue;
            refs.put(new JSONObject().put("id", artist.optString("id", "")).put("name", artist.optString("name")));
        }
        return refs;
    }

    private static String artistNames(JSONArray artists) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; artists != null && i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            String name = artist == null ? "" : artist.optString("name", "");
            if (name.isEmpty()) continue;
            if (names.length() > 0) names.append(" / ");
            names.append(name);
        }
        return names.toString();
    }

    private static JSONObject playlistResult(int code, boolean loggedIn, String message) throws JSONException {
        JSONObject result = new JSONObject().put("code", code).put("loggedIn", loggedIn);
        if (message != null && !message.isEmpty()) result.put("message", message);
        return result;
    }

    private LikeAccount accountForLikes() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            long generation = sessionGeneration.current();
            if (likedTrackCache.hasAccountForGeneration(generation)) {
                String userId = likedTrackCache.userIdForGeneration(generation);
                return new LikeAccount(userId, generation);
            }
            try {
                loginStatus();
            } catch (Exception failure) {
                if (!sessionGeneration.accepts(generation)) continue;
                throw failure;
            }
            if (!sessionGeneration.accepts(generation)) continue;
            if (likedTrackCache.hasAccountForGeneration(generation)) {
                String userId = likedTrackCache.userIdForGeneration(generation);
                return new LikeAccount(userId, generation);
            }
        }
        return new LikeAccount("", sessionGeneration.current());
    }

    private static JSONObject favoriteResult(int code, boolean loggedIn, boolean liked, String message)
            throws JSONException {
        JSONObject result = new JSONObject().put("code", code).put("loggedIn", loggedIn).put("liked", liked);
        if (message != null && !message.isEmpty()) result.put("message", message);
        return result;
    }

    private static final class LikeAccount {
        final String userId;
        final long generation;
        final boolean loggedIn;

        LikeAccount(String userId, long generation) {
            this.userId = userId == null ? "" : userId;
            this.generation = generation;
            this.loggedIn = NetEaseAuthState.hasPositiveUserId(this.userId);
        }
    }

    private JSONObject sendCaptcha(JSONObject args) throws Exception {
        String phone = args.optString("phone", "").trim();
        if (phone.isEmpty()) throw new IllegalArgumentException("请输入手机号");
        JSONObject params = new JSONObject();
        params.put("ctcode", countryCode(args));
        params.put("secrete", "music_middleuser_pclogin");
        params.put("cellphone", phone);
        return sanitize(requestWeapi("/api/sms/captcha/sent", params));
    }

    private JSONObject loginCaptcha(JSONObject args) throws Exception {
        String phone = args.optString("phone", "").trim();
        if (phone.isEmpty()) throw new IllegalArgumentException("请输入手机号");
        String captcha = args.optString("captcha", "").trim();
        String password = args.optString("password", "");
        String md5Password = args.optString("md5_password", "");
        if (captcha.isEmpty() && password.isEmpty() && md5Password.isEmpty()) {
            throw new IllegalArgumentException("请输入密码或短信验证码");
        }
        JSONObject params = new JSONObject();
        params.put("type", "1");
        params.put("https", "true");
        params.put("phone", phone);
        params.put("countrycode", countryCode(args));
        params.put("remember", "true");
        if (!captcha.isEmpty()) {
            params.put("captcha", captcha);
        } else {
            params.put("password", md5Password.isEmpty() ? NetEaseCrypto.md5(password) : md5Password);
        }
        long generation = advanceSessionGeneration();
        JSONObject response = requestEapi("/api/w/login/cellphone", params, generation);
        if (isDebuggable()) logLoginDiagnostics(response);
        return sanitize(response);
    }

    private void logLoginDiagnostics(JSONObject response) {
        String musicU = parseCookies(getCookie()).get("MUSIC_U");
        boolean musicUSet = musicU != null && !musicU.isEmpty();
        boolean profileHasPositiveId = hasPositiveId(response.optJSONObject("profile"),
                "userId", "user_id", "id");
        boolean accountHasPositiveId = hasPositiveId(response.optJSONObject("account"), "id");
        Log.i("YPMAuth", "loginCaptcha code=" + response.optInt("code", -1)
                + " musicUSet=" + musicUSet
                + " profileHasPositiveId=" + profileHasPositiveId
                + " accountHasPositiveId=" + accountHasPositiveId);
    }

    private boolean isDebuggable() {
        return (appContext.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private static boolean hasPositiveId(JSONObject object, String... keys) {
        if (object == null) return false;
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number && ((Number) value).longValue() > 0L) return true;
            if (value instanceof String) {
                try {
                    if (Long.parseLong((String) value) > 0L) return true;
                } catch (NumberFormatException ignored) {
                    // Keep the diagnostic boolean false for non-numeric identifiers.
                }
            }
        }
        return false;
    }

    private JSONObject lyrics(JSONObject args) throws Exception {
        String id = requireId(args.optString("id", args.optString("songId", "")));
        JSONObject params = new JSONObject();
        params.put("id", id);
        params.put("tv", -1);
        params.put("lv", -1);
        params.put("rv", -1);
        params.put("kv", -1);
        params.put("_nmclfl", 1);
        return sanitize(requestWeapi("/api/song/lyric", params));
    }

    private JSONObject songDetails(JSONObject args) throws Exception {
        String ids = args.optString("ids", args.optString("id", ""));
        JSONArray array = args.optJSONArray("ids");
        if (array != null) {
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                if (joined.length() > 0) joined.append(',');
                joined.append(requireId(array.optString(i, "")));
            }
            ids = joined.toString();
        }
        String safeIds = numericIdList(ids);
        String encoded = URLEncoder.encode("[" + safeIds + "]", "UTF-8");
        JSONObject result = requestGet(MUSIC_HOST + "/api/song/detail?ids=" + encoded);
        return sanitize(result);
    }

    private JSONObject logout() throws Exception {
        synchronized (cookieLock) {
            advanceSessionGeneration();
            authStore.clear("netease");
        }
        return new JSONObject().put("code", 200).put("loggedOut", true);
    }

    private long advanceSessionGeneration() {
        synchronized (cookieLock) {
            long generation = sessionGeneration.advance();
            likedTrackCache.clear();
            return generation;
        }
    }

    private JSONObject requestWeapi(String apiPath, JSONObject params) throws Exception {
        return requestWeapi(apiPath, params, sessionGeneration.current());
    }

    private JSONObject requestWeapi(String apiPath, JSONObject params, long requestGeneration) throws Exception {
        RequestMetadata metadata;
        synchronized (cookieLock) {
            if (!sessionGeneration.accepts(requestGeneration)) {
                throw new IOException("\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            metadata = requestMetadata();
        }
        JSONObject body = new JSONObject(params.toString());
        body.put("csrf_token", metadata.cookies.getOrDefault("__csrf", ""));
        JSONObject encrypted = NetEaseCrypto.encryptWeApi(body);
        String relative = NetEaseCrypto.normalizeApiPath(apiPath).substring(5);
        return requestPost(MUSIC_HOST + "/weapi/" + relative, form(encrypted, true), true,
                requestGeneration, metadata.cookieHeader(true));
    }

    private JSONObject requestEapi(String apiPath, JSONObject params) throws Exception {
        return requestEapi(apiPath, params, sessionGeneration.current());
    }

    private JSONObject requestEapi(String apiPath, JSONObject params, long requestGeneration) throws Exception {
        return requestEapiAt(API_HOST, apiPath, params, requestGeneration);
    }

    private JSONObject requestEapiAt(String host, String apiPath, JSONObject params, long requestGeneration)
            throws Exception {
        String normalized = NetEaseCrypto.normalizeApiPath(apiPath);
        RequestMetadata metadata;
        synchronized (cookieLock) {
            if (!sessionGeneration.accepts(requestGeneration)) {
                throw new IOException("\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            metadata = requestMetadata();
        }
        JSONObject requestParameters = NetEaseCrypto.withEapiHeader(params, metadata.eapiHeader);
        JSONObject envelope = new JSONObject();
        envelope.put("params", NetEaseCrypto.encryptEApi(normalized, requestParameters));
        String relative = normalized.substring(5);
        return requestPost(host + "/eapi/" + relative, form(envelope, false), false,
                requestGeneration, metadata.eapiCookieHeader());
    }

    private JSONObject requestPost(String endpoint, String body, boolean weapi) throws Exception {
        return requestPost(endpoint, body, weapi, sessionGeneration.current());
    }

    private JSONObject requestPost(String endpoint, String body, boolean weapi, long requestGeneration) throws Exception {
        return requestPost(endpoint, body, weapi, requestGeneration, null);
    }

    private JSONObject requestPost(String endpoint, String body, boolean weapi, long requestGeneration,
                                   String cookieHeader) throws Exception {
        HttpURLConnection connection = null;
        try {
            PlaylistEditDeadline.checkCurrent();
            if (!sessionGeneration.accepts(requestGeneration)) {
                throw new IOException("\u767b\u5f55\u72b6\u6001\u5df2\u53d8\u5316\uff0c\u8bf7\u91cd\u8bd5");
            }
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            PlaylistEditDeadline.attachCurrent(connection);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("User-Agent", weapi ? WEAPI_UA : androidUserAgent());
            connection.setRequestProperty("Referer", MUSIC_HOST + "/");
            connection.setRequestProperty("Cookie", cookieHeader == null
                    ? requestCookieHeader(weapi) : cookieHeader);
            byte[] request = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            PlaylistEditDeadline.checkCurrent();
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            PlaylistEditDeadline.checkCurrent();
            int status = connection.getResponseCode();
            PlaylistEditDeadline.checkCurrent();
            storeResponseCookies(connection, requestGeneration);
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new IOException("网易云接口未返回数据（HTTP " + status + "）");
            byte[] bytes;
            try (InputStream stream = input) {
                bytes = readBounded(stream, MAX_RESPONSE_BYTES);
            }
            PlaylistEditDeadline.checkCurrent();
            try {
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException parseFailure) {
                throw new IOException("网易云接口返回无法识别的数据（HTTP " + status + "）");
            }
        } catch (Exception failure) {
            PlaylistEditDeadline.checkCurrent();
            throw failure;
        } finally {
            PlaylistEditDeadline.detachCurrent(connection);
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject requestGet(String endpoint) throws Exception {
        long requestGeneration = sessionGeneration.current();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json, text/plain, */*");
            connection.setRequestProperty("User-Agent", WEAPI_UA);
            connection.setRequestProperty("Referer", MUSIC_HOST + "/");
            connection.setRequestProperty("Cookie", requestCookieHeader(true));
            int status = connection.getResponseCode();
            storeResponseCookies(connection, requestGeneration);
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new IOException("网易云接口未返回数据（HTTP " + status + "）");
            byte[] bytes;
            try (InputStream stream = input) {
                bytes = readBounded(stream, MAX_RESPONSE_BYTES);
            }
            try {
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException parseFailure) {
                throw new IOException("网易云接口返回无法识别的数据（HTTP " + status + "）");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String requestCookieHeader(boolean encodeValues) throws JSONException {
        return requestMetadata().cookieHeader(encodeValues);
    }

    private RequestMetadata requestMetadata() throws JSONException {
        LinkedHashMap<String, String> values = parseCookies(getCookie());
        values.put("os", "android");
        values.put("appver", ANDROID_APP_VERSION);
        values.put("osver", Build.VERSION.RELEASE == null ? "14" : Build.VERSION.RELEASE);
        values.put("deviceId", deviceId());
        values.put("channel", ANDROID_CHANNEL);
        values.put("__remember_me", "true");
        values.put("versioncode", "140");
        values.put("buildver", String.valueOf(System.currentTimeMillis() / 1000L));
        values.put("requestId", System.currentTimeMillis() + "_"
                + String.format(Locale.US, "%04d", (int) (Math.random() * 1000)));

        JSONObject eapiHeader = new JSONObject();
        eapiHeader.put("osver", values.get("osver"));
        eapiHeader.put("deviceId", values.get("deviceId"));
        eapiHeader.put("os", values.get("os"));
        eapiHeader.put("appver", values.get("appver"));
        eapiHeader.put("versioncode", values.get("versioncode"));
        eapiHeader.put("mobilename", "");
        eapiHeader.put("buildver", values.get("buildver"));
        eapiHeader.put("resolution", "1920x1080");
        eapiHeader.put("__csrf", values.getOrDefault("__csrf", ""));
        eapiHeader.put("channel", values.get("channel"));
        eapiHeader.put("requestId", values.get("requestId"));
        for (String name : new String[] {"MUSIC_U", "MUSIC_A"}) {
            String value = values.get(name);
            if (value != null && !value.isEmpty()) eapiHeader.put(name, value);
        }
        return new RequestMetadata(values, eapiHeader);
    }

    private static String countryCode(JSONObject args) {
        String countryCode = args.optString("countrycode", "").trim();
        return countryCode.isEmpty() ? "86" : countryCode;
    }

    private static final class RequestMetadata {
        final LinkedHashMap<String, String> cookies;
        final JSONObject eapiHeader;

        RequestMetadata(LinkedHashMap<String, String> cookies, JSONObject eapiHeader) {
            this.cookies = cookies;
            this.eapiHeader = eapiHeader;
        }

        String cookieHeader(boolean encodeValues) {
            StringBuilder header = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (header.length() > 0) header.append("; ");
                String value = safeHeaderValue(entry.getValue());
                if (encodeValues) value = encodeURIComponent(value);
                header.append(entry.getKey()).append('=').append(value);
            }
            return header.toString();
        }

        String eapiCookieHeader() {
            StringBuilder header = new StringBuilder();
            java.util.Iterator<String> keys = eapiHeader.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (header.length() > 0) header.append("; ");
                header.append(key).append('=').append(encodeURIComponent(
                        safeHeaderValue(eapiHeader.optString(key, ""))));
            }
            return header.toString();
        }
    }

    private static String encodeURIComponent(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                    .replace("%21", "!").replace("%27", "'").replace("%28", "(")
                    .replace("%29", ")").replace("%7E", "~");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String deviceId() {
        String id = Settings.Secure.getString(appContext.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.trim().isEmpty()) id = UUID.randomUUID().toString().replace("-", "");
        return id;
    }

    private String androidUserAgent() {
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        return "NeteaseMusic/8.20.20.231215173437(8020200);Dalvik/2.1.0 (Linux; U; Android "
                + Build.VERSION.RELEASE + "; " + safeHeaderValue(model) + " Build/" + safeHeaderValue(Build.ID) + ")";
    }

    private void storeResponseCookies(HttpURLConnection connection, long requestGeneration) {
        List<String> received = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                received.addAll(entry.getValue());
            }
        }
        if (received.isEmpty()) return;
        synchronized (cookieLock) {
            if (!sessionGeneration.accepts(requestGeneration)) return;
            LinkedHashMap<String, String> merged = parseCookies(authStore.get("netease"));
            String previousMusicU = merged.get("MUSIC_U");
            boolean musicUSessionChanged = false;
            for (String header : received) {
                String first = header.split(";", 2)[0].trim();
                int separator = first.indexOf('=');
                if (separator <= 0) continue;
                String name = first.substring(0, separator).trim();
                String value = first.substring(separator + 1).trim();
                if (name.isEmpty() || isCookieAttribute(name)) continue;
                if ("MUSIC_U".equalsIgnoreCase(name)) {
                    if (previousMusicU == null ? !value.isEmpty() : !previousMusicU.equals(value)) {
                        musicUSessionChanged = true;
                    }
                    if (value.isEmpty() || "deleted".equalsIgnoreCase(value)) merged.remove(name);
                    else merged.put(name, value);
                } else {
                    merged.put(name, value);
                }
            }
            if (!merged.isEmpty()) authStore.put("netease", formatCookies(merged));
            else authStore.clear("netease");
            if (musicUSessionChanged) advanceSessionGeneration();
        }
    }

    private static LinkedHashMap<String, String> parseCookies(String cookie) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (cookie == null) return result;
        String[] parts = cookie.split(";");
        for (String part : parts) {
            int separator = part.indexOf('=');
            if (separator <= 0) continue;
            String name = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if (!name.isEmpty() && !isCookieAttribute(name)) result.put(name, value);
        }
        return result;
    }

    private static boolean isCookieAttribute(String name) {
        String lower = name.toLowerCase(Locale.US);
        return "path".equals(lower) || "domain".equals(lower) || "expires".equals(lower)
                || "max-age".equals(lower) || "samesite".equals(lower) || "secure".equals(lower)
                || "httponly".equals(lower);
    }

    private static String cleanCookieHeader(String cookie) {
        return formatCookies(parseCookies(cookie));
    }

    private static String formatCookies(LinkedHashMap<String, String> cookies) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(safeHeaderValue(entry.getValue()));
        }
        return result.toString();
    }

    private static String safeHeaderValue(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").replace(";", "");
    }

    private static String form(JSONObject values, boolean includeRsa) throws Exception {
        StringBuilder result = new StringBuilder();
        result.append("params=").append(URLEncoder.encode(values.optString("params"), "UTF-8"));
        if (includeRsa) {
            result.append("&encSecKey=").append(URLEncoder.encode(values.optString("encSecKey"), "UTF-8"));
        }
        return result.toString();
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maxBytes) throw new IOException("网易云响应超过大小限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String requireId(String id) {
        if (id == null || !id.matches("[0-9]{1,24}")) {
            throw new IllegalArgumentException("网易云内容 ID 无效");
        }
        return id;
    }

    private static String numericIdList(String values) {
        if (values == null || values.trim().isEmpty()) throw new IllegalArgumentException("歌曲 ID 为空");
        String[] ids = values.split(",");
        StringBuilder result = new StringBuilder();
        for (String id : ids) {
            if (result.length() > 0) result.append(',');
            result.append(requireId(id.trim()));
        }
        return result.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static JSONObject sanitize(JSONObject source) throws JSONException {
        JSONObject result = new JSONObject();
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (isSensitiveResponseKey(key)) continue;
            Object value = source.opt(key);
            if (value instanceof JSONObject) value = sanitize((JSONObject) value);
            else if (value instanceof JSONArray) value = sanitize((JSONArray) value);
            result.put(key, value);
        }
        return result;
    }

    private static JSONArray sanitize(JSONArray source) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            Object value = source.opt(i);
            if (value instanceof JSONObject) value = sanitize((JSONObject) value);
            else if (value instanceof JSONArray) value = sanitize((JSONArray) value);
            result.put(value);
        }
        return result;
    }

    private static boolean isSensitiveResponseKey(String key) {
        String normalized = key.toLowerCase(Locale.US);
        return "cookie".equals(normalized) || "set-cookie".equals(normalized)
                || "music_u".equals(normalized) || "music_a".equals(normalized)
                || "__csrf".equals(normalized) || "csrf_token".equals(normalized)
                || "token".equals(normalized) || "tokeninfo".equals(normalized)
                || "access_token".equals(normalized) || "refresh_token".equals(normalized);
    }

    private static Exception apiError(String fallback, JSONObject response) {
        String message = response.optString("msg", response.optString("message", ""));
        int code = response.optInt("code", 0);
        String detail = message.isEmpty() ? fallback : message;
        if (code != 0) detail += "（" + code + "）";
        return new IOException(detail);
    }
}
