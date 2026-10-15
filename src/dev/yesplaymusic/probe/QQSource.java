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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Ports the installed desktop player's QQ compatibility route; no external proxy. */
public final class QQSource {
    private static QQSource instance;
    private final Context appContext;
    private final AuthStore sessions;
    private long refreshRetryAt;
    private long sessionGeneration;
    private static final String[] COOKIE_NAMES = {"uin","wxuin","qqmusic_key","qm_keyst","psrf_qqrefresh_token","qqrefresh_token","psrf_musickey_createtime","psrf_qqaccess_token","psrf_access_token","qqaccess_token","psrf_access_token_expiresAt","psrf_qqopenid","psrf_qqunionid","login_type","tmeLoginType","euin","music_ignore_pskey","wxopenid","wxunionid","wxrefresh_token"};
    private QQSource(Context context) { appContext = context.getApplicationContext(); sessions = new AuthStore(appContext); }
    public static synchronized QQSource get(Context context) { if (instance == null) instance = new QQSource(context.getApplicationContext()); return instance; }
    public String getCookie() { return sessions.get("qq"); }
    public synchronized boolean setCookie(String raw) {
        Map<String,String> values = fields(raw); String key = first(values,"qqmusic_key","qm_keyst");
        String uin = first(values,"uin","wxuin").replaceFirst("^o0*", "");
        if (!uin.matches("[1-9][0-9]*") || key.isEmpty()) return false;
        values.put("uin",uin); values.put("qqmusic_key",key); values.put("qm_keyst",key);
        StringBuilder result = new StringBuilder();
        for (String name : COOKIE_NAMES) { String value=values.get(name); if (value!=null && !value.isEmpty() && !value.matches(".*[\\r\\n\\x00-\\x20].*")) { if(result.length()>0)result.append("; ");result.append(name).append('=').append(value); } }
        sessions.put("qq",result.toString()); sessionGeneration++; refreshRetryAt=0; return true;
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
        if("qqLogout".equals(method)){synchronized(this){sessions.clear("qq");sessionGeneration++;refreshRetryAt=0;}return new JSONObject().put("code",200);}
        if("qqSearch".equals(method)){
            String query=args.optString("query",args.optString("keywords","")).trim();if(query.isEmpty())return new JSONObject().put("code",200).put("result",new JSONObject().put("songs",new JSONArray()).put("songCount",0));
            JSONArray raw=searchRaw(query,args.optInt("page",1),args.optInt("limit",30),freshCookie()), songs=new JSONArray();
            for(int i=0;i<raw.length();i++)songs.put(toTrack(raw.getJSONObject(i)));
            return new JSONObject().put("code",200).put("result",new JSONObject().put("songs",songs).put("songCount",songs.length()));
        }
        if("qqLyrics".equals(method)){
            String mid=args.optString("qqMid",args.optString("mid",args.optString("id",""))).replaceFirst("^qq:","");
            String cookie=freshCookie();JSONObject data;
            // The legacy lyric endpoint never returns translations; PlayLyricInfo does when trans=1.
            try{data=request("music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",new JSONObject().put("songMID",mid).put("trans",1).put("roma",0).put("crypt",0).put("qrc",0).put("type",0),cookie);if(data.optString("lyric").isEmpty())throw new Exception("empty lyric");}
            catch(Exception unavailable){data=getJson("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="+URLEncoder.encode(mid,"UTF-8")+"&format=json&nobase64=1",cookie);}
            return new JSONObject().put("code",200).put("lrc",new JSONObject().put("lyric",lyricText(data.optString("lyric")))).put("tlyric",new JSONObject().put("lyric",lyricText(data.optString("trans"))));
        }
        throw new Exception("不支持的 QQ 音乐操作");
    }
    private static String lyricText(String text){
        if(!text.contains("[")&&!text.contains("&#")){try{text=new String(Base64.decode(text,Base64.DEFAULT),"UTF-8");}catch(Exception ignored){}}
        return text.replace("&#10;","\n").replace("&#13;","").replace("&#58;",":").replace("&#46;",".").replace("&#32;"," ").replace("&#39;","'").replace("&apos;","'").replace("&quot;","\"").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">");
    }
    private static JSONObject toTrack(JSONObject song) throws Exception {
        JSONArray singers=song.optJSONArray("singer");StringBuilder artist=new StringBuilder();if(singers!=null)for(int i=0;i<singers.length();i++){if(i>0)artist.append(" / ");artist.append(singers.getJSONObject(i).optString("name"));}
        JSONObject album=song.optJSONObject("album"), file=song.optJSONObject("file");String mid=song.optString("mid",song.optString("songmid"));String albumMid=album==null?"":album.optString("mid");
        return new JSONObject().put("id","qq:"+mid).put("qqMid",mid).put("provider","qq").put("title",song.optString("title",song.optString("name"))).put("artist",artist.toString()).put("artistAliases",ArtistAliases.toJsonArray(ArtistAliases.fromArtists(singers))).put("album",album==null?"":album.optString("name")).put("artwork",albumMid.isEmpty()?"":"https://y.gtimg.cn/music/photo_new/T002R800x800M000"+albumMid+".jpg").put("duration",song.optLong("interval")*1000).put("mediaMid",file==null?mid:file.optString("media_mid",mid));
    }
    private static JSONObject bestSearchMatch(JSONObject track,JSONArray songs,List<String> aliases) throws Exception {
        JSONObject best=null;int bestScore=-1;
        for(int i=0;songs!=null&&i<songs.length();i++){
            JSONObject candidate=songs.optJSONObject(i);if(candidate==null)continue;
            JSONObject normalized=toTrack(candidate);
            List<String> candidateAliases=ArtistAliases.fromAliasArray(normalized.optJSONArray("artistAliases"),normalized.optString("artist",""));
            int score=ArtistAliases.score(track.optString("title",""),track.optString("artist",""),aliases,track.optLong("duration"),
                    normalized.optString("title",""),normalized.optString("artist",""),candidateAliases,normalized.optLong("duration"));
            if(score>bestScore){bestScore=score;best=candidate;}
        }
        return bestScore<0?null:best;
    }
    private static final int MAX_PLAYBACK_CANDIDATES=3;
    private static void addCandidate(LinkedHashMap<String,JSONObject> found,JSONObject song){
        if(song==null||found.size()>=MAX_PLAYBACK_CANDIDATES)return;
        String mid=song.optString("mid",song.optString("songmid"));
        if(!mid.isEmpty()&&!found.containsKey(mid))found.put(mid,song);
    }
    /** Port of UNM select.js: first results within 5 s of the requested duration, in search order. */
    private static void addUnmCandidates(LinkedHashMap<String,JSONObject> found,JSONObject track,JSONArray songs,boolean titleOnlyQuery) throws Exception {
        for(int i=0;songs!=null&&i<songs.length()&&i<QQMatch.UNM_WINDOW;i++){
            JSONObject candidate=songs.optJSONObject(i);if(candidate==null)continue;
            JSONObject normalized=toTrack(candidate);
            if(QQMatch.unmAccepts(track.optString("title",""),track.optLong("duration"),normalized.optString("title",""),normalized.optLong("duration"),titleOnlyQuery))addCandidate(found,candidate);
        }
    }
    /**
     * Ordered QQ versions to try for a NetEase-catalog track: strict title/artist matches first, then the
     * duration-based pick the desktop app's UNM module makes, then a title-only search for songs QQ files
     * under a different (Chinese or romanized) artist name.
     */
    private List<JSONObject> searchCandidates(JSONObject track,String cookie) throws Exception {
        String title=track.optString("title",""),artist=track.optString("artist","");
        LinkedHashMap<String,JSONObject> found=new LinkedHashMap<>();
        JSONArray songs=searchRaw(QQMatch.keyword(title,artist),1,10,cookie);
        List<String> aliases=ArtistAliases.fromAliasArray(track.optJSONArray("artistAliases"),artist);
        addCandidate(found,bestSearchMatch(track,songs,aliases));
        if(!Thread.currentThread().isInterrupted()&&found.isEmpty()&&aliases.isEmpty()
                &&"netease".equals(track.optString("provider",""))
                &&NetEaseAuthState.hasPositiveUserId(track.optString("id",""))){
            try {
                List<String> recovered=MusicApi.get(appContext).findExactNeteaseArtistAliases(track.optString("id",""),title,artist);
                if(!recovered.isEmpty()){
                    aliases=recovered;
                    addCandidate(found,bestSearchMatch(track,songs,aliases));
                }
            } catch(Exception ignored) {}
        }
        addUnmCandidates(found,track,songs,false);
        if(found.isEmpty()){
            for(String query:ArtistAliases.searchQueries(title,artist,aliases)){
                if(Thread.currentThread().isInterrupted())break;
                JSONArray aliasSongs=searchRaw(query,1,10,cookie);
                addCandidate(found,bestSearchMatch(track,aliasSongs,aliases));
                addUnmCandidates(found,track,aliasSongs,false);
                if(!found.isEmpty())break;
            }
        }
        if(found.isEmpty()&&!Thread.currentThread().isInterrupted()&&!artist.trim().isEmpty()){
            JSONArray titleSongs=searchRaw(title.trim(),1,10,cookie);
            addCandidate(found,bestSearchMatch(track,titleSongs,aliases));
            addUnmCandidates(found,track,titleSongs,true);
        }
        return new java.util.ArrayList<>(found.values());
    }

    public JSONObject resolve(JSONObject track) throws Exception {
        String cookie=freshCookie();List<JSONObject> candidates=new java.util.ArrayList<>();String mid=track.optString("qqMid");
        if(mid.isEmpty()&&track.optString("id").startsWith("qq:"))mid=track.optString("id").substring(3);
        if(!mid.isEmpty()){
            JSONObject match=null;
            try { JSONObject detail=request("music.pf_song_detail_svr","get_song_detail",new JSONObject().put("song_mid",mid),cookie);match=detail.optJSONObject("track_info"); } catch(Exception ignored){}
            if(match==null)match=new JSONObject().put("mid",mid).put("file",new JSONObject().put("media_mid",track.optString("mediaMid",mid)));
            candidates.add(match);
        } else candidates=searchCandidates(track,cookie);
        if(candidates.isEmpty())throw new Exception("QQ 音乐未找到相符版本，可在 QQ 曲库中搜索");
        Map<String,String> values=fields(cookie);String uin=first(values,"uin","wxuin");if(uin.isEmpty())uin="0";
        String guid=String.valueOf(1000000+new Random().nextInt(8999999));
        for(JSONObject match:candidates){
            if(Thread.currentThread().isInterrupted())break;
            mid=match.optString("mid");JSONObject file=match.optJSONObject("file");String mediaMid=file==null?mid:file.optString("media_mid",mid);
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
        }
        throw new Exception(cookie.isEmpty()?"这首歌的 QQ 音源需要登录，请在资料库中登录 QQ 音乐":"QQ 音乐未提供可播放地址，请确认登录及歌曲权益");
    }
}
