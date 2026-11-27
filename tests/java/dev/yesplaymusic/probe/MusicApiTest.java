package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class MusicApiTest {
    private MusicApiTest() {}

    static void run() throws Exception {
        testExtractPlayableItemMatchesExactId();
        testExtractPlayableItemRejectsCrossTalk();
        testExtractPlayableItemClassifiesCodesAndTrials();
        testApplySetCookiesRotationAndDeletion();
        testReverseCatalogMatch();
        testStreamHosts();
    }

    private static void testExtractPlayableItemMatchesExactId() throws Exception {
        JSONObject response = new JSONObject()
                .put("code", 200)
                .put("data", new JSONArray()
                        .put(new JSONObject().put("id", 7).put("code", 200)
                                .put("url", "http://m8.music.126.net/wrong.mp3").put("time", 200000))
                        .put(new JSONObject().put("id", 409736441).put("code", 200)
                                .put("url", "http://m8.music.126.net/fml.mp3").put("time", 236120).put("br", 320000).put("type", "mp3")));
        JSONObject item = MusicApi.extractPlayableItem(response, "409736441");
        that("409736441".equals(item.getString("id")), "selects the matching track id even when second");
        that("https://m8.music.126.net/fml.mp3".equals(item.getString("url")), "normalizes to https");
        that(!item.getBoolean("trial"), "full stream is not marked trial");
    }

    private static void testExtractPlayableItemRejectsCrossTalk() throws Exception {
        JSONObject response = new JSONObject()
                .put("code", 200)
                .put("data", new JSONArray()
                        .put(new JSONObject().put("id", 7).put("code", 200)
                                .put("url", "https://m8.music.126.net/wrong.mp3").put("time", 200000)));
        expectResolveCode(response, "42", "netease_id_mismatch", "rejects mismatched id in data[0]");
    }

    private static void testExtractPlayableItemClassifiesCodesAndTrials() throws Exception {
        expectResolveCode(new JSONObject().put("code", 301), "42", "netease_auth_expired", "301 means auth expired");
        expectResolveCode(new JSONObject().put("code", -462), "42", "netease_rate_limited", "-462 means rate limited");

        JSONObject trial = new JSONObject().put("code", 200).put("data", new JSONArray()
                .put(new JSONObject().put("id", 42).put("code", 200)
                        .put("url", "https://m8.music.126.net/preview.mp3")
                        .put("freeTrialInfo", new JSONObject().put("start", 30).put("end", 60))));
        expectResolveCode(trial, "42", "trial_unavailable", "freeTrialInfo is rejected");

        JSONObject emptyUrl = new JSONObject().put("code", 200).put("data", new JSONArray()
                .put(new JSONObject().put("id", 42).put("code", -110).put("url", JSONObject.NULL)));
        expectResolveCode(emptyUrl, "42", "netease_rights_unavailable", "null url reports rights unavailable");

        JSONObject untrustedHost = new JSONObject().put("code", 200).put("data", new JSONArray()
                .put(new JSONObject().put("id", 42).put("code", 200).put("url", "https://evil.example/song.mp3")));
        expectResolveCode(untrustedHost, "42", "stream_unavailable", "untrusted host is rejected");
    }

    private static void expectResolveCode(JSONObject response, String id, String expectedCode, String message)
            throws Exception {
        try {
            MusicApi.extractPlayableItem(response, id);
            that(false, message + " (expected " + expectedCode + ")");
        } catch (MusicApi.ResolveException error) {
            that(expectedCode.equals(error.code), message + " (got " + error.code + ")");
        }
    }

    private static void testApplySetCookiesRotationAndDeletion() {
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        cookies.put("MUSIC_U", "old_token");
        cookies.put("__csrf", "csrf1");
        long now = 1_800_000_000_000L;

        boolean changed = MusicApi.applySetCookies(cookies,
                Collections.singletonList("MUSIC_U=new_token; Path=/; HttpOnly"), now);
        that(changed && "new_token".equals(cookies.get("MUSIC_U")), "rotates MUSIC_U and marks change");

        boolean deletedByMaxAge = MusicApi.applySetCookies(cookies,
                Collections.singletonList("MUSIC_U=stale_val; Max-Age=0; Path=/"), now);
        that(deletedByMaxAge && !cookies.containsKey("MUSIC_U"), "Max-Age=0 deletes cookie even with non-empty value");

        cookies.put("MUSIC_U", "temp");
        boolean deletedByExpires = MusicApi.applySetCookies(cookies,
                Collections.singletonList("MUSIC_U=temp; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"), now);
        that(deletedByExpires && !cookies.containsKey("MUSIC_U"), "past Expires deletes cookie");

        boolean keptFuture = MusicApi.applySetCookies(cookies,
                Collections.singletonList("MUSIC_U=fresh; Max-Age=3600; Path=/"), now);
        that(keptFuture && "fresh".equals(cookies.get("MUSIC_U")), "positive Max-Age keeps cookie");
    }

    private static void testReverseCatalogMatch() throws Exception {
        JSONObject kanye = new JSONObject().put("id", 37520).put("name", "Kanye West");
        JSONObject rihanna = new JSONObject().put("id", 72724).put("name", "Rihanna");
        JSONObject album = new JSONObject().put("id", 34565008).put("name", "The Life Of Pablo");
        JSONObject famousNoAlias = new JSONObject().put("id", 409736434).put("name", "Famous").put("dt", 196040)
                .put("al", album).put("ar", new JSONArray().put(kanye));

        // Reverse match requires artist overlap: Ye (侃爷) without alias cannot write Kanye West to account.
        JSONObject soloQq = new JSONObject().put("title", "Famous (Explicit)").put("artist", "Ye (侃爷)")
                .put("duration", 196000);
        JSONObject unmatched = MusicApi.matchTrack(soloQq, query -> new JSONArray().put(famousNoAlias));
        that(!unmatched.getBoolean("matched") && !unmatched.has("id"),
                "reverse match returns matched:false when artists do not overlap");

        // When NetEase search result carries alias "ye", fallback keyword matches cleanly.
        JSONObject kanyeWithAlias = new JSONObject(kanye.toString()).put("alias", new JSONArray().put("ye"));
        JSONObject famous = new JSONObject().put("id", 409736434).put("name", "Famous").put("dt", 196040)
                .put("al", album).put("ar", new JSONArray().put(kanyeWithAlias).put(rihanna));
        JSONObject preview = new JSONObject(famous.toString()).put("id", 989).put("dt", 30000);
        JSONObject live = new JSONObject(famous.toString()).put("id", 988).put("name", "Famous (Live)");
        List<String> queries = new ArrayList<>();
        JSONObject matched = MusicApi.matchTrack(soloQq, query -> {
            queries.add(query);
            return "Famous - Ye".equals(query) ? new JSONArray().put(preview).put(live).put(famous) : new JSONArray();
        });
        that(queries.equals(Arrays.asList("Famous (Explicit) - Ye (侃爷)", "Famous - Ye")),
                "reverse match tries UNM keyword then cleaned fallback keyword");
        that(matched.getBoolean("matched") && "409736434".equals(matched.getString("id"))
                        && "Kanye West / Rihanna".equals(matched.getString("artist")),
                "skips 30s preview and live version and returns matched NetEase track");

        // Overlapping collaborator matches on first query; unknown duration works as fallback.
        JSONObject collabQq = new JSONObject().put("title", "Famous").put("artist", "Ye (侃爷) / Rihanna")
                .put("duration", 196000);
        JSONObject unknownDuration = new JSONObject().put("id", 408440465).put("name", "Famous").put("dt", 0)
                .put("al", album).put("ar", new JSONArray().put(kanye).put(rihanna));
        List<String> collabQueries = new ArrayList<>();
        JSONObject fallbackDuration = MusicApi.matchTrack(collabQq, query -> {
            collabQueries.add(query);
            return new JSONArray().put(preview).put(unknownDuration);
        });
        that(collabQueries.equals(Collections.singletonList("Famous - Ye (侃爷) / Rihanna")),
                "matching on primary query avoids fallback search");
        that(fallbackDuration.getBoolean("matched") && "408440465".equals(fallbackDuration.getString("id")),
                "unknown duration acts as fallback when artist overlaps");

        // Empty artist never matches in reverse direction.
        JSONObject missingArtist = new JSONObject().put("title", "Famous").put("artist", "").put("duration", 196000);
        that(!MusicApi.matchTrack(missingArtist, query -> new JSONArray().put(famous)).getBoolean("matched"),
                "missing artist returns matched:false for reverse match");
    }

    private static void testStreamHosts() {
        that("https://ws.stream.qqmusic.qq.com/a.mp3".equals(
                StreamHosts.normalizeStreamUrl("http://ws.stream.qqmusic.qq.com/a.mp3", "qq")),
                "qqmusic stream host allowed and upgraded to https");
        that("https://aqqmusic.tc.qq.com/b.m4a".equals(
                StreamHosts.normalizeStreamUrl("https://aqqmusic.tc.qq.com/b.m4a", "qq")),
                "tc.qq.com stream host allowed");
        that(StreamHosts.normalizeStreamUrl("https://www.qq.com/not-audio", "qq").isEmpty(),
                "generic qq.com host is not allowed as audio stream");
        that(StreamHosts.normalizeStreamUrl("https://evil.example/a.mp3", "netease").isEmpty(),
                "unrelated host rejected for netease");
        that("https://m701.music.126.net/x.mp3".equals(
                StreamHosts.normalizeStreamUrl("http://m701.music.126.net/x.mp3", "netease")),
                "music.126.net allowed for netease");
    }
}
