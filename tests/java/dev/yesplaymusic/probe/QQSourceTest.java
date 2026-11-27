package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static dev.yesplaymusic.probe.Check.that;

final class QQSourceTest {
    private QQSourceTest() {}

    static void run() throws Exception {
        String wx = "uin=o00999; wxuin=o00123; login_type=2; qqmusic_key=W_X_test; qqrefresh_token=unused";
        that("123".equals(QQSource.credentials(wx).uin), "WeChat music account takes precedence");
        that(!QQSource.credentials(wx).canRefresh, "WeChat token never enters QQ refresh");
        that(QQSource.sessionCookie(wx).contains("uin=o00999"), "preserve distinct QQ account field");
        that("456".equals(QQSource.credentials("uin=invalid; wxuin=o00456; qm_keyst=test").uin),
                "invalid primary account falls back to valid alternate");
        that(!QQSource.credentials("uin=o00123; qqmusic_key=bad\r\nX-Injected:1").loggedIn
                && QQSource.sessionCookie("uin=o00123; qqmusic_key=bad\r\nX-Injected:1").isEmpty(),
                "CRLF header injection in cookie values is rejected");
        transientSearch();
        providerCodes();
        filterResults();
        matching();
        cachedAndNative();
        vkeyAndHosts();
        denialsAndCancellation();
        lyrics();
    }

    private static void transientSearch() throws Exception {
        int[] calls = {0};
        JSONObject page = new JSONObject();
        JSONObject result = QQSource.searchWithRetry(() -> {
            if (++calls[0] == 1) throw new QQSource.ResolveException("stream_unavailable", "2001");
            return page;
        });
        that(result == page && calls[0] == 2, "a transient QQ search failure retries once");
        calls[0] = 0;
        try {
            QQSource.searchWithRetry(() -> {
                calls[0]++;
                throw new QQSource.ResolveException("stream_unavailable", "2001");
            });
            throw new AssertionError("repeated refusal must be reported");
        } catch (QQSource.ResolveException expected) {
            that(calls[0] == 2, "a persistent 2001 is bounded to one retry");
        }
        calls[0] = 0;
        try {
            QQSource.searchWithRetry(() -> {
                calls[0]++;
                throw new QQSource.ResolveException("qq_region_locked", "104003");
            });
            throw new AssertionError("expected search refusal");
        } catch (QQSource.ResolveException expected) {
            that(calls[0] == 1 && "qq_region_locked".equals(expected.code), "permanent search refusal is surfaced immediately");
        }
    }

    private static void providerCodes() throws Exception {
        that(QQSource.providerCode(new JSONObject().put("result", "104009"), "result") == 104009,
                "numeric provider status strings keep their original value");
        for (Object value : Arrays.asList("invalid", JSONObject.NULL, 0.5)) {
            try {
                QQSource.providerCode(new JSONObject().put("result", value), "result");
                throw new AssertionError("malformed result cannot grant a file");
            } catch (QQSource.ResolveException expected) {
                that("stream_unavailable".equals(expected.code), "malformed status is not a grant");
            }
        }
    }

    private static Track target() throws Exception {
        return Track.fromJson(new JSONObject().put("id", "409736441").put("title", "FML")
                .put("artist", "Kanye West / The Weeknd").put("album", "The Life Of Pablo")
                .put("duration", 236120).put("artistRefs", new JSONArray()
                        .put(new JSONObject().put("id", "37520").put("name", "Kanye West"))));
    }

    private static Track single() throws Exception {
        return Track.fromJson(new JSONObject().put("id", "99").put("title", "Song")
                .put("artist", "Artist").put("album", "Album").put("duration", 200000));
    }

    private static JSONObject song(String mid, String file, String title, String artist, String album, int seconds) throws Exception {
        return song(mid, file, title, new String[]{artist}, album, seconds);
    }

    private static JSONObject song(String mid, String file, String title, String[] singers, String album, int seconds) throws Exception {
        JSONArray singerArray = new JSONArray();
        for (int i = 0; i < singers.length; i++) {
            singerArray.put(new JSONObject().put("mid", "singer" + i).put("name", singers[i]));
        }
        return new JSONObject().put("mid", mid).put("file", new JSONObject().put("media_mid", file))
                .put("title", title).put("singer", singerArray)
                .put("album", new JSONObject().put("name", album)).put("interval", seconds);
    }

