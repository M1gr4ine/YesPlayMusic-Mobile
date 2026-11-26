package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import dev.yesplaymusic.probe.ArtistAliases.Member;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.ArrayList;
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
        testExtractArtistDetailAliases();
        testReverseCatalogMatch();
        testExactSongMembers();
        testPartialQueueArtistRecovery();
        testReverseArtistNames();
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

    private static void testExtractArtistDetailAliases() throws Exception {
        JSONObject artist = new JSONObject()
                .put("id", 37520)
                .put("name", "Kanye West")
                .put("alias", new JSONArray().put("ye"))
                .put("trans", "坎耶·维斯特");
        ArtistAliases.Member detail = ArtistAliases.membersFromArtists(new JSONArray().put(artist)).get(0);
        ArtistAliases.Member credited = new ArtistAliases.Member("Kanye West", "37520", Collections.emptyList());
        List<String> aliases = MusicApi.withOfficialAliases(credited, detail).aliases;
        that(aliases.contains("ye") && aliases.contains("坎耶·维斯特"),
                "extracts alias and trans from artist detail");
        ArtistAliases.Member stale = new ArtistAliases.Member("Rihanna", "37520", Collections.emptyList());
        that(MusicApi.withOfficialAliases(stale, detail).aliases.isEmpty(),
                "an ID attached to a different visible name cannot supply its aliases");
    }

    private static void testReverseCatalogMatch() throws Exception {
        JSONObject request = new JSONObject().put("title", "Famous (Explicit)").put("artist", "Ye (侃爷)")
                .put("album", "The Life Of Pablo (Explicit)").put("duration", 196000);
        JSONObject kanye = new JSONObject().put("id", 37520).put("name", "Kanye West");
        JSONObject rihanna = new JSONObject().put("id", 72724).put("name", "Rihanna");
        JSONArray duet = new JSONArray().put(kanye).put(rihanna);
        JSONObject album = new JSONObject().put("name", "The Life Of Pablo");
        JSONObject famous = new JSONObject().put("id", 409736434).put("name", "Famous").put("dt", 196040)
                .put("al", album).put("ar", duet);
        JSONObject otherRelease = new JSONObject(famous.toString()).put("id", 484755737).put("dt", 196092);
        JSONObject single = new JSONObject(famous.toString()).put("id", 408440465)
                .put("al", new JSONObject().put("name", "Famous"));
        List<String> searches = new ArrayList<>(), details = new ArrayList<>();
        JSONObject result = MusicApi.matchTrack(request, query -> {
            searches.add(query);
            return "Famous Ye".equals(query) ? new JSONArray().put(single).put(otherRelease).put(famous) : new JSONArray();
        }, MusicApi.artistLookup(new LinkedHashMap<>(), id -> {
            details.add(id);
            return new ArtistAliases.Member("Kanye West", id, Collections.singletonList("ye"));
        }));
        that(searches.contains("Famous Ye") && details.equals(Collections.singletonList("37520")),
                "uses the clean keyword and fetches only the missing lead identity once per request");
        that(result.getBoolean("matched") && "409736434".equals(result.getString("id")),
                "returns the closest release on the same NetEase album with an evidenced lead");

        kanye.put("alias", new JSONArray().put("ye"));
        JSONObject fullCredit = new JSONObject(request.toString()).put("artist", "Ye (侃爷) / Rihanna");
        result = MusicApi.matchTrack(fullCredit, query -> new JSONArray().put(single).put(famous),
                id -> { throw new AssertionError("search already supplied official aliases"); });
        that("409736434".equals(result.getString("id")), "complete artist credit prefers the matching album");

        JSONObject stranger = new JSONObject(famous.toString()).put("id", 987)
                .put("ar", new JSONArray().put(rihanna).put(kanye));
        JSONObject live = new JSONObject(famous.toString()).put("id", 988).put("name", "Famous (Live)");
        JSONObject preview = new JSONObject(famous.toString()).put("id", 989).put("dt", 30000);
        result = MusicApi.matchTrack(request, query -> new JSONArray().put(stranger).put(single).put(live).put(preview),
                id -> null);
        that(!result.getBoolean("matched") && !result.has("id"),
                "shared guest, different album, live recording and preview cannot authorize a playlist ID");

        JSONObject conflict = new JSONObject(famous.toString()).put("id", 986).put("ar", new JSONArray().put(kanye)
                .put(new JSONObject().put("name", "Another Guest").put("id", 123)));
        result = MusicApi.matchTrack(request, query -> new JSONArray().put(famous).put(conflict), id -> null);
        that(!result.getBoolean("matched"), "distinct collaborative credits on the same album remain ambiguous");
    }

    private static void testExactSongMembers() throws Exception {
        JSONArray artists = new JSONArray().put(new JSONObject().put("id", 37520).put("name", "Kanye West"))
                .put(new JSONObject().put("id", 72724).put("name", "Rihanna"));
        JSONObject details = new JSONObject().put("songs", new JSONArray()
                .put(new JSONObject().put("id", 5).put("artists", new JSONArray()))
                .put(new JSONObject().put("id", 409736434).put("artists", artists)));
        List<ArtistAliases.Member> members = MusicApi.exactSongMembers(details, "409736434");
        that(members.size() == 2 && "37520".equals(members.get(0).providerId) && "Rihanna".equals(members.get(1).name),
                "restores ordered, named member IDs from the exact legacy song detail");
        that(MusicApi.exactSongMembers(details, "7").isEmpty(), "a different song cannot supply member IDs");
        List<Member> partial = Collections.singletonList(new Member("Kanye West", "37520", Collections.singletonList("ye")));
        List<Member> recovered = MusicApi.findNeteaseArtistMembers("409736434", "Kanye West / Rihanna", partial,
                () -> details, id -> null);
        that(recovered.size() == 2 && recovered.get(0).aliases.contains("ye") && recovered.get(1).aliases.isEmpty(),
                "song detail restores the guest while retaining the lead's search aliases");
    }

    private static void testPartialQueueArtistRecovery() throws Exception {
        List<Member> partial = new ArrayList<>();
        partial.add(new Member("Kanye West", "37520", Collections.singletonList("坎耶·维斯特")));
        String credit = "Kanye West";
        for (int i = 1; i <= 6; i++) {
            partial.add(new Member("Guest " + i, String.valueOf(i), Collections.emptyList()));
            credit += " / Guest " + i;
        }
        credit += " / Rihanna";
        LinkedHashMap<String, Member> cache = new LinkedHashMap<>();
        cache.put("2", new Member("Guest 2", "2", Collections.singletonList("Two")));
        cache.put("6", new Member("Guest 6", "6", Collections.singletonList("Six")));
        List<String> fetched = new ArrayList<>();
        List<Member> restored = MusicApi.findNeteaseArtistMembers("409736434", credit, partial,
                () -> { throw new SocketTimeoutException(); }, MusicApi.artistLookup(cache, id -> {
                    fetched.add(id);
                    return "37520".equals(id) ? new Member("Kanye West", id, Collections.singletonList("ye")) : null;
                }));
        that(restored.get(0).aliases.containsAll(Arrays.asList("ye", "坎耶·维斯特")),
                "a song detail timeout still enriches the known artist ID without losing catalog aliases");
        that(fetched.equals(Arrays.asList("37520", "1", "3", "4")) && restored.get(6).aliases.contains("Six"),
                "cache hits remain available after four artist detail requests");

        InterruptedException cancelled = new InterruptedException();
        try {
            MusicApi.findNeteaseArtistMembers("409736434", credit, partial,
                    () -> { throw cancelled; }, id -> { throw new AssertionError("cancelled lookup"); });
            throw new AssertionError("expected interruption");
        } catch (InterruptedException expected) {
            that(expected == cancelled, "the original interruption reaches the caller");
        }
    }

    private static void testReverseArtistNames() throws Exception {
        JSONObject request = new JSONObject().put("title", "Song").put("duration", 200000).put("artist", "A & B")
                .put("artistMembers", ArtistAliases.membersToJson(Collections.singletonList(
                        new Member("A & B", "", Collections.singletonList("Duo")))));
        JSONObject song = new JSONObject().put("id", 11).put("name", "Song").put("dt", 200000)
                .put("ar", new JSONArray().put(new JSONObject().put("id", 12).put("name", "A")));
        JSONObject result = MusicApi.matchTrack(request, query -> new JSONArray().put(song),
                id -> new Member("A", id, Collections.singletonList("Duo")));
        that(result.getBoolean("matched"), "a token inside a band name does not suppress the official alias lookup");

        request.remove("artistMembers");
        request.put("artist", "A-B");
        song.put("ar", new JSONArray().put(new JSONObject().put("name", "A-B")));
        JSONObject second = new JSONObject(song.toString()).put("id", 22)
                .put("ar", new JSONArray().put(new JSONObject().put("name", "a-b")));
        JSONArray releases = new JSONArray().put(song).put(second);
        that(MusicApi.matchTrack(request, query -> releases, id -> null).getBoolean("matched"),
                "case alone does not make ID-less credits ambiguous");
        second.getJSONArray("ar").getJSONObject(0).put("name", "AB");
        that(!MusicApi.matchTrack(request, query -> releases, id -> null).getBoolean("matched"),
                "ID-less credits with distinct punctuation still conflict");
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
