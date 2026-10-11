package dev.yesplaymusic.probe;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Ports the installed desktop player's QQ compatibility route; no external proxy. */
public final class QQSource {
    private static QQSource instance;
    private final AuthStore sessions;
    private long refreshRetryAt;
    private long sessionGeneration;
    private static final long FAVORITE_CACHE_TTL_MS = 30000L;
    private static final long FAVORITE_OPERATION_TIMEOUT_MS = 20000L;
    private long favoriteCacheGeneration = -1;
    private long favoriteCacheExpiresAt;
    private boolean favoriteCacheLoaded;
    private boolean favoriteCacheLoading;
    private boolean favoriteMutationInFlight;
    private final Set<String> favoriteMids = new HashSet<>();
    private final Set<String> favoriteIds = new HashSet<>();
    private static final String[] COOKIE_NAMES = {"uin","wxuin","qqmusic_key","qm_keyst","psrf_qqrefresh_token","qqrefresh_token","psrf_musickey_createtime","psrf_qqaccess_token","psrf_access_token","qqaccess_token","psrf_access_token_expiresAt","psrf_qqopenid","psrf_qqunionid","login_type","tmeLoginType","euin","music_ignore_pskey","wxopenid","wxunionid","wxrefresh_token"};
    private QQSource(Context context) { sessions = new AuthStore(context); }
    public static synchronized QQSource get(Context context) { if (instance == null) instance = new QQSource(context.getApplicationContext()); return instance; }
    public String getCookie() { return sessions.get("qq"); }
    public synchronized boolean setCookie(String raw) {
        Map<String,String> values = fields(raw); String key = first(values,"qqmusic_key","qm_keyst");
        String uin = first(values,"uin","wxuin").replaceFirst("^o0*", "");
        if (!uin.matches("[1-9][0-9]*") || key.isEmpty()) return false;
        values.put("uin",uin); values.put("qqmusic_key",key); values.put("qm_keyst",key);
        StringBuilder result = new StringBuilder();
        for (String name : COOKIE_NAMES) { String value=values.get(name); if (value!=null && !value.isEmpty() && !value.matches(".*[\\r\\n\\x00-\\x20].*")) { if(result.length()>0)result.append("; ");result.append(name).append('=').append(value); } }
        sessions.put("qq",result.toString()); sessionGeneration++; refreshRetryAt=0; invalidateFavoriteCacheLocked(); return true;
    }
    private static Map<String,String> fields(String cookie) {
        Map<String,String> map=new LinkedHashMap<>();
        for(String item:cookie.split(";")){int at=item.indexOf('=');if(at>0)map.put(item.substring(0,at).trim(),item.substring(at+1).trim());} return map;
    }
    private static String first(Map<String,String> values,String...keys){for(String key:keys){String val=values.get(key);if(val!=null&&!val.isEmpty())return val;}return "";}
    private static JSONObject request(String module,String method,JSONObject param,String cookie) throws Exception {
        JSONObject body = new JSONObject().put("req_0",new JSONObject().put("module",module).put("method",method).put("param",param));
        JSONObject result = getJson("https://u.y.qq.com/cgi-bin/musicu.fcg?data="+URLEncoder.encode(body.toString(),"UTF-8"),cookie);
        JSONObject reply = result.optJSONObject("req_0");
        if (result.optInt("code",0)!=0 || reply==null || reply.optInt("code",0)!=0) throw new Exception("QQ 音乐接口暂不可用，请稍后重试");
        JSONObject data=reply.optJSONObject("data"); return data==null?new JSONObject():data;
    }
    private static JSONObject getJson(String url,String cookie) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Referer","https://y.qq.com/");connection.setRequestProperty("Origin","https://y.qq.com");
        connection.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
        if(!cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
        try { int status=connection.getResponseCode(); if(status!=200)throw new Exception("QQ 音乐连接失败（"+status+"）");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>8*1024*1024)throw new Exception("QQ 音乐返回内容过大");bytes.write(buffer,0,n);}return new JSONObject(new String(bytes.toByteArray(),"UTF-8"));
            }
        } finally {connection.disconnect();}
    }
    private String freshCookie() {
        final String original, uin, key, token;
        final long generation;
        final Map<String,String> map;
        synchronized(this) {
            original=getCookie(); map=fields(original);
            uin=first(map,"uin","wxuin"); key=first(map,"qqmusic_key","qm_keyst"); token=first(map,"psrf_qqrefresh_token","qqrefresh_token");
            if(original.isEmpty() || token.isEmpty() || key.isEmpty() || System.currentTimeMillis()<refreshRetryAt)return original;
            long created=0;try{created=Long.parseLong(first(map,"psrf_musickey_createtime"))*1000;}catch(Exception ignored){}
            if(created>0 && created+259200000L>System.currentTimeMillis()+600000L)return original;
            generation=sessionGeneration;
            // Only one refresh runs at a time; do not hold the session lock during network I/O.
            refreshRetryAt=System.currentTimeMillis()+300000L;
        }
        try {
            JSONObject result=request("music.login.LoginServer","Login",new JSONObject().put("refresh_token",token).put("musickey",key).put("musicid",Long.parseLong(uin)),original);
            String newKey=result.optString("musickey"); long time=result.optLong("musickeyCreateTime");
            if(newKey.isEmpty() || time*1000+259200000L<=System.currentTimeMillis()+600000L)throw new Exception("Expired session");
            map.put("qm_keyst",newKey);map.put("qqmusic_key",newKey);map.put("psrf_musickey_createtime",String.valueOf(time));
            if(!result.optString("refresh_token").isEmpty())map.put("psrf_qqrefresh_token",result.optString("refresh_token"));
            synchronized(this) {
                if(sessionGeneration!=generation || !getCookie().equals(original))return getCookie();
                StringBuilder merged=new StringBuilder();for(Map.Entry<String,String> item:map.entrySet())merged.append(item.getKey()).append('=').append(item.getValue()).append("; ");
                setCookie(merged.toString());return getCookie();
            }
        } catch(Exception error){return getCookie();}
    }
    private JSONArray searchRaw(String query,int page,int limit,String cookie) throws Exception {
        JSONObject data=request("music.search.SearchCgiService","DoSearchForQQMusicDesktop",new JSONObject().put("num_per_page",Math.min(50,Math.max(1,limit))).put("page_num",Math.max(1,page)).put("query",query).put("search_type",0),cookie);
        JSONObject body=data.optJSONObject("body"), songs=body==null?null:body.optJSONObject("song");JSONArray list=songs==null?null:songs.optJSONArray("list");return list==null?new JSONArray():list;
    }
    public JSONObject execute(String method,JSONObject args) throws Exception {
        if("qqStatus".equals(method)){
            Map<String,String> values=fields(getCookie());return new JSONObject().put("code",200).put("loggedIn",!first(values,"qqmusic_key","qm_keyst").isEmpty()).put("canRefresh",!first(values,"psrf_qqrefresh_token","qqrefresh_token").isEmpty());
        }
        if("qqLogout".equals(method)){synchronized(this){sessions.clear("qq");sessionGeneration++;refreshRetryAt=0;invalidateFavoriteCacheLocked();}return new JSONObject().put("code",200);}
        if("qqLikeStatus".equals(method)) return executeLikeStatus(args);
        if("qqSetLiked".equals(method)) return executeSetLiked(args);
        if("qqSearch".equals(method)){
            String query=args.optString("query",args.optString("keywords","")).trim();if(query.isEmpty())return new JSONObject().put("code",200).put("result",new JSONObject().put("songs",new JSONArray()).put("songCount",0));
            JSONArray raw=searchRaw(query,args.optInt("page",1),args.optInt("limit",30),freshCookie()), songs=new JSONArray();
            for(int i=0;i<raw.length();i++)songs.put(toTrack(raw.getJSONObject(i)));
            return new JSONObject().put("code",200).put("result",new JSONObject().put("songs",songs).put("songCount",songs.length()));
        }
        if("qqLyrics".equals(method)){
            String mid=args.optString("qqMid",args.optString("mid",args.optString("id",""))).replaceFirst("^qq:","");
            JSONObject data=getJson("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="+URLEncoder.encode(mid,"UTF-8")+"&format=json&nobase64=1",freshCookie());
            return new JSONObject().put("code",200).put("lrc",new JSONObject().put("lyric",lyricText(data.optString("lyric")))).put("tlyric",new JSONObject().put("lyric",lyricText(data.optString("trans"))));
        }
        throw new Exception("不支持的 QQ 音乐操作");
    }
    private JSONObject executeLikeStatus(JSONObject args) {
        String mid = requestedMid(args);
        FavoriteDeadline deadline = new FavoriteDeadline(FAVORITE_OPERATION_TIMEOUT_MS);
        AuthSession session = authenticatedSession();
        if (session == null) {
            try { return new JSONObject().put("code", 200).put("loggedIn", false).put("liked", false); }
            catch (Exception impossible) { return new JSONObject(); }
        }
        long id = numericArgumentId(args);
        if (mid.isEmpty() && id <= 0) return favoriteFailure(400, "缺少 QQ 曲目标识，无法查询收藏状态。", true);
        if (!mid.isEmpty() && !validMid(mid)) return favoriteFailure(400, "QQ 曲目 MID 格式无效，无法查询收藏状态。", true);
        try {
            FavoriteSnapshot snapshot = loadFavoriteSnapshot(session, deadline);
            boolean liked = snapshot.contains(mid, id);
            JSONObject result = new JSONObject().put("code", 200).put("loggedIn", true).put("liked", liked);
            if (!mid.isEmpty()) result.put("qqMid", mid);
            return result;
        } catch (Exception error) {
            return favoriteFailure(error, false, isLoggedIn());
        }
    }

    private JSONObject executeSetLiked(JSONObject args) {
        FavoriteDeadline deadline = new FavoriteDeadline(FAVORITE_OPERATION_TIMEOUT_MS);
        Object likedValue = args == null ? null : args.opt("liked");
        if (!(likedValue instanceof Boolean)) return favoriteFailure(400, "收藏请求缺少有效的 liked 布尔值。", false);
        boolean desiredLiked = (Boolean) likedValue;
        String mid = requestedMid(args);
        if (mid.isEmpty()) return favoriteFailure(400, "缺少 QQ 曲目 MID，无法修改收藏。", false);
        if (!validMid(mid)) return favoriteFailure(400, "QQ 曲目 MID 格式无效，无法修改收藏。", false);
        AuthSession session = authenticatedSession();
        if (session == null) return favoriteFailure(401, "QQ 音乐尚未登录，请先登录后收藏。", false);

        boolean mutationStarted = false;
        try {
            beginFavoriteMutation(session, deadline);
            mutationStarted = true;
            FavoriteSnapshot before = loadFavoriteSnapshotForMutation(session, deadline);
            long requestedId = numericArgumentId(args);
            boolean wasLiked = before.contains(mid, requestedId);
            if (!QQFavoriteProtocol.needsWrite(wasLiked, desiredLiked)) {
                return new JSONObject().put("code", 200).put("loggedIn", true).put("liked", wasLiked).put("qqMid", mid);
            }

            deadline.check();
            SongWriteInfo song = resolveSongWriteInfo(session, mid, args);
            deadline.check();
            String action = QQFavoriteProtocol.writeMethod(desiredLiked);
            JSONObject param = new JSONObject()
                    .put("dirId", 201)
                    .put("tid", 0)
                    .put("bFmtUtf8", true)
                    .put("v_songInfo", new JSONArray().put(new JSONObject().put("songId", song.id).put("songType", song.type)));
            MusicuResponse response = requestAuthenticatedRaw(session, "music.musicasset.PlaylistDetailWrite", action, param);
            deadline.check();
            int retCode = response.data == null ? -1 : response.data.optInt("retCode", -1);
            if (!response.hasOuterCode || !response.hasBlockCode) {
                throw new QQFavoriteProtocol.Failure(502, action + " response is missing a required code");
            }
            QQFavoriteProtocol.requireWriteSuccess(response.httpStatus, response.outerCode, response.blockCode, retCode, action);

            invalidateFavoriteCache();
            assertSessionCurrent(session);
            FavoriteSnapshot verified = fetchFavoriteSnapshot(session, deadline);
            boolean actualLiked = verified.contains(mid, song.id);
            cacheFavoriteSnapshot(session, verified);
            if (actualLiked != desiredLiked) {
                return new JSONObject().put("code", 409).put("loggedIn", true).put("liked", actualLiked)
                        .put("message", "QQ 音乐已响应收藏请求，但回读结果与目标状态不一致；请刷新后确认。");
            }
            return new JSONObject().put("code", 200).put("loggedIn", true).put("liked", actualLiked).put("qqMid", mid);
        } catch (Exception error) {
            invalidateFavoriteCache();
            return favoriteFailure(error, true, isLoggedIn());
        } finally {
            if (mutationStarted) endFavoriteMutation();
        }
    }

    private AuthSession authenticatedSession() {
        freshCookie();
        synchronized (this) {
            String cookie = getCookie();
            Map<String,String> values = fields(cookie);
            String uin = first(values, "uin", "wxuin").replaceFirst("^o0*", "");
            String key = first(values, "qqmusic_key", "qm_keyst");
            if (!uin.matches("[1-9][0-9]*") || key.isEmpty()) return null;
            return new AuthSession(cookie, uin, key, sessionGeneration);
        }
    }

    private FavoriteSnapshot loadFavoriteSnapshot(AuthSession session, FavoriteDeadline deadline) throws Exception {
        while (true) {
            synchronized (this) {
                deadline.check();
                assertSessionCurrentLocked(session);
                if (hasFreshFavoriteCacheLocked(session)) return copyFavoriteCacheLocked();
                if (!favoriteCacheLoading && !favoriteMutationInFlight) {
                    favoriteCacheLoading = true;
                    break;
                }
                waitForFavoriteWorkLocked(deadline);
            }
        }
        FavoriteSnapshot loaded;
        try {
            loaded = fetchFavoriteSnapshot(session, deadline);
        } catch (Exception error) {
            synchronized (this) {
                favoriteCacheLoading = false;
                notifyAll();
            }
            throw error;
        }
        synchronized (this) {
            favoriteCacheLoading = false;
            notifyAll();
            assertSessionCurrentLocked(session);
            storeFavoriteCacheLocked(session, loaded);
            return copyFavoriteCacheLocked();
        }
    }

    private FavoriteSnapshot loadFavoriteSnapshotForMutation(AuthSession session, FavoriteDeadline deadline) throws Exception {
        synchronized (this) {
            deadline.check();
            assertSessionCurrentLocked(session);
            if (hasFreshFavoriteCacheLocked(session)) return copyFavoriteCacheLocked();
        }
        FavoriteSnapshot loaded = fetchFavoriteSnapshot(session, deadline);
        synchronized (this) {
            assertSessionCurrentLocked(session);
            storeFavoriteCacheLocked(session, loaded);
            return copyFavoriteCacheLocked();
        }
    }

    private void beginFavoriteMutation(AuthSession session, FavoriteDeadline deadline) throws Exception {
        synchronized (this) {
            while (favoriteMutationInFlight || favoriteCacheLoading) waitForFavoriteWorkLocked(deadline);
            deadline.check();
            assertSessionCurrentLocked(session);
            favoriteMutationInFlight = true;
        }
    }

    private void endFavoriteMutation() {
        synchronized (this) {
            favoriteMutationInFlight = false;
            notifyAll();
        }
    }

    private void waitForFavoriteWorkLocked(FavoriteDeadline deadline) throws Exception {
        try {
            wait(deadline.remainingWaitMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new QQFavoriteProtocol.Failure(503, "favorite request interrupted");
        }
        deadline.check();
    }

    private FavoriteSnapshot fetchFavoriteSnapshot(AuthSession session, FavoriteDeadline deadline) throws Exception {
        FavoriteSnapshot result = new FavoriteSnapshot();
        int offset = 0;
        int loaded = 0;
        int total = -1;
        for (int page = 1; ; page++) {
            deadline.check();
            QQFavoriteProtocol.validatePageCount(page);
            assertSessionCurrent(session);
            JSONObject param = new JSONObject()
                    .put("disstid", 0)
                    .put("dirid", 201)
                    .put("tag", true)
                    .put("song_begin", offset)
                    .put("song_num", QQFavoriteProtocol.PAGE_SIZE)
                    .put("userinfo", true)
                    .put("orderlist", true);
            JSONObject data = requestAuthenticatedData(session, "music.srfDissInfo.DissInfo", "CgiGetDiss", param);
            deadline.check();
            if (page == 1) total = readTotalSongCount(data);
            if (total > QQFavoriteProtocol.MAX_SONGS) {
                throw new QQFavoriteProtocol.Failure(502, "liked playlist exceeds the safe page limit");
            }
            JSONArray songs = data.optJSONArray("songlist");
            if (songs == null) {
                if (total == 0 && readHasMore(data) == 0) return result;
                throw new QQFavoriteProtocol.Failure(502, "QQ liked playlist response is missing songlist");
            }
            for (int i = 0; i < songs.length(); i++) {
                JSONObject entry = songs.optJSONObject(i);
                if (entry == null) throw new QQFavoriteProtocol.Failure(502, "QQ liked playlist contains an invalid song");
                JSONObject track = unwrapSong(entry);
                String mid = firstString(track, "songmid", "songMid", "mid", "MID");
                String id = positiveIdString(firstString(track, "songId", "songid", "id", "qqId"));
                if (mid.isEmpty() && id.isEmpty()) {
                    throw new QQFavoriteProtocol.Failure(502, "QQ liked playlist song has no usable identity");
                }
                if (!mid.isEmpty()) result.mids.add(QQFavoriteProtocol.normalizeMid(mid));
                if (!id.isEmpty()) result.ids.add(id);
            }
            loaded += songs.length();
            if (loaded > QQFavoriteProtocol.MAX_SONGS) {
                throw new QQFavoriteProtocol.Failure(502, "liked playlist exceeds the safe page limit");
            }
            int hasMore = readHasMore(data);
            boolean next = QQFavoriteProtocol.shouldFetchNextPage(total, loaded, hasMore, songs.length());
            if (!next) return result;
            offset += songs.length();
        }
    }

    private static int readTotalSongCount(JSONObject data) throws QQFavoriteProtocol.Failure {
        Object value = data.has("total_song_num") ? data.opt("total_song_num") : data.opt("total");
        if (value == null) return -1;
        try {
            int total = value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value));
            return total < 0 ? -1 : total;
        } catch (Exception ignored) {
            throw new QQFavoriteProtocol.Failure(502, "QQ liked playlist returned an invalid total");
        }
    }

    private static int readHasMore(JSONObject data) throws QQFavoriteProtocol.Failure {
        if (!data.has("hasmore")) return -1;
        Object value = data.opt("hasmore");
        if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
        if (value instanceof Number) {
            int flag = ((Number) value).intValue();
            if (flag == 0 || flag == 1) return flag;
            throw new QQFavoriteProtocol.Failure(502, "QQ liked playlist returned an invalid hasmore flag");
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return 1;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return 0;
        throw new QQFavoriteProtocol.Failure(502, "QQ liked playlist returned an invalid hasmore flag");
    }

    private SongWriteInfo resolveSongWriteInfo(AuthSession session, String mid, JSONObject args) throws Exception {
        JSONObject param = new JSONObject().put("song_mid", mid);
        JSONObject data = requestAuthenticatedData(session, "music.pf_song_detail_svr", "get_song_detail", param);
        JSONObject track = data.optJSONObject("track_info");
        if (track == null) track = data.optJSONObject("track");
        if (track == null) throw new QQFavoriteProtocol.Failure(502, "QQ song detail response is missing track_info");
        String detailMid = QQFavoriteProtocol.normalizeMid(firstString(track, "mid", "songmid", "song_mid", "songMid"));
        if (!detailMid.isEmpty() && !mid.equals(detailMid)) {
            throw new QQFavoriteProtocol.Failure(409, "QQ song detail MID did not match the requested track");
        }
        long id = positiveLong(track, "id", "songId", "song_id", "songid", "qqId");
        int type = integerField(track, "songType", "songtype", "song_type", "type", "Type");
        if (id <= 0) throw new QQFavoriteProtocol.Failure(400, "QQ song detail has no numeric song ID");
        if (type < 0) throw new QQFavoriteProtocol.Failure(400, "QQ song detail has no numeric song type");
        long callerId = numericArgumentId(args);
        if (callerId > 0 && callerId != id) {
            throw new QQFavoriteProtocol.Failure(409, "provided QQ song ID did not match the song MID");
        }
        return new SongWriteInfo(id, type);
    }

    private MusicuResponse requestAuthenticatedRaw(AuthSession session, String module, String method, JSONObject param) throws Exception {
        assertSessionCurrent(session);
        int gtk = hash33(session.key);
        String loginType = session.key.startsWith("W_X") ? "1" : "2";
        JSONObject comm = new JSONObject()
                .put("ct", 24)
                .put("cv", 4747474)
                .put("platform", "yqq.json")
                .put("uin", session.uin)
                .put("g_tk", gtk)
                .put("g_tk_new_20200303", gtk)
                .put("format", "json")
                .put("inCharset", "utf-8")
                .put("outCharset", "utf-8")
                .put("notice", 0)
                .put("need_new_code", 1)
                .put("authst", session.key)
                .put("tmeAppID", "qqmusic")
                .put("tmeLoginType", loginType);
        JSONObject request = new JSONObject().put("module", module).put("method", method).put("param", param);
        JSONObject body = new JSONObject().put("comm", comm).put("req_0", request);
        HttpURLConnection connection = (HttpURLConnection) new URL("https://u.y.qq.com/cgi-bin/musicu.fcg").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Referer", "https://y.qq.com/");
        connection.setRequestProperty("Origin", "https://y.qq.com");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
        connection.setRequestProperty("Cookie", session.cookie);
        try {
            byte[] payload = body.toString().getBytes("UTF-8");
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            int httpStatus = connection.getResponseCode();
            if (httpStatus != 200) QQFavoriteProtocol.requireWriteSuccess(httpStatus, 0, 0, 0, method);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 8 * 1024 * 1024) throw new QQFavoriteProtocol.Failure(502, "QQ response exceeded the safe size limit");
                    bytes.write(buffer, 0, count);
                }
                JSONObject root = new JSONObject(new String(bytes.toByteArray(), "UTF-8"));
                JSONObject block = root.optJSONObject("req_0");
                JSONObject data = block == null ? null : block.optJSONObject("data");
                return new MusicuResponse(httpStatus, root.has("code"), strictCode(root, "code"),
                        block != null && block.has("code"), block == null ? Integer.MIN_VALUE : strictCode(block, "code"), data);
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject requestAuthenticatedData(AuthSession session, String module, String method, JSONObject param) throws Exception {
        MusicuResponse response = requestAuthenticatedRaw(session, module, method, param);
        if (!response.hasOuterCode) throw new QQFavoriteProtocol.Failure(502, method + " response is missing the outer code");
        if (response.outerCode != 0) throw new QQFavoriteProtocol.Failure(response.outerCode, method + " outer code " + response.outerCode);
        if (!response.hasBlockCode) throw new QQFavoriteProtocol.Failure(502, method + " response is missing the module code");
        if (response.blockCode != 0) throw new QQFavoriteProtocol.Failure(response.blockCode, method + " module code " + response.blockCode);
        if (response.data == null) throw new QQFavoriteProtocol.Failure(502, method + " response is missing data");
        assertSessionCurrent(session);
        return response.data;
    }

    private static int strictCode(JSONObject object, String key) throws QQFavoriteProtocol.Failure {
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); } catch (NumberFormatException ignored) {}
        }
        throw new QQFavoriteProtocol.Failure(502, "QQ response contains an invalid " + key);
    }

    private void assertSessionCurrent(AuthSession session) throws QQFavoriteProtocol.Failure {
        synchronized (this) { assertSessionCurrentLocked(session); }
    }

    private void assertSessionCurrentLocked(AuthSession session) throws QQFavoriteProtocol.Failure {
        if (sessionGeneration != session.generation || !getCookie().equals(session.cookie)) {
            throw new QQFavoriteProtocol.Failure(409, "QQ session changed during favorite operation");
        }
    }

    private boolean hasFreshFavoriteCacheLocked(AuthSession session) {
        return favoriteCacheLoaded && favoriteCacheGeneration == session.generation
                && favoriteCacheExpiresAt > System.currentTimeMillis() && getCookie().equals(session.cookie);
    }

    private FavoriteSnapshot copyFavoriteCacheLocked() {
        return new FavoriteSnapshot(favoriteMids, favoriteIds);
    }

    private void storeFavoriteCacheLocked(AuthSession session, FavoriteSnapshot snapshot) {
        favoriteMids.clear();
        favoriteMids.addAll(snapshot.mids);
        favoriteIds.clear();
        favoriteIds.addAll(snapshot.ids);
        favoriteCacheGeneration = session.generation;
        favoriteCacheExpiresAt = System.currentTimeMillis() + FAVORITE_CACHE_TTL_MS;
        favoriteCacheLoaded = true;
    }

    private void cacheFavoriteSnapshot(AuthSession session, FavoriteSnapshot snapshot) throws Exception {
        synchronized (this) {
            assertSessionCurrentLocked(session);
            storeFavoriteCacheLocked(session, snapshot);
        }
    }

    private void invalidateFavoriteCache() {
        synchronized (this) { invalidateFavoriteCacheLocked(); }
    }

    private void invalidateFavoriteCacheLocked() {
        favoriteCacheLoaded = false;
        favoriteCacheGeneration = -1;
        favoriteCacheExpiresAt = 0L;
        favoriteMids.clear();
        favoriteIds.clear();
    }

    private boolean isLoggedIn() {
        Map<String,String> values = fields(getCookie());
        String uin = first(values, "uin", "wxuin").replaceFirst("^o0*", "");
        return uin.matches("[1-9][0-9]*") && !first(values, "qqmusic_key", "qm_keyst").isEmpty();
    }

    private static String requestedMid(JSONObject args) {
        if (args == null) return "";
        String mid = QQFavoriteProtocol.normalizeMid(args.optString("qqMid", ""));
        if (!mid.isEmpty()) return mid;
        String id = args.optString("id", "");
        return id.startsWith("qq:") ? QQFavoriteProtocol.normalizeMid(id) : "";
    }

    private static boolean validMid(String mid) {
        return mid != null && mid.length() <= 64 && mid.matches("[A-Za-z0-9_-]+");
    }

    private static long numericArgumentId(JSONObject args) {
        if (args == null) return -1L;
        String value = args.optString("qqId", args.optString("id", "")).trim();
        return parsePositiveLong(value);
    }

    private static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) return text;
        }
        return "";
    }

    private static JSONObject unwrapSong(JSONObject raw) {
        for (String key : new String[]{"Track", "track", "songInfo", "song_info", "Song", "song"}) {
            JSONObject nested = raw.optJSONObject(key);
            if (nested != null) return nested;
        }
        return raw;
    }

    private static String positiveIdString(String value) {
        long id = parsePositiveLong(value);
        return id > 0 ? String.valueOf(id) : "";
    }

    private static long positiveLong(JSONObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key)) continue;
            long value = parsePositiveLong(String.valueOf(object.opt(key)));
            if (value > 0) return value;
        }
        return -1L;
    }

    private static long parsePositiveLong(String value) {
        if (value == null || !value.matches("[0-9]+")) return -1L;
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static int integerField(JSONObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key)) continue;
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) {
                try { return Integer.parseInt((String) value); } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private static int hash33(String value) {
        long hash = 5381L;
        for (int i = 0; i < value.length(); i++) hash = ((hash << 5) + hash + value.charAt(i)) & 0x7fffffffL;
        return (int) hash;
    }

    private static JSONObject favoriteFailure(Exception error, boolean write, boolean loggedIn) {
        int code = error instanceof QQFavoriteProtocol.Failure
                ? ((QQFavoriteProtocol.Failure) error).getResultCode() : 502;
        String message;
        if (code == 80105) {
            message = "QQ 音乐拒绝了收藏操作（业务码 80105），收藏状态未确认。";
        } else if (code == 401 || code == 403) {
            message = "QQ 音乐登录状态无效，请重新登录后重试。";
        } else if (code == 400) {
            message = write ? "曲目信息不足，无法安全地修改 QQ 收藏。" : "缺少有效的 QQ 曲目标识，无法读取收藏状态。";
        } else if (code == 409) {
            message = "QQ 登录状态或收藏回读在操作期间发生变化，请刷新后重试。";
        } else if (write) {
            message = "QQ 音乐未确认这次收藏操作（错误码 " + code + "），请刷新状态后重试。";
        } else {
            message = "读取 QQ 音乐收藏状态失败（错误码 " + code + "）。";
        }
        JSONObject result = new JSONObject();
        try {
            return result.put("code", code == 200 ? 502 : code).put("loggedIn", loggedIn).put("message", message);
        } catch (Exception impossible) {
            return new JSONObject();
        }
    }

    private static JSONObject favoriteFailure(int code, String message, boolean loggedIn) {
        try { return new JSONObject().put("code", code).put("loggedIn", loggedIn).put("message", message); }
        catch (Exception impossible) { return new JSONObject(); }
    }

    private static final class FavoriteDeadline {
        final long deadlineNanos;
        FavoriteDeadline(long timeoutMs) {
            deadlineNanos = System.nanoTime() + timeoutMs * 1000000L;
        }
        void check() throws QQFavoriteProtocol.Failure {
            if (deadlineNanos - System.nanoTime() <= 0L) {
                throw new QQFavoriteProtocol.Failure(503, "QQ favorite operation timed out");
            }
        }
        long remainingWaitMillis() throws QQFavoriteProtocol.Failure {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) throw new QQFavoriteProtocol.Failure(503, "QQ favorite operation timed out");
            return Math.max(1L, (remainingNanos + 999999L) / 1000000L);
        }
    }

    private static final class AuthSession {
        final String cookie;
        final String uin;
        final String key;
        final long generation;
        AuthSession(String cookie, String uin, String key, long generation) {
            this.cookie = cookie; this.uin = uin; this.key = key; this.generation = generation;
        }
    }

    private static final class FavoriteSnapshot {
        final Set<String> mids = new HashSet<>();
        final Set<String> ids = new HashSet<>();
        FavoriteSnapshot() {}
        FavoriteSnapshot(Set<String> mids, Set<String> ids) { this.mids.addAll(mids); this.ids.addAll(ids); }
        boolean contains(String mid, long id) {
            String normalized = QQFavoriteProtocol.normalizeMid(mid);
            return (!normalized.isEmpty() && mids.contains(normalized)) || (id > 0 && ids.contains(String.valueOf(id)));
        }
    }

    private static final class SongWriteInfo {
        final long id;
        final int type;
        SongWriteInfo(long id, int type) { this.id = id; this.type = type; }
    }

    private static final class MusicuResponse {
        final int httpStatus;
        final boolean hasOuterCode;
        final int outerCode;
        final boolean hasBlockCode;
        final int blockCode;
        final JSONObject data;
        MusicuResponse(int httpStatus, boolean hasOuterCode, int outerCode, boolean hasBlockCode, int blockCode, JSONObject data) {
            this.httpStatus = httpStatus; this.hasOuterCode = hasOuterCode; this.outerCode = outerCode; this.hasBlockCode = hasBlockCode; this.blockCode = blockCode; this.data = data;
        }
    }

    private static String lyricText(String text){
        if(!text.contains("[")&&!text.contains("&#")){try{text=new String(Base64.decode(text,Base64.DEFAULT),"UTF-8");}catch(Exception ignored){}}
        return text.replace("&#10;","\n").replace("&#13;","").replace("&#58;",":").replace("&#46;",".").replace("&#32;"," ").replace("&#39;","'").replace("&apos;","'").replace("&quot;","\"").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">");
    }
    private static JSONObject toTrack(JSONObject song) throws Exception {
        JSONArray singers=song.optJSONArray("singer");StringBuilder artist=new StringBuilder();if(singers!=null)for(int i=0;i<singers.length();i++){if(i>0)artist.append(" / ");artist.append(singers.getJSONObject(i).optString("name"));}
        JSONObject album=song.optJSONObject("album"), file=song.optJSONObject("file");String mid=song.optString("mid",song.optString("songmid"));String albumMid=album==null?"":album.optString("mid");
        return new JSONObject().put("id","qq:"+mid).put("qqMid",mid).put("provider","qq").put("title",song.optString("title",song.optString("name"))).put("artist",artist.toString()).put("album",album==null?"":album.optString("name")).put("artwork",albumMid.isEmpty()?"":"https://y.gtimg.cn/music/photo_new/T002R800x800M000"+albumMid+".jpg").put("duration",song.optLong("interval")*1000).put("mediaMid",file==null?mid:file.optString("media_mid",mid));
    }
    public JSONObject resolve(JSONObject track) throws Exception {
        String cookie=freshCookie();JSONObject match=null;String mid=track.optString("qqMid");
        if(mid.isEmpty()&&track.optString("id").startsWith("qq:"))mid=track.optString("id").substring(3);
        if(!mid.isEmpty()){
            try { JSONObject detail=request("music.pf_song_detail_svr","get_song_detail",new JSONObject().put("song_mid",mid),cookie);match=detail.optJSONObject("track_info"); } catch(Exception ignored){}
            if(match==null)match=new JSONObject().put("mid",mid).put("file",new JSONObject().put("media_mid",track.optString("mediaMid",mid)));
        } else {
            JSONArray songs=searchRaw(track.optString("title")+" "+track.optString("artist"),1,10,cookie);int best=-1;
            for(int i=0;i<songs.length();i++){
                JSONObject candidate=songs.getJSONObject(i), normalized=toTrack(candidate);
                int score=QQMatch.score(track.optString("title"),track.optString("artist"),track.optLong("duration"),normalized.optString("title"),normalized.optString("artist"),normalized.optLong("duration"));
                if(score>best){best=score;match=candidate;}
            }
        }
        if(match==null)throw new Exception("QQ 音乐未找到相符版本，可在 QQ 曲库中搜索");
        mid=match.optString("mid");JSONObject file=match.optJSONObject("file");String mediaMid=file==null?mid:file.optString("media_mid",mid);
        Map<String,String> values=fields(cookie);String uin=first(values,"uin","wxuin");if(uin.isEmpty())uin="0";
        String guid=String.valueOf(1000000+new Random().nextInt(8999999));
        for(String[] quality:new String[][]{{"M800",".mp3","320k"},{"M500",".mp3","128k"},{"C400",".m4a","AAC"}}){
            JSONObject param=new JSONObject().put("guid",guid).put("loginflag",1).put("filename",new JSONArray().put(quality[0]+mediaMid+quality[1])).put("songmid",new JSONArray().put(mid)).put("songtype",new JSONArray().put(0)).put("uin",uin).put("platform","20");
            JSONObject data=request("vkey.GetVkeyServer","CgiGetVkey",param,cookie);JSONArray info=data.optJSONArray("midurlinfo"),sip=data.optJSONArray("sip");
            String path=info!=null&&info.length()>0?info.getJSONObject(0).optString("purl"):"";
            if(!path.isEmpty()&&sip!=null&&sip.length()>0){
                URL resolved=new URL(new URL(sip.getString(0)),path);String host=resolved.getHost().toLowerCase(java.util.Locale.ROOT);
                if(!(host.endsWith(".qq.com")||host.endsWith(".qqmusic.qq.com")||host.endsWith(".gtimg.cn")))throw new Exception("QQ 音乐返回了未知播放地址");
                String url=resolved.toString().replaceFirst("^http:","https:");
                return new JSONObject().put("url",url).put("source","qq").put("qqMid",mid).put("quality",quality[2]).put("trial",false).put("duration",match.optLong("interval",track.optLong("duration")/1000)*1000);
            }
        }
        throw new Exception(cookie.isEmpty()?"这首歌的 QQ 音源需要登录，请在资料库中登录 QQ 音乐":"QQ 音乐未提供可播放地址，请确认登录及歌曲权益");
    }
}