    private static void filterResults() throws Exception {
        JSONObject good = song("song1", "file1", "Song", "Artist", "Album", 200);
        JSONArray filtered = QQSource.filterSearchResults(new JSONArray().put(good).put(JSONObject.NULL)
                .put(song("", "file2", "Song", "Artist", "Album", 200))
                .put(song("song3", "", "Song", "Artist", "Album", 200))
                .put(song("bad/slash", "file4", "Song", "Artist", "Album", 200))
                .put(new JSONObject().put("mid", 123).put("file", new JSONObject().put("media_mid", "file"))));
        that(filtered.length() == 1 && filtered.getJSONObject(0) == good,
                "search filters malformed song and file identities without altering valid rows");
    }

    private static void matching() throws Exception {
        // FML must not be replaced by same-artist, similar-duration Flashing Lights (237s).
        Fake flashingOnly = new Fake();
        flashingOnly.results.put("FML - Kanye West / The Weeknd", new JSONArray()
                .put(song("flashing", "flashingfile", "Flashing Lights (Explicit)", "Ye", "Graduation", 237)));
        expectError(target(), flashingOnly, "0", "match_not_found", null);
        that(flashingOnly.requests.isEmpty(), "Flashing Lights never triggers a vkey request for FML");
        that(flashingOnly.queries.equals(Arrays.asList("FML - Kanye West / The Weeknd", "FML - Kanye West")),
                "searches UNM keyword then first-artist fallback without bare-title search");

        // Renamed artist (Ye (侃爷) vs Kanye West) still resolves in QQ order on the first search without aliases.
        Fake fml = new Fake();
        fml.results.put("FML - Kanye West / The Weeknd", new JSONArray()
                .put(song("flashing", "flashingfile", "Flashing Lights (Explicit)", "Ye", "Graduation", 237))
                .put(song("0005toRJ3QqtAx", "003mHViD1MfUDY", "FML (Explicit)", "Ye (侃爷)", "The Life Of Pablo (Explicit)", 236)));
        List<JSONObject> fmlResolved = QQSource.lookupAttempts(target(), fml, "0");
        that("0005toRJ3QqtAx".equals(fmlResolved.get(0).getString("qqMid")),
                "FML resolves to Ye (侃爷) despite mismatched artist credit");
        that(fml.queries.equals(Collections.singletonList("FML - Kanye West / The Weeknd")) && fml.requests.size() == 1,
                "resolves in one search and one vkey request");

        // Fallback search strips benign Explicit annotation and keeps only the first artist.
        Track explicitCollab = Track.fromJson(new JSONObject().put("id", "409736441").put("title", "FML (Explicit)")
                .put("artist", "Kanye West / The Weeknd").put("duration", 236120));
        Fake fallback = new Fake();
        fallback.results.put("FML - Kanye West", new JSONArray()
                .put(song("0005toRJ3QqtAx", "003mHViD1MfUDY", "FML (Explicit)", "Ye (侃爷)", "The Life Of Pablo", 236)));
        that("0005toRJ3QqtAx".equals(QQSource.lookupAttempts(explicitCollab, fallback, "0").get(0).getString("qqMid"))
                        && fallback.queries.equals(Arrays.asList("FML (Explicit) - Kanye West / The Weeknd", "FML - Kanye West")),
                "fallback retries once without Explicit and with lead artist only");

        // Only the first 5 playable results (with mid and media_mid) are examined.
        Fake capped = new Fake();
        capped.results.put("Song - Artist", new JSONArray()
                .put(song("unplayable", "", "Song", "Artist", "Album", 200))
                .put(song("other1", "f1", "Other 1", "Artist", "Album", 200))
                .put(song("other2", "f2", "Other 2", "Artist", "Album", 200))
                .put(song("other3", "f3", "Other 3", "Artist", "Album", 200))
                .put(song("other4", "f4", "Other 4", "Artist", "Album", 200))
                .put(song("other5", "f5", "Other 5", "Artist", "Album", 200))
                .put(song("sixth", "f6", "Song", "Artist", "Album", 200)));
        expectError(single(), capped, "0", "match_not_found", null);
        that(capped.requests.isEmpty(), "sixth playable result is outside the top-5 window");

        // Famous excludes 30s snippet; overlapping artist goes first; no_purl falls back to next candidate (max 3).
        Track famousNetease = Track.fromJson(new JSONObject()
                .put("id", "409736434").put("title", "Famous")
                .put("artist", "Kanye West / Rihanna").put("duration", 196040));
        JSONArray famousHits = new JSONArray()
                .put(song("0023yUmC3Ss6Xm", "file30s", "Famous", new String[]{"Kanye West", "Rihanna"}, "Famous", 30))
                .put(song("002XelTe31rBEE", "fileAlbum", "Famous (Explicit)", new String[]{"Ye (侃爷)"}, "The Life Of Pablo", 196))
                .put(song("003Z0JlC3c9Jwx", "fileCollab", "Famous", new String[]{"Ye (侃爷)", "Rihanna"}, "Famous", 195));
        Fake famous = new Fake();
        famous.results.put("Famous - Kanye West / Rihanna", famousHits);
        that("003Z0JlC3c9Jwx".equals(QQSource.lookupAttempts(famousNetease, famous, "0").get(0).getString("qqMid"))
                        && famous.requests.size() == 1,
                "overlapping Rihanna credit takes priority and 30s preview is ignored");

        Fake famousFallback = new Fake();
        famousFallback.results.put("Famous - Kanye West / Rihanna", famousHits);
        famousFallback.noPurl.add("003Z0JlC3c9Jwx");
        that("002XelTe31rBEE".equals(QQSource.lookupAttempts(famousNetease, famousFallback, "0").get(0).getString("qqMid"))
                        && famousFallback.requests.size() == 2
                        && !"0023yUmC3Ss6Xm".equals(famousFallback.requests.get(0).getJSONArray("songmid").getString(0))
                        && !"0023yUmC3Ss6Xm".equals(famousFallback.requests.get(1).getJSONArray("songmid").getString(0)),
                "falls back to second matching candidate after no_purl without ever requesting 30s preview");

        Fake maxThree = new Fake();
        maxThree.results.put("Song - Artist", new JSONArray()
                .put(song("m1", "f1", "Song", "Artist", "Album", 200))
                .put(song("m2", "f2", "Song", "Artist", "Album", 200))
                .put(song("m3", "f3", "Song", "Artist", "Album", 200))
                .put(song("m4", "f4", "Song", "Artist", "Album", 200)));
        maxThree.noPurl.addAll(Arrays.asList("m1", "m2", "m3", "m4"));
        expectError(single(), maxThree, "0", "qq_rights_unavailable", "no_purl");
        that(maxThree.requests.size() == 3, "vkey tries at most three candidates");

        // Acapella and HOOLIGANS VERSION are rejected; studio version succeeds.
        Track carnival = Track.fromJson(new JSONObject()
                .put("id", "2125735002").put("title", "CARNIVAL")
                .put("artist", "¥$ / Kanye West / Ty Dolla $ign").put("duration", 264000));
        Fake badVersions = new Fake();
        badVersions.results.put("CARNIVAL - ¥$ / Kanye West / Ty Dolla $ign", new JSONArray()
                .put(song("badAca", "fAca", "CARNIVAL (Acapella)", new String[]{"¥$", "Ye (侃爷)", "Ty Dolla $ign"}, "VULTURES 1", 264))
                .put(song("badHool", "fHool", "CARNIVAL (HOOLIGANS VERSION)", new String[]{"¥$", "Ye (侃爷)", "Ty Dolla $ign"}, "VULTURES 1", 264)));
        expectError(carnival, badVersions, "0", "match_not_found", null);
        that(badVersions.requests.isEmpty(), "Acapella and HOOLIGANS VERSION never request a vkey");

        Fake durationCases = new Fake();
        durationCases.results.put("Song - Artist", new JSONArray()
                .put(song("wrongLen", "fWrong", "Song", "Artist", "Album", 120)));
        expectError(single(), durationCases, "0", "match_not_found", null);

        Fake unknownDuration = new Fake();
        unknownDuration.results.put("Song - Artist", new JSONArray()
                .put(song("wrongLen", "fWrong", "Song", "Artist", "Album", 120))
                .put(song("noLen", "fNoLen", "Song", "Artist", "Album", 0)));
        that("noLen".equals(QQSource.lookupAttempts(single(), unknownDuration, "0").get(0).getString("qqMid")),
                "candidate without duration acts as fallback when known durations mismatch");
    }

