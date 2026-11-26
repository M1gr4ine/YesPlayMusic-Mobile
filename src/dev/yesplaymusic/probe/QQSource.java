package dev.yesplaymusic.probe;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.net.URI;
import java.net.URISyntaxException;

/** Ports the installed desktop player's QQ compatibility route; no external proxy. */
public final class QQSource {
    public static final class ResolveException extends Exception {
        public final String code;
        public final String detail;
        public ResolveException(String code, String detail) { super(messageFor(code)); this.code=code; this.detail=detail; }
        private static String messageFor(String code) {
            if("qq_auth_required".equals(code))return "请先登录 QQ 音乐";
            if("qq_auth_expired".equals(code))return "QQ 音乐登录已过期，请重新登录";
            if("qq_region_locked".equals(code))return "这首歌因地区或版权限制暂不可用";
            if("qq_rights_unavailable".equals(code))return "QQ 音乐暂未提供这首歌的播放权限";
            if("match_not_found".equals(code))return "QQ 音乐未找到相符版本";
            if("network_error".equals(code))return "QQ 音乐连接失败，请检查网络后重试";
            return "QQ 音乐暂不可用，请稍后重试";
        }
    }
    static final class Credentials {
        final String uin;
        final boolean loggedIn, wechat, canRefresh;
        Credentials(String uin, boolean loggedIn, boolean wechat, boolean canRefresh) {
            this.uin=uin; this.loggedIn=loggedIn; this.wechat=wechat; this.canRefresh=canRefresh;
        }
    }
    static final class AuthSnapshot {
        final String cookie;
        final long generation, requestOrder;
        AuthSnapshot(String cookie,long generation,long requestOrder) { this.cookie=cookie; this.generation=generation; this.requestOrder=requestOrder; }
    }
    private static QQSource instance;
    private final Context appContext;
    private final AuthStore sessions;
    private long refreshRetryAt;
    private long sessionGeneration;
    private long authRequestOrder, authAppliedOrder;
    private String observedAuthState="unverified";
    private static final String[] COOKIE_NAMES = {"uin","wxuin","qqmusic_key","qm_keyst","psrf_qqrefresh_token","qqrefresh_token","psrf_musickey_createtime","psrf_qqaccess_token","psrf_access_token","qqaccess_token","psrf_access_token_expiresAt","psrf_qqopenid","psrf_qqunionid","login_type","tmeLoginType","euin","music_ignore_pskey","wxopenid","wxunionid","wxrefresh_token"};
    private QQSource(Context context) { appContext = context.getApplicationContext(); sessions = new AuthStore(appContext); }
    public static synchronized QQSource get(Context context) { if (instance == null) instance = new QQSource(context.getApplicationContext()); return instance; }
    public String getCookie() { return sessions.get("qq"); }
    private static Map<String,String> fields(String cookie) {
        Map<String,String> map=new LinkedHashMap<>();
        for(String item:(cookie==null?"":cookie).split(";")){int at=item.indexOf('=');if(at>0)map.put(item.substring(0,at).trim(),item.substring(at+1).trim());} return map;
    }
    private static final java.util.regex.Pattern CONTROL_OR_SPACE = java.util.regex.Pattern.compile("[\\r\\n\\x00-\\x20\\x7f]");
    private static boolean safeCookieValue(String value) {
        return value != null && !value.isEmpty() && !CONTROL_OR_SPACE.matcher(value).find();
    }
    private static String first(Map<String,String> values,String...keys){for(String key:keys){String val=values.get(key);if(safeCookieValue(val))return val;}return "";}
    private static String normalizedUin(String raw) {
        if(raw==null)return "";
        String value=raw.startsWith("o")?raw.substring(1):raw;
        value=value.replaceFirst("^0+","");
        return value.matches("[1-9][0-9]*")?value:"";
    }
    static Credentials credentials(String cookie) {
        Map<String,String> values=fields(cookie);
        String key=first(values,"qqmusic_key","qm_keyst");
        boolean wechat="2".equals(first(values,"login_type")) || "1".equals(first(values,"tmeLoginType")) || key.startsWith("W_X");
        String uin=normalizedUin(values.get(wechat?"wxuin":"uin"));
        if(uin.isEmpty())uin=normalizedUin(values.get(wechat?"uin":"wxuin"));
        boolean loggedIn=!uin.isEmpty()&&!key.isEmpty();
        boolean canRefresh=loggedIn&&!wechat&&!first(values,"psrf_qqrefresh_token","qqrefresh_token").isEmpty();
        return new Credentials(uin.isEmpty()?"0":uin,loggedIn,wechat,canRefresh);
    }
    static String sessionCookie(String raw) {
        Map<String,String> values=fields(raw);
        if(!credentials(raw).loggedIn)return "";
        String key=first(values,"qqmusic_key","qm_keyst");
        values.put("qqmusic_key",key); values.put("qm_keyst",key);
        StringBuilder result=new StringBuilder();
        for(String name:COOKIE_NAMES) {
            String value=values.get(name);
            if(safeCookieValue(value)) {
                if(result.length()>0)result.append("; ");result.append(name).append('=').append(value);
            }
        }
        return result.toString();
    }
    public synchronized boolean setCookie(String raw) {
        String cookie=sessionCookie(raw); if(cookie.isEmpty())return false;
        sessions.put("qq",cookie); sessionGeneration++; refreshRetryAt=0; observedAuthState="unverified"; return true;
    }
    synchronized AuthSnapshot snapshotAuth(String cookie) {
        return cookie!=null && getCookie().equals(cookie) ? new AuthSnapshot(cookie,sessionGeneration,++authRequestOrder) : null;
    }
    synchronized boolean currentSession(AuthSnapshot snapshot) {
        return snapshot!=null && snapshot.generation==sessionGeneration && getCookie().equals(snapshot.cookie);
    }
    synchronized void recordAuthResult(AuthSnapshot snapshot,boolean expired) {
        if(currentSession(snapshot) && credentials(snapshot.cookie).loggedIn && snapshot.requestOrder>=authAppliedOrder) {
            authAppliedOrder=snapshot.requestOrder;
            if(expired && !"expired".equals(observedAuthState))refreshRetryAt=0;
            observedAuthState=expired?"expired":"logged_in";
        }
    }
    private static ResolveException apiError(int code,String cookie) {
        if(code==104003)return new ResolveException("qq_region_locked",String.valueOf(code));
        if(code==104009)return new ResolveException(credentials(cookie).loggedIn?"qq_auth_expired":"qq_auth_required",String.valueOf(code));
        return new ResolveException("stream_unavailable",String.valueOf(code));
    }
    private static JSONObject request(String module,String method,JSONObject param,String cookie) throws Exception {
        JSONObject body = new JSONObject().put("req_0",new JSONObject().put("module",module).put("method",method).put("param",param));
        JSONObject result = getJson("https://u.y.qq.com/cgi-bin/musicu.fcg?data="+URLEncoder.encode(body.toString(),"UTF-8"),cookie);
        if(result.has("code")) { int topCode=providerCode(result,"code"); if(topCode!=0)throw apiError(topCode,cookie); }
        JSONObject reply=result.optJSONObject("req_0");
        if(reply==null || !reply.has("code"))throw new ResolveException("stream_unavailable","invalid_response");
        int replyCode=providerCode(reply,"code"); if(replyCode!=0)throw apiError(replyCode,cookie);
        JSONObject data=reply.optJSONObject("data"); if(data==null)throw new ResolveException("stream_unavailable","invalid_response"); return data;
    }
    private static JSONObject getJson(String url,String cookie) throws Exception {
        HttpURLConnection connection=null;
        try {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Referer","https://y.qq.com/");connection.setRequestProperty("Origin","https://y.qq.com");
            connection.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
            if(!cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
            int status=connection.getResponseCode(); if(status!=200)throw new ResolveException("network_error","HTTP "+status);
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(bytes.size()+n>8*1024*1024)throw new ResolveException("stream_unavailable","response_too_large");bytes.write(buffer,0,n);
                }
                try{return new JSONObject(new String(bytes.toByteArray(),"UTF-8"));}
                catch(org.json.JSONException invalid){throw new ResolveException("stream_unavailable","invalid_response");}
            }
        } catch(java.io.IOException error) { throw new ResolveException("network_error","connection_failed"); }
        finally {if(connection!=null)connection.disconnect();}
    }
    private String freshCookie() {
        final String original, uin, key, token;
        final AuthSnapshot snapshot;
        final Map<String,String> map;
        synchronized(this) {
            original=getCookie(); map=fields(original); Credentials auth=credentials(original);
            uin=auth.uin; key=first(map,"qqmusic_key","qm_keyst"); token=first(map,"psrf_qqrefresh_token","qqrefresh_token");
            if(!auth.canRefresh || System.currentTimeMillis()<refreshRetryAt)return original;
            long created=0;try{created=Long.parseLong(first(map,"psrf_musickey_createtime"))*1000;}catch(Exception ignored){}
            if(!"expired".equals(observedAuthState) && created>0 && created+259200000L>System.currentTimeMillis()+600000L)return original;
            snapshot=snapshotAuth(original);
            refreshRetryAt=System.currentTimeMillis()+300000L;
        }
        try {
            JSONObject result=request("music.login.LoginServer","Login",new JSONObject().put("refresh_token",token).put("musickey",key).put("musicid",Long.parseLong(uin)),original);
            Object rawKey=result.opt("musickey"), rawToken=result.opt("refresh_token");
            if(!(rawKey instanceof String))return getCookie();
            String newKey=(String)rawKey;
            if(!safeCookieValue(newKey) || "null".equalsIgnoreCase(newKey))return getCookie();
            if(result.has("refresh_token") && (!(rawToken instanceof String) || !safeCookieValue((String)rawToken)
                    || "null".equalsIgnoreCase((String)rawToken)))return getCookie();
            long time=result.optLong("musickeyCreateTime");
            if(time<=0 || time>(Long.MAX_VALUE-259200000L)/1000 || time*1000+259200000L<=System.currentTimeMillis()+600000L)return getCookie();
            map.put("qm_keyst",newKey);map.put("qqmusic_key",newKey);map.put("psrf_musickey_createtime",String.valueOf(time));
            if(rawToken instanceof String)map.put("psrf_qqrefresh_token",(String)rawToken);
            synchronized(this) {
                if(snapshot==null||sessionGeneration!=snapshot.generation||snapshot.requestOrder<authAppliedOrder||!getCookie().equals(original))return getCookie();
                StringBuilder merged=new StringBuilder();for(Map.Entry<String,String> item:map.entrySet())merged.append(item.getKey()).append('=').append(item.getValue()).append("; ");
                if(setCookie(merged.toString()))observedAuthState="logged_in";return getCookie();
            }
        } catch(InterruptedException cancelled){Thread.currentThread().interrupt();return getCookie();}
        catch(ResolveException error){if("qq_auth_expired".equals(error.code))recordAuthResult(snapshot,true);return getCookie();}
        catch(Exception error){return getCookie();}
    }
    static boolean shouldRetrySearch(ResolveException error) {
        return "stream_unavailable".equals(error.code) && "2001".equals(error.detail);
    }
    static JSONArray filterSearchResults(JSONArray list) {
        JSONArray filtered=new JSONArray();
        for(int i=0;list!=null&&i<list.length();i++) {
            JSONObject song=list.optJSONObject(i);
            if(song!=null&&!songMid(song).isEmpty()&&!fileMid(song).isEmpty())filtered.put(song);
        }
        return filtered;
    }
    interface SearchRequest {
        JSONObject fetch() throws Exception;
    }

    static JSONObject searchWithRetry(SearchRequest request) throws Exception {
        checkCancelled();
        try {
            return request.fetch();
        } catch (ResolveException failure) {
            if (!shouldRetrySearch(failure)) throw failure;
            checkCancelled();
            Thread.sleep(150);
            checkCancelled();
            return request.fetch();
        }
    }

    private JSONArray searchRaw(String query,int page,int limit,String cookie) throws Exception {
        JSONObject param=new JSONObject().put("num_per_page",Math.min(50,Math.max(1,limit))).put("page_num",Math.max(1,page)).put("query",query).put("search_type",0);
        JSONObject data = searchWithRetry(() ->
                request("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", param, cookie));
        JSONObject body=data.optJSONObject("body"), songs=body==null?null:body.optJSONObject("song");JSONArray list=songs==null?null:songs.optJSONArray("list");return filterSearchResults(list);
    }
    public JSONObject execute(String method,JSONObject args) throws Exception {
        if("qqStatus".equals(method)){
            synchronized(this) {
                Credentials auth=credentials(getCookie());
                String state=auth.loggedIn?observedAuthState:"logged_out";
                return new JSONObject().put("code",200).put("loggedIn",auth.loggedIn&&!"expired".equals(state)).put("canRefresh",auth.canRefresh).put("authState",state);
            }
        }
        if("qqLogout".equals(method)){synchronized(this){sessions.clear("qq");sessionGeneration++;refreshRetryAt=0;observedAuthState="unverified";}return new JSONObject().put("code",200);}
        if("qqSearch".equals(method)){
            String query=args.optString("query",args.optString("keywords","")).trim();if(query.isEmpty())return new JSONObject().put("code",200).put("result",new JSONObject().put("songs",new JSONArray()).put("songCount",0));
            JSONArray raw=searchRaw(query,args.optInt("page",1),args.optInt("limit",30),freshCookie()), songs=new JSONArray();
            for(int i=0;i<raw.length();i++)songs.put(toTrack(raw.getJSONObject(i)));
            return new JSONObject().put("code",200).put("result",new JSONObject().put("songs",songs).put("songCount",songs.length()));
        }
        if ("qqLyrics".equals(method)) {
            String key = args.has("qqMid") ? "qqMid" : args.has("mid") ? "mid" : "id";
            Object mid = args.opt(key);
            return resolveLyrics(mid instanceof String ? (String) mid : null, new LyricLookup() {
                @Override public JSONObject modern(String songMid) throws Exception {
                    JSONObject params = new JSONObject().put("songMID", songMid).put("trans", 1)
                            .put("roma", 0).put("crypt", 0).put("qrc", 0).put("type", 0);
                    return request("music.musichallSong.PlayLyricInfo", "GetPlayLyricInfo", params, freshCookie());
                }

                @Override public JSONObject legacy(String songMid) throws Exception {
                    String url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                            + URLEncoder.encode(songMid, "UTF-8") + "&format=json&nobase64=1";
                    return getJson(url, getCookie());
                }
            });
        }
        throw new Exception("不支持的 QQ 音乐操作");
    }
    interface LyricLookup {
        JSONObject modern(String mid) throws Exception;
        JSONObject legacy(String mid) throws Exception;
    }

    static JSONObject resolveLyrics(String rawMid, LyricLookup lookup) throws Exception {
        checkCancelled();
        String mid = rawMid == null ? "" : rawMid.trim();
        mid = validMid(mid.startsWith("qq:") ? mid.substring(3) : mid);
        if (mid.isEmpty() || mid.matches("[0-9]+")) {
            throw new ResolveException("match_not_found", "invalid_lyric_mid");
        }
        try {
            JSONObject modern = lookup.modern(mid);
            checkCancelled();
            JSONObject lyrics = parseLyricResponse(modern, false);
            checkCancelled();
            if (!lyrics.getBoolean("nolyric")) return lyrics;
        } catch (InterruptedException | java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception unavailable) {
            // The legacy service can still have the original when PlayLyricInfo fails.
        }
        checkCancelled();
        try {
            return parseLyricResponse(lookup.legacy(mid), true);
        } finally {
            // A cancelled HTTP read can surface as an ordinary connection error.
            checkCancelled();
        }
    }

    private static JSONObject parseLyricResponse(JSONObject data, boolean legacy) throws Exception {
        if (data == null || (legacy && !data.has("code") && !data.has("retcode"))) {
            throw new ResolveException("stream_unavailable", "missing_lyric_status");
        }
        int code = providerCode(data, "code");
        int retcode = providerCode(data, "retcode");
        if (legacy && retcode == -1901 && (code == 0 || code == -1901)) return lyricResult("", "");
        if (code != 0 || retcode != 0) {
            throw new ResolveException("stream_unavailable", Integer.toString(code != 0 ? code : retcode));
        }
        Object raw = data.opt("lyric");
        if (raw == null || raw == JSONObject.NULL || (raw instanceof String && ((String) raw).trim().isEmpty())) {
            return lyricResult("", "");
        }
        String original = lyricText(raw);
        try {
            if (!LrcLines.parse(original).isEmpty()) return lyricResult(original, lyricText(data.opt("trans")));
        } catch (NumberFormatException invalidTimestamp) {
            // A timestamp outside the parser's range cannot be used for playback.
        }
        throw new ResolveException("stream_unavailable", "invalid_timed_lyric");
    }

    private static JSONObject lyricResult(String original, String translation) throws org.json.JSONException {
        return new JSONObject().put("code", 200).put("nolyric", original.isEmpty())
                .put("lrc", new JSONObject().put("lyric", original))
                .put("tlyric", new JSONObject().put("lyric", translation));
    }

    static String lyricText(Object value) {
        if (!(value instanceof String)) return "";
        String text = (String) value;
        String decoded = decodeLyricBase64(text);
        return decodeLyricEntities(decoded == null ? text : decoded);
    }

    private static String decodeLyricBase64(String text) {
        // Round-trip validation rejects ignored characters, missing or noncanonical padding.
        if (text.isEmpty() || text.length() % 4 != 0) return null;
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(text);
            if (!java.util.Base64.getEncoder().encodeToString(bytes).equals(text)) return null;
            String decoded = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            for (int i = 0; i < decoded.length(); i++) {
                char ch = decoded.charAt(i);
                if ((Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')
                        || ch == '\ufffd') return null;
            }
            return decoded;
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            return null;
        }
    }

    private static String decodeLyricEntities(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) == '&') {
                int end = i + 1;
                // Bound the scan so malformed text with many ampersands remains linear.
                while (end < text.length() && end - i <= 32 && text.charAt(end) != ';'
                        && text.charAt(end) != '&') end++;
                if (end < text.length() && end - i <= 32 && text.charAt(end) == ';') {
                    String entity = text.substring(i + 1, end);
                    String replacement = lyricEntity(entity);
                    if (replacement != null) {
                        result.append(replacement);
                        i = end + 1;
                        continue;
                    }
                }
            }
            result.append(text.charAt(i++));
        }
        return result.toString();
    }

    private static String lyricEntity(String entity) {
        if (entity.startsWith("#")) {
            int radix = 10, start = 1;
            if (entity.length() > 2 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                radix = 16;
                start = 2;
            }
            if (start == entity.length()) return null;
            for (int i = start; i < entity.length(); i++) {
                char c = entity.charAt(i);
                if (!(c >= '0' && c <= '9') && !(radix == 16 &&
                        ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))) return null;
            }
            try {
                int codePoint = Integer.parseInt(entity.substring(start), radix);
                if (!Character.isValidCodePoint(codePoint) || codePoint == 0 ||
                        (codePoint >= 0xd800 && codePoint <= 0xdfff)) return null;
                return new String(Character.toChars(codePoint));
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        switch (entity) {
            case "amp": case "AMP": return "&";
            case "lt": case "LT": return "<";
            case "gt": case "GT": return ">";
            case "nbsp": return "\u00a0";
            case "quot": case "QUOT": return "\"";
            case "apos": return "'";
            case "lbrack": return "[";
            case "rbrack": return "]";
            case "colon": return ":";
            case "period": return ".";
            case "sol": return "/";
            case "num": return "#";
            case "NewLine": return "\n";
            case "Tab": return "\t";
            default: return null;
        }
    }
    static String validMid(Object value) {
        if (!(value instanceof String)) return "";
        String mid = ((String) value).trim();
        return mid.matches("[A-Za-z0-9]{1,128}") && !"null".equalsIgnoreCase(mid) ? mid : "";
    }

    static String songMid(JSONObject song) {
        if (song == null) return "";
        String mid = validMid(song.opt("mid"));
        return mid.isEmpty() ? validMid(song.opt("songmid")) : mid;
    }

    static String fileMid(JSONObject song) {
        JSONObject file = song == null ? null : song.optJSONObject("file");
        return file == null ? "" : validMid(file.opt("media_mid"));
    }

    private static JSONObject toTrack(JSONObject song) throws Exception {
        JSONArray singers = song.optJSONArray("singer");
        StringBuilder artist = new StringBuilder();
        for (int i = 0; singers != null && i < singers.length(); i++) {
            JSONObject singer = singers.optJSONObject(i);
            if (singer == null || !(singer.opt("name") instanceof String)) continue;
            String name = singer.getString("name").trim();
            if (name.isEmpty()) continue;
            if (artist.length() > 0) artist.append(" / ");
            artist.append(name);
        }
        JSONObject album = song.optJSONObject("album");
        String albumMid = album == null ? "" : validMid(album.opt("mid"));
        String mid = songMid(song);
        JSONArray artistMembers = ArtistAliases.membersToJson(ArtistAliases.membersFromArtists(singers));
        JSONObject track = new JSONObject().put("id", "qq:" + mid).put("provider", "qq")
                .put("qqMid", mid).put("mediaMid", fileMid(song))
                .put("title", song.optString("title", song.optString("name", "")))
                .put("artist", artist.toString())
                .put("artistAliases", ArtistAliases.toJsonArray(ArtistAliases.fromArtists(singers)))
                .put("album", album == null ? "" : album.optString("name", ""))
                .put("artwork", albumMid.isEmpty() ? "" : "https://y.gtimg.cn/music/photo_new/T002R800x800M000" + albumMid + ".jpg")
                .put("duration", song.optLong("interval", 0) * 1000L);
        if (artistMembers.length() > 0) track.put("artistMembers", artistMembers);
        return track;
    }

    /** The provider reads used by playback. Tests supply recorded catalogue and vkey shapes. */
    interface Lookup {
        JSONArray search(String query) throws Exception;
        JSONObject detail(String mid) throws Exception;
        List<String> aliases(Track track) throws Exception;
        default List<ArtistAliases.Member> artistMembers(Track track) throws Exception {
            return null;
        }
        JSONObject vkey(JSONObject params) throws Exception;
    }

    private static final class Candidate {
        final String mid, mediaMid, reason;
        final long duration;
        Candidate(String mid, String mediaMid, long duration, String reason) {
            this.mid = mid;
            this.mediaMid = mediaMid;
            this.duration = duration;
            this.reason = reason;
        }
        Candidate(Track song, String reason) {
            this(song.effectiveQqMid(), song.mediaMid, song.duration, reason);
        }
    }

    private static final class SearchHit {
        final Track song;
        final boolean artistQuery;
        final int rank;
        SearchHit(Track song, boolean artistQuery, int rank) {
            this.song = song;
            this.artistQuery = artistQuery;
            this.rank = rank;
        }
    }

    private static void addHits(List<SearchHit> hits, JSONArray songs, boolean artistQuery) throws Exception {
        for (int i = 0; songs != null && i < songs.length(); i++) {
            JSONObject item = songs.optJSONObject(i);
            if (songMid(item).isEmpty() || fileMid(item).isEmpty()) continue;
            Track song = Track.fromJson(toTrack(item));
            if (song != null) hits.add(new SearchHit(song, artistQuery, i));
        }
    }

    private static int strictScore(Track target, List<ArtistAliases.Member> members, List<String> aliases, Track song) {
        return ArtistAliases.score(target.title, target.artist, members, aliases, target.album, target.duration,
                song.title, song.artist, song.artistMembers, song.artistAliases, song.album, song.duration);
    }

    private static boolean hasCompetingCredit(Track target, List<ArtistAliases.Member> members,
                                              List<String> aliases, Track selected, List<SearchHit> hits) {
        for (SearchHit hit : hits) {
            if (!hit.artistQuery) continue;
            Track other = hit.song;
            if (other.id.equals(selected.id) || other.artist.isEmpty()
                    || other.duration <= 0 || Math.abs(other.duration - target.duration) > QQMatch.UNM_DURATION_MS
                    || !QQMatch.sameRecordingTitle(target.title, other.title)) continue;
            if (strictScore(selected, selected.artistMembers, selected.artistAliases, other) >= 0) continue;
            if (strictScore(target, members, aliases, other) >= 0) continue;
            if (ArtistAliases.evidencedLeadAlbumBridge(
                    target.title, target.artist, members, aliases, target.album, target.duration,
                    other.title, other.artist, other.artistMembers, other.artistAliases, other.album, other.duration)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static long durationGap(Track target, Track candidate) {
        return target.duration > 0 && candidate.duration > 0
                ? Math.abs(target.duration - candidate.duration) : Long.MAX_VALUE;
    }

    private static List<Candidate> rankCandidates(Track target, List<ArtistAliases.Member> members,
                                                  List<String> aliases, List<SearchHit> hits) {
        Map<Track,Integer> scores = new LinkedHashMap<>();
        for (SearchHit hit : hits) {
            int score = strictScore(target, members, aliases, hit.song);
            if (score >= 0) scores.put(hit.song, score);
        }
        List<Track> ranked = new ArrayList<>(scores.keySet());
        ranked.sort((left, right) -> {
            int byScore = Integer.compare(scores.get(right), scores.get(left));
            if (byScore != 0) return byScore;
            return Long.compare(durationGap(target, left), durationGap(target, right));
        });
        Map<String,Candidate> accepted = new LinkedHashMap<>();
        for (Track song : ranked) accepted.putIfAbsent(song.id, new Candidate(song, "artist_title"));

        // Evidenced same-album solo-lead versions can safely back up full-credit matches if rights differ.
        List<Track> evidencedBridges = new ArrayList<>();
        for (SearchHit hit : hits) {
            Track song = hit.song;
            if (accepted.containsKey(song.id)) continue;
            if (ArtistAliases.evidencedLeadAlbumBridge(
                    target.title, target.artist, members, aliases, target.album, target.duration,
                    song.title, song.artist, song.artistMembers, song.artistAliases, song.album, song.duration)
                    && !hasCompetingCredit(target, members, aliases, song, hits)) {
                evidencedBridges.add(song);
            }
        }
        evidencedBridges.sort((left, right) -> Long.compare(durationGap(target, left), durationGap(target, right)));
        for (Track song : evidencedBridges) accepted.putIfAbsent(song.id, new Candidate(song, "album_bridge"));

        if (accepted.isEmpty()) {
            for (SearchHit hit : hits) {
                Track song = hit.song;
                if (ArtistAliases.bridgeAccepts(target.title, target.artist, members, aliases, target.album,
                        target.duration, song.title, song.artist, song.artistMembers, song.artistAliases, song.album,
                        song.duration, hit.artistQuery, hit.rank)
                        && !hasCompetingCredit(target, members, aliases, song, hits)) {
                    accepted.putIfAbsent(song.id, new Candidate(song, "catalog_bridge"));
                }
            }
        }
        return new ArrayList<>(accepted.values());
    }

    private static List<Candidate> searchCandidates(Track target, Lookup api) throws Exception {
        List<SearchHit> hits = new ArrayList<>();
        String primary = QQMatch.keyword(target.title, target.artist);
        checkCancelled();
        addHits(hits, api.search(primary), !target.artist.trim().isEmpty());
        List<ArtistAliases.Member> members = target.artistMembers;
        List<String> aliases = target.artistAliases;
        List<Candidate> matches = rankCandidates(target, members, aliases, hits);
        if (!matches.isEmpty() && "artist_title".equals(matches.get(0).reason)) return matches;

        checkCancelled();
        try {
            List<ArtistAliases.Member> fetchedMembers = api.artistMembers(target);
            if (fetchedMembers != null) {
                if (!fetchedMembers.isEmpty()) members = fetchedMembers;
                if (members.size() == 1 && !members.get(0).aliases.isEmpty()) {
                    LinkedHashSet<String> augmented = new LinkedHashSet<>(aliases);
                    augmented.addAll(members.get(0).aliases);
                    aliases = new ArrayList<>(augmented);
                }
            } else {
                LinkedHashSet<String> augmented = new LinkedHashSet<>(aliases);
                augmented.addAll(api.aliases(target));
                aliases = new ArrayList<>(augmented);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception ignored) {
            // Alias metadata is optional. Every remaining route still verifies identity.
        }
        checkCancelled();
        matches = rankCandidates(target, members, aliases, hits);
        if (!matches.isEmpty()) return matches;
        LinkedHashSet<String> queries = new LinkedHashSet<>(
                ArtistAliases.searchQueries(target.title, target.artist, members, aliases));
        // The QQ catalogue can credit just the lead performer of a collaboration.
        int separator = target.artist.indexOf(" / ");
        if (separator > 0) queries.add(QQMatch.keyword(target.title, target.artist.substring(0, separator)));
        queries.remove(primary);
        for (String query : queries) {
            checkCancelled();
            addHits(hits, api.search(query), true);
            matches = rankCandidates(target, members, aliases, hits);
            if (!matches.isEmpty()) return matches;
        }
        String bareTitle = QQMatch.cleanSearchTitle(target.title);
        if (!bareTitle.isEmpty() && !bareTitle.equals(primary)) {
            checkCancelled();
            addHits(hits, api.search(bareTitle), false);
        }
        return rankCandidates(target, members, aliases, hits);
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("playback cancelled");
    }

    private static final int MAX_PLAYBACK_CANDIDATES = 3;
    private static final String[][] QUALITIES = {
            {"M800", ".mp3", "320k"}, {"M500", ".mp3", "128k"}, {"C400", ".m4a", "AAC"}
    };

    private static String filename(String[] quality, String mediaMid) {
        return quality[0] + mediaMid + quality[1];
    }

    private static JSONObject vkeyParams(Candidate song, String uin, String guid) throws Exception {
        JSONArray files = new JSONArray(), mids = new JSONArray(), types = new JSONArray();
        for (String[] quality : QUALITIES) {
            files.put(filename(quality, song.mediaMid));
            mids.put(song.mid);
            types.put(0);
        }
        return new JSONObject().put("guid", guid).put("loginflag", 1).put("platform", "20")
                .put("uin", uin).put("filename", files).put("songmid", mids).put("songtype", types);
    }

    private static List<String> endpoints(JSONArray sip) {
        LinkedHashSet<String> secure = new LinkedHashSet<>();
        LinkedHashSet<String> upgraded = new LinkedHashSet<>();
        for (int i = 0; sip != null && i < sip.length(); i++) {
            Object raw = sip.opt(i);
            if (!(raw instanceof String)) continue;
            String base = ((String) raw).trim();
            String safe = StreamHosts.normalizeStreamUrl(base, "qq");
            if (safe.isEmpty()) continue;
            (base.regionMatches(true, 0, "https://", 0, 8) ? secure : upgraded).add(safe);
        }
        secure.addAll(upgraded);
        return new ArrayList<>(secure);
    }

    /** Missing optional result fields have no error. A malformed field never grants a file. */
    static int providerCode(JSONObject obj, String field) throws ResolveException {
        if (!obj.has(field)) return 0;
        try {
            return Integer.parseInt(String.valueOf(obj.opt(field)));
        } catch (NumberFormatException invalid) {
            throw new ResolveException("stream_unavailable", "invalid_" + field);
        }
    }

    private static ResolveException denial(int code, boolean loggedIn) {
        if (code == 104009) return new ResolveException(loggedIn ? "qq_auth_expired" : "qq_auth_required", "104009");
        if (code == 104003) return new ResolveException("qq_region_locked", "104003");
        return code == 0 ? new ResolveException("qq_rights_unavailable", "no_purl")
                : new ResolveException("stream_unavailable", Integer.toString(code));
    }

    private static int priority(String code) {
        switch (code) {
            case "qq_auth_expired": case "qq_auth_required": return 5;
            case "qq_region_locked": return 4;
            case "network_error": return 3;
            case "stream_unavailable": return 2;
            default: return 1;
        }
    }

    private static ResolveException prefer(ResolveException previous, ResolveException next) {
        return previous == null || priority(next.code) > priority(previous.code) ? next : previous;
    }

    private static boolean matchesFile(JSONObject info, String expectedFile, String expectedMid) {
        if (info == null || !expectedFile.equals(info.opt("filename"))) return false;
        Object mid = info.opt("songmid");
        return mid == null || mid == JSONObject.NULL || "".equals(mid) || expectedMid.equals(mid);
    }

    private static List<JSONObject> streams(Candidate song, String uin, String guid, Lookup api) throws Exception {
        checkCancelled();
        JSONObject data = api.vkey(vkeyParams(song, uin, guid));
        if (data == null) throw new ResolveException("stream_unavailable", "vkey_data_missing");
        boolean loggedIn = !"0".equals(uin);
        int retcode = providerCode(data, "retcode");
        if (retcode == 0) retcode = providerCode(data, "result");
        if (retcode != 0) throw denial(retcode, loggedIn);
        List<String> bases = endpoints(data.optJSONArray("sip"));
        JSONArray rows = data.optJSONArray("midurlinfo");
        Map<String,JSONObject> found = new LinkedHashMap<>();
        ResolveException failure = null;
        boolean sawFile = false;
        for (String[] quality : QUALITIES) {
            String expected = filename(quality, song.mediaMid);
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (!matchesFile(row, expected, song.mid)) continue;
                sawFile = true;
                int result;
                try {
                    result = providerCode(row, "result");
                    if (result == 0) result = providerCode(row, "retcode");
                } catch (ResolveException malformed) {
                    failure = prefer(failure, malformed);
                    continue;
                }
                if (result != 0) {
                    failure = prefer(failure, denial(result, loggedIn));
                    continue;
                }
                Object raw = row.opt("purl");
                if (!(raw instanceof String) || ((String) raw).trim().isEmpty()
                        || "null".equalsIgnoreCase(((String) raw).trim())) continue;
                boolean valid = false;
                for (String base : bases) {
                    String url;
                    try {
                        URI resolved = new URI(base).resolve(new URI(((String) raw).trim()));
                        String path = resolved.getRawPath();
                        if (resolved.getRawFragment() != null || path == null || path.length() <= 1) continue;
                        url = StreamHosts.normalizeStreamUrl(resolved.toString(), "qq");
                    } catch (URISyntaxException e) { continue; }
                    if (url.isEmpty()) continue;
                    valid = true;
                    if (found.containsKey(url)) continue;
                    JSONObject attempt = new JSONObject().put("url", url).put("source", "qq")
                            .put("qqMid", song.mid).put("mediaMid", song.mediaMid)
                            .put("quality", quality[2]).put("duration", song.duration)
                            .put("trial", false).put("verified", true).put("matchReason", song.reason)
                            .put("host", StreamHosts.hostOf(url));
                    found.put(url, attempt);
                }
                if (!valid) failure = prefer(failure, new ResolveException("stream_unavailable", "no_trusted_endpoint"));
            }
        }
        if (!found.isEmpty()) return new ArrayList<>(found.values());
        if (failure != null) throw failure;
        if (!sawFile && rows != null && rows.length() > 0)
            throw new ResolveException("stream_unavailable", "vkey_identity_mismatch");
        throw denial(0, loggedIn);
    }
    private static Candidate savedCandidate(Track track, Lookup api) throws Exception {
        String mid = validMid(track.effectiveQqMid());
        if (mid.isEmpty()) return null;
        boolean nativeQq = track.id.startsWith("qq:");
        boolean verified = track.hasVerifiedQqMid();
        JSONObject detail = null;
        checkCancelled();
        try {
            detail = api.detail(mid);
        } catch (ResolveException unavailable) {
            // A native catalogue identity or a previously verified mapping can use its saved file.
        }
        checkCancelled();
        if (!mid.equals(songMid(detail))) detail = null;
        if (!nativeQq && !verified) {
            if (detail == null || fileMid(detail).isEmpty()) return null;
            Track candidate = Track.fromJson(toTrack(detail));
            // A legacy hint alone cannot provide a ranked search context for an alias bridge.
            return candidate != null && strictScore(track, track.artistMembers, track.artistAliases, candidate) >= 0
                    ? new Candidate(candidate, "legacy_reverified") : null;
        }
        String file = fileMid(detail);
        if (file.isEmpty()) file = validMid(track.mediaMid);
        if (file.isEmpty()) file = mid;
        long duration = detail == null ? 0 : detail.optLong("interval") * 1000L;
        return new Candidate(mid, file, duration > 0 ? duration : track.duration,
                nativeQq ? "catalog_id" : "verified_cache");
    }

    static List<JSONObject> lookupAttempts(Track track, Lookup api, String uin) throws Exception {
        if (track == null) throw new ResolveException("match_not_found", "missing_track");
        // A ten-digit decimal device identifier, encoded as a JSON string.
        String guid = Long.toString(ThreadLocalRandom.current()
                .nextLong(1_000_000_000L, 10_000_000_000L));
        Candidate previous = savedCandidate(track, api);
        ResolveException failure = null;
        if (previous != null) {
            try {
                return streams(previous, uin, guid, api);
            } catch (ResolveException denied) {
                failure = denied;
            }
        }
        if (track.id.startsWith("qq:")) {
            if (failure != null) throw failure;
            throw new ResolveException("match_not_found", "invalid_catalog_mid");
        }
        if (track.title.isEmpty()) throw new ResolveException("match_not_found", "missing_title");
        List<Candidate> matches;
        try {
            matches = searchCandidates(track, api);
        } catch (ResolveException searchFailure) {
            throw prefer(failure, searchFailure);
        }
        int tried = 0;
        for (Candidate match : matches) {
            if (previous != null && previous.mid.equals(match.mid) && previous.mediaMid.equals(match.mediaMid)) continue;
            if (tried++ >= MAX_PLAYBACK_CANDIDATES) break;
            try {
                List<JSONObject> granted = streams(match, uin, guid, api);
                if (!granted.isEmpty()) return granted;
            } catch (ResolveException denied) {
                failure = prefer(failure, denied);
            }
        }
        if (failure != null) throw failure;
        throw new ResolveException("match_not_found", "no_verified_recording");
    }

    public List<JSONObject> resolveAttempts(JSONObject input) throws Exception {
        checkCancelled();
        String cookie = freshCookie();
        AuthSnapshot authSnapshot = snapshotAuth(cookie);
        Credentials auth = credentials(cookie);
        Lookup api = new Lookup() {
            public JSONArray search(String query) throws Exception {
                return searchRaw(query, 1, 10, cookie);
            }
            public JSONObject detail(String mid) throws Exception {
                return request("music.pf_song_detail_svr", "get_song_detail",
                        new JSONObject().put("song_mid", mid), cookie).optJSONObject("track_info");
            }
            public List<String> aliases(Track track) {
                return Collections.emptyList();
            }
            @Override public List<ArtistAliases.Member> artistMembers(Track track) throws Exception {
                if (!"netease".equals(track.provider)) return Collections.emptyList();
                return MusicApi.get(appContext).findNeteaseArtistMembers(
                        track.id, track.title, track.artist, track.artistMembers, track.artistIds);
            }
            public JSONObject vkey(JSONObject params) throws Exception {
                return request("vkey.GetVkeyServer", "CgiGetVkey", params, cookie);
            }
        };
        try {
            List<JSONObject> granted = lookupAttempts(Track.fromJson(input), api, auth.loggedIn ? auth.uin : "0");
            checkCancelled();
            if (!currentSession(authSnapshot)) throw new ResolveException("stream_unavailable", "session_changed");
            // A public file can also be granted to a guest. Only an authenticated account response proves login.
            return granted;
        } catch (ResolveException denied) {
            if ("qq_auth_expired".equals(denied.code)) {
                if (!auth.loggedIn) throw new ResolveException("qq_auth_required", denied.detail);
                recordAuthResult(authSnapshot, true);
            }
            throw denied;
        }
    }
}