    private static void cachedAndNative() throws Exception {
        JSONObject base = single().toJson(false);
        base.put("qqMid", "wrong");
        Fake stale = new Fake();
        stale.details.put("wrong", song("wrong", "wrongFile", "Different", "Someone", "Album", 200));
        stale.results.put("Song - Artist", new JSONArray().put(song("right", "rightFile", "Song", "Artist", "Album", 200)));
        that("right".equals(QQSource.lookupAttempts(Track.fromJson(base), stale, "0").get(0).getString("qqMid")),
                "legacy MID with mismatching detail title is searched again");
        that(stale.requests.size() == 1 && "right".equals(stale.requests.get(0).getJSONArray("songmid").getString(0)),
                "wrong cached MID is never sent for vkey");

        Fake wrongDuration = new Fake();
        wrongDuration.details.put("short", song("short", "shortFile", "Song", "Artist", "Album", 30));
        wrongDuration.results.put("Song - Artist", new JSONArray().put(song("right", "rightFile", "Song", "Artist", "Album", 200)));
        base.put("qqMid", "short");
        that("right".equals(QQSource.lookupAttempts(Track.fromJson(base), wrongDuration, "0").get(0).getString("qqMid"))
                        && wrongDuration.requests.size() == 1,
                "legacy MID with mismatching detail duration is searched again");

        Fake unavailable = new Fake();
        unavailable.details.put("old", song("old", "oldFile", "Song", "Renamed Artist", "Album", 200));
        unavailable.noPurl.add("old");
        unavailable.results.put("Song - Artist", new JSONArray().put(song("new", "newFile", "Song", "Artist", "Album", 200)));
        base.put("qqMid", "old");
        that("new".equals(QQSource.lookupAttempts(Track.fromJson(base), unavailable, "0").get(0).getString("qqMid")),
                "legacy MID reverified via select still searches after a no-purl response");
        that(unavailable.requests.size() == 2, "old and replacement recordings are each tried once");

        Fake nativeApi = new Fake();
        Track nativeTrack = Track.fromJson(new JSONObject().put("id", "qq:A").put("qqMid", "B")
                .put("mediaMid", "wrongFile").put("duration", 200000));
        List<JSONObject> nativeResult = QQSource.lookupAttempts(nativeTrack, nativeApi, "0");
        JSONObject request = nativeApi.requests.get(0);
        that(nativeApi.detailCalls.equals(Arrays.asList("A")) && nativeApi.queries.isEmpty()
                && "A".equals(request.getJSONArray("songmid").getString(0))
                && "M800A.mp3".equals(request.getJSONArray("filename").getString(0))
                && "A".equals(nativeResult.get(0).getString("qqMid")),
                "native catalog ID wins a conflicting cached MID even when detail is unavailable");
    }

    private static void vkeyAndHosts() throws Exception {
        Fake api = new Fake();
        api.details.put("songA", song("songA", "fileA", "Song", "Artist", "Album", 200));
        api.reverse = true;
        api.sips = new JSONArray().put("http://a.qqmusic.qq.com/").put("https://b.qqmusic.qq.com/")
                .put("https://a.qqmusic.qq.com/").put("https://b.qqmusic.qq.com/").put("https://evil.example/");
        List<JSONObject> attempts = QQSource.lookupAttempts(Track.fromJson(new JSONObject().put("id", "qq:songA")), api, "123");
        JSONObject request = api.requests.get(0);
        that(request.opt("guid") instanceof String && request.getString("guid").matches("[1-9][0-9]{9}"),
                "GUID is a ten-digit JSON string");
        that(request.getJSONArray("filename").toString().equals("[\"M800fileA.mp3\",\"M500fileA.mp3\",\"C400fileA.m4a\"]")
                && request.getJSONArray("songmid").toString().equals("[\"songA\",\"songA\",\"songA\"]"),
                "vkey batches all qualities with file MID in filenames and song MID in songmid");
        that(attempts.size() == 6 && "320k".equals(attempts.get(0).getString("quality"))
                && attempts.get(0).getString("url").startsWith("https://b.qqmusic.qq.com/")
                && "128k".equals(attempts.get(2).getString("quality"))
                && "AAC".equals(attempts.get(4).getString("quality")),
                "response order cannot change quality preference; HTTPS endpoints come first and duplicates collapse");
        Fake mismatched = new Fake(); mismatched.wrongRowMid = true;
        expectError(Track.fromJson(new JSONObject().put("id", "qq:A")), mismatched, "0", "stream_unavailable", "vkey_identity_mismatch");
        Fake absoluteEvil = new Fake(); absoluteEvil.purlOverride = "https://evil.example/steal";
        expectError(Track.fromJson(new JSONObject().put("id", "qq:A")), absoluteEvil, "0", "stream_unavailable", "no_trusted_endpoint");
        Fake malformed = new Fake(); malformed.purlOverride = "http://[invalid";
        expectError(Track.fromJson(new JSONObject().put("id", "qq:A")), malformed, "0", "stream_unavailable", "no_trusted_endpoint");
        Fake unsafe = new Fake();
        unsafe.sips = new JSONArray().put("https://evil.example/").put("https://bad host/").put("not a url");
        expectError(Track.fromJson(new JSONObject().put("id", "qq:A")), unsafe, "0", "stream_unavailable", "no_trusted_endpoint");
    }

    private static void denialsAndCancellation() throws Exception {
        Track nativeTrack = Track.fromJson(new JSONObject().put("id", "qq:A"));
        for (String uin : Arrays.asList("0", "123")) {
            Fake region = new Fake(); region.retcode = 104003;
            expectError(nativeTrack, region, uin, "qq_region_locked", "104003");
            Fake auth = new Fake(); auth.retcode = 104009;
            expectError(nativeTrack, auth, uin, "0".equals(uin) ? "qq_auth_required" : "qq_auth_expired", "104009");
            Fake rowRegion = new Fake(); rowRegion.rowCode = 104003;
            expectError(nativeTrack, rowRegion, uin, "qq_region_locked", "104003");
            Fake rowAuth = new Fake(); rowAuth.rowCode = 104009;
            expectError(nativeTrack, rowAuth, uin, "0".equals(uin) ? "qq_auth_required" : "qq_auth_expired", "104009");
            Fake noPurl = new Fake(); noPurl.noPurl.add("A");
            expectError(nativeTrack, noPurl, uin, "qq_rights_unavailable", "no_purl");
        }
        Fake cancelled = new Fake();
        Thread.currentThread().interrupt();
        boolean interrupted = false;
        try { QQSource.lookupAttempts(nativeTrack, cancelled, "0"); }
        catch (InterruptedException expected) { interrupted = true; }
        finally { Thread.interrupted(); }
        that(interrupted && cancelled.detailCalls.isEmpty() && cancelled.requests.isEmpty(),
                "cancellation stops before provider requests");
        Fake midSearch = new Fake(); midSearch.interruptSearch = true;
        interrupted = false;
        try { QQSource.lookupAttempts(single(), midSearch, "0"); }
        catch (InterruptedException expected) { interrupted = true; }
        finally { Thread.interrupted(); }
        that(interrupted && midSearch.requests.isEmpty(), "cancellation during search stops before vkey");
    }

    private static void expectError(Track track, Fake api, String uin, String code, String detail) throws Exception {
        try {
            QQSource.lookupAttempts(track, api, uin);
            throw new AssertionError("expected " + code);
        } catch (QQSource.ResolveException expected) {
            that(code.equals(expected.code) && (detail == null || detail.equals(expected.detail)),
                    "expected " + code + "/" + detail + ", got " + expected.code + "/" + expected.detail);
        }
    }

    private static void lyrics() throws Exception {
        String original = "[00:00.00]Like Him\n[00:12.40]Second line";
        String translation = "[00:12.40]第二行";
        LyricFake modern = new LyricFake();
        modern.modernResult = new JSONObject().put("lyric", original).put("trans", translation);
        JSONObject result = QQSource.resolveLyrics("qq:000LikeHim", modern);
        assertLyrics(result, original, translation, "modern timed lyrics and translation");
        that(modern.modernCalls == 1 && modern.legacyCalls == 0 && "000LikeHim".equals(modern.lastMid),
                "native prefix is removed before the one modern request");

        modern = new LyricFake();
        modern.modernResult = new JSONObject().put("lyric", "[00:03.00]Verse\n[00:04.00]Chorus");
        assertLyrics(QQSource.resolveLyrics("LikeHim", modern), "[00:03.00]Verse\n[00:04.00]Chorus", "",
                "modern timed original without translation");
        that(modern.legacyCalls == 0, "missing translation does not discard a timed original");

        modern = new LyricFake();
        modern.modernResult = new JSONObject().put("retcode", 0).put("code", 0)
                .put("lyric", "&#91;00&#58;01&#46;00&#93;A &amp; B")
                .put("trans", "WzAwOjAxLjAwXUhlbGxv");
        assertLyrics(QQSource.resolveLyrics("LikeHim", modern), "[00:01.00]A & B", "[00:01.00]Hello",
                "modern sections decode entities and base64");
        that(modern.legacyCalls == 0, "decoded modern timed lyric avoids fallback");

        LyricFake fallback = new LyricFake();
        fallback.modernResult = new JSONObject().put("lyric", "[ar:Like Him]");
        fallback.legacyResult = new JSONObject().put("retcode", 0)
                .put("lyric", "[00:01.00]Legacy");
        assertLyrics(QQSource.resolveLyrics("LikeHim", fallback), "[00:01.00]Legacy", "",
                "metadata alone falls back to timed legacy lyrics");
        that(fallback.modernCalls == 1 && fallback.legacyCalls == 1, "untimed modern result falls back once");

        for (JSONObject badModern : Arrays.asList(
                new JSONObject().put("retcode", 1).put("lyric", "[00:01.00]Bad"),
                new JSONObject().put("code", 1).put("lyric", "[00:01.00]Bad"),
                new JSONObject().put("retcode", 0).put("code", 1).put("lyric", "[00:01.00]Bad"),
                new JSONObject().put("retcode", 1).put("code", 0).put("lyric", "[00:01.00]Bad"),
                new JSONObject().put("retcode", "invalid").put("lyric", "[00:01.00]Bad"))) {
            fallback = new LyricFake();
            fallback.modernResult = badModern;
            fallback.legacyResult = new JSONObject().put("code", 0).put("lyric", "[00:02.00]Good");
            assertLyrics(QQSource.resolveLyrics("LikeHim", fallback), "[00:02.00]Good", "",
                    "unsuccessful modern status falls back");
            that(fallback.legacyCalls == 1, "modern provider status cannot be ignored");
        }

        fallback = new LyricFake();
        fallback.modernError = new QQSource.ResolveException("network_error", "fixture");
        fallback.legacyResult = new JSONObject().put("retcode", 0).put("lyric", "[00:02.00]Recovered");
        assertLyrics(QQSource.resolveLyrics("LikeHim", fallback), "[00:02.00]Recovered", "",
                "modern request failure can recover via legacy");

        for (JSONObject empty : Arrays.asList(new JSONObject().put("retcode", -1901),
                new JSONObject().put("retcode", 0),
                new JSONObject().put("retcode", 0).put("lyric", JSONObject.NULL),
                new JSONObject().put("retcode", 0).put("lyric", ""),
                new JSONObject().put("code", 0).put("lyric", ""))) {
            fallback = new LyricFake();
            fallback.modernResult = new JSONObject().put("lyric", "");
            fallback.legacyResult = empty;
            result = QQSource.resolveLyrics("LikeHim", fallback);
            that(result.getInt("code") == 200 && result.optBoolean("nolyric", false)
                    && result.getJSONObject("lrc").getString("lyric").isEmpty()
                    && result.getJSONObject("tlyric").getString("lyric").isEmpty(),
                    "confirmed empty legacy response is marked as no lyrics");
        }

        for (JSONObject badLegacy : Arrays.asList(new JSONObject(),
                new JSONObject().put("lyric", "[00:01.00]Missing status"),
                new JSONObject().put("retcode", "invalid"),
                new JSONObject().put("retcode", 9),
                new JSONObject().put("retcode", 0).put("lyric", "[ar:Only metadata]"),
                new JSONObject().put("retcode", 0).put("lyric", 42),
                new JSONObject().put("retcode", -1901).put("code", 9),
                new JSONObject().put("retcode", 0).put("code", 9),
                new JSONObject().put("retcode", 9).put("code", 0))) {
            fallback = new LyricFake();
            fallback.modernResult = new JSONObject().put("lyric", "");
            fallback.legacyResult = badLegacy;
            expectLyricError("LikeHim", fallback, "malformed or failed legacy response");
        }
        fallback = new LyricFake();
        fallback.modernResult = new JSONObject().put("lyric", "");
        fallback.legacyError = new QQSource.ResolveException("network_error", "fixture");
        try {
            QQSource.resolveLyrics("LikeHim", fallback);
            throw new AssertionError("legacy failure after empty modern result must remain an error");
        } catch (QQSource.ResolveException expected) {
            that("network_error".equals(expected.code), "legacy network failure retains its typed error");
        }

        for (String id : Arrays.asList(null, "", "123456789", "qq:2640855415", "qq:", "bad/slash", "null")) {
            LyricFake invalid = new LyricFake();
            expectLyricError(id, invalid, "invalid or numeric catalog ID");
            that(invalid.modernCalls == 0 && invalid.legacyCalls == 0, "invalid ID is rejected before network");
        }

        LyricFake cancelled = new LyricFake();
        cancelled.modernInterrupt = true;
        try {
            QQSource.resolveLyrics("LikeHim", cancelled);
            throw new AssertionError("interrupted modern request must propagate");
        } catch (InterruptedException expected) {
            that(cancelled.modernCalls == 1 && cancelled.legacyCalls == 0,
                    "interrupted modern request never invokes legacy");
        } finally { Thread.interrupted(); }

        cancelled = new LyricFake();
        cancelled.modernCancel = true;
        try {
            QQSource.resolveLyrics("LikeHim", cancelled);
            throw new AssertionError("cancelled modern request must propagate");
        } catch (java.util.concurrent.CancellationException expected) {
            that(cancelled.modernCalls == 1 && cancelled.legacyCalls == 0,
                    "runtime cancellation never invokes legacy");
        }

        cancelled = new LyricFake();
        Thread.currentThread().interrupt();
        try {
            QQSource.resolveLyrics("LikeHim", cancelled);
            throw new AssertionError("active interruption must propagate");
        } catch (InterruptedException expected) {
            that(cancelled.modernCalls == 0 && cancelled.legacyCalls == 0,
                    "active interruption stops before provider requests");
        } finally { Thread.interrupted(); }

        cancelled = new LyricFake();
        cancelled.interruptAfterModern = true;
        try {
            QQSource.resolveLyrics("LikeHim", cancelled);
            throw new AssertionError("interruption during modern request must propagate");
        } catch (InterruptedException expected) {
            that(cancelled.modernCalls == 1 && cancelled.legacyCalls == 0,
                    "active interruption after modern prevents fallback");
        } finally { Thread.interrupted(); }

        cancelled = new LyricFake();
        cancelled.interruptDuringLegacy = true;
        try {
            QQSource.resolveLyrics("LikeHim", cancelled);
            throw new AssertionError("interruption during a failed legacy read must propagate");
        } catch (InterruptedException expected) {
            that(cancelled.legacyCalls == 1, "legacy transport failure preserves pending interruption");
        } finally { Thread.interrupted(); }

        lyricTextDecoding();
    }

    private static void lyricTextDecoding() {
        that("[00:01.00]Hello".equals(QQSource.lyricText("WzAwOjAxLjAwXUhlbGxv")),
                "base64 UTF-8 lyrics are decoded");
        that("[00:01.00]你好".equals(QQSource.lyricText("[00:01.00]你好")),
                "raw Unicode lyrics are preserved");
        that("Love".equals(QQSource.lyricText("Love")) && "test".equals(QQSource.lyricText("test")),
                "ambiguous literal words are not corrupted as base64");
        that("Hello".equals(QQSource.lyricText("SGVsbG8=")) && "A".equals(QQSource.lyricText("QQ==")),
                "canonical UTF-8 base64 also covers single-word and single-character lyrics");
        that("QR==".equals(QQSource.lyricText("QR==")), "base64 with nonzero padding bits is preserved");
        that("&#xD800;".equals(QQSource.lyricText("&#xD800;")) && "&#x110000;".equals(QQSource.lyricText("&#x110000;")),
                "out-of-range code points and lone surrogates are preserved");
        that("&#91;".equals(QQSource.lyricText("&amp;#91;")), "entity decoding is single pass");
        that("/w==".equals(QQSource.lyricText("/w==")), "invalid UTF-8 base64 is not decoded lossily");
        that("[00:01.00]A & B\nC".equals(QQSource.lyricText("&#91;00&#58;01&#46;00&#93;A &amp; B&#10;C")),
                "decimal and named HTML entities decode in lyrics");
        that("[00:01.00]Hi".equals(QQSource.lyricText("&#x5b;00&#x3a;01&#x2e;00&#x5d;Hi")),
                "hexadecimal HTML entities decode in lyrics");
        that("".equals(QQSource.lyricText(null)) && "".equals(QQSource.lyricText(JSONObject.NULL))
                && "".equals(QQSource.lyricText(42)), "non-string lyric values are rejected");
    }

    private static void assertLyrics(JSONObject response, String original, String translation, String label) throws Exception {
        that(response.getInt("code") == 200 && !response.optBoolean("nolyric", false)
                && original.equals(response.getJSONObject("lrc").getString("lyric"))
                && translation.equals(response.getJSONObject("tlyric").getString("lyric")), label);
    }

    private static void expectLyricError(String mid, LyricFake api, String label) throws Exception {
        try {
            QQSource.resolveLyrics(mid, api);
            throw new AssertionError(label + " must be reported as an error");
        } catch (QQSource.ResolveException expected) {
            that(expected.code != null && !expected.code.isEmpty(), label + " has a typed error");
        }
    }

    private static final class LyricFake implements QQSource.LyricLookup {
        JSONObject modernResult = new JSONObject(), legacyResult = new JSONObject();
        QQSource.ResolveException modernError, legacyError;
        boolean modernInterrupt, modernCancel, interruptAfterModern, interruptDuringLegacy;
        int modernCalls, legacyCalls;
        String lastMid;

        public JSONObject modern(String mid) throws Exception {
            modernCalls++;
            lastMid = mid;
            if (modernInterrupt) throw new InterruptedException("fixture");
            if (modernCancel) throw new java.util.concurrent.CancellationException("fixture");
            if (interruptAfterModern) Thread.currentThread().interrupt();
            if (modernError != null) throw modernError;
            return modernResult;
        }

        public JSONObject legacy(String mid) throws Exception {
            legacyCalls++;
            lastMid = mid;
            if (interruptDuringLegacy) {
                Thread.currentThread().interrupt();
                throw new QQSource.ResolveException("network_error", "fixture");
            }
            if (legacyError != null) throw legacyError;
            return legacyResult;
        }
    }
    private static final class Fake implements QQSource.Lookup {
        final Map<String, JSONArray> results = new LinkedHashMap<>();
        final Map<String, JSONObject> details = new LinkedHashMap<>();
        final List<String> queries = new ArrayList<>(), detailCalls = new ArrayList<>(), noPurl = new ArrayList<>();
        final List<JSONObject> requests = new ArrayList<>();
        JSONArray sips = new JSONArray().put("https://a.qqmusic.qq.com/");
        int retcode, rowCode;
        boolean reverse, wrongRowMid, interruptSearch;
        String purlOverride;

        public JSONArray search(String query) {
            queries.add(query);
            if (interruptSearch) Thread.currentThread().interrupt();
            return results.getOrDefault(query, new JSONArray());
        }
        public JSONObject detail(String mid) { detailCalls.add(mid); return details.get(mid); }
        public JSONObject vkey(JSONObject params) throws Exception {
            requests.add(new JSONObject(params.toString()));
            String mid = params.getJSONArray("songmid").getString(0);
            JSONArray rows = new JSONArray(), files = params.getJSONArray("filename");
            for (int n = 0; n < files.length(); n++) {
                int i = reverse ? files.length() - 1 - n : n;
                String file = files.getString(i);
                rows.put(new JSONObject().put("filename", file).put("songmid", wrongRowMid ? "other" : mid)
                        .put("result", rowCode).put("purl", purlOverride != null ? purlOverride
                                : noPurl.contains(mid) ? "" : file + "?vkey=fixture"));
            }
            return new JSONObject().put("retcode", retcode).put("sip", sips).put("midurlinfo", rows);
        }
    }
}
