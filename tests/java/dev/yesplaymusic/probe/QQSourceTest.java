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
        that(QQSource.shouldRetrySearch(new QQSource.ResolveException("stream_unavailable", "2001")),
                "transient search refusal can be retried");
        transientSearch();
        providerCodes();
        filterResults();
        matching();
        cachedAndNative();
        vkeyAndHosts();
        denialsAndCancellation();
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
        return new JSONObject().put("mid", mid).put("file", new JSONObject().put("media_mid", file))
                .put("title", title).put("singer", new JSONArray().put(new JSONObject().put("name", artist)))
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
        Fake api = new Fake();
        api.results.put("FML Kanye West The Weeknd", new JSONArray().put(
                song("0005toRJ3QqtAx", "003mHViD1MfUDY", "FML (Explicit)", "Ye (侃爷)", "The Life Of Pablo (Explicit)", 236))
                .put(song("other1", "otherfile1", "Other One", "Other", "Other", 236))
                .put(song("other2", "otherfile2", "Other Two", "Other", "Other", 236))
                .put(song("other3", "otherfile3", "Other Three", "Other", "Other", 236))
                .put(song("other4", "otherfile4", "Other Four", "Other", "Other", 236))
                .put(song("flashing", "flashingfile", "Flashing Lights (Explicit)", "Ye", "Other", 237)));
        api.artistAliases = Arrays.asList("ye");
        List<JSONObject> resolved = QQSource.lookupAttempts(target(), api, "0");
        that("0005toRJ3QqtAx".equals(resolved.get(0).getString("qqMid")), "FML resolves to the intended QQ recording");
        that(api.aliasTracks.size() == 1 && api.aliasTracks.get(0).artistIds.contains("37520"),
                "official NetEase artist ID reaches alias lookup");

        Fake leadQuery = new Fake();
        leadQuery.results.put("FML Kanye West", api.results.get("FML Kanye West The Weeknd"));
        leadQuery.artistAliases = Collections.singletonList("ye");
        that("0005toRJ3QqtAx".equals(QQSource.lookupAttempts(target(), leadQuery, "0").get(0).getString("qqMid")),
                "search with only the lead artist reaches the observed QQ result for a collaborative track");

        Fake solo = new Fake();
        solo.results.put("FML Kanye West", new JSONArray().put(
                song("0005toRJ3QqtAx", "003mHViD1MfUDY", "FML (Explicit)", "Ye", "The Life Of Pablo (Explicit)", 236)));
        solo.artistAliases = Arrays.asList("ye");
        Track soloTrack = Track.fromJson(new JSONObject().put("id", "484755744").put("title", "FML")
                .put("artist", "Kanye West").put("duration", 236170).put("album", "The Life Of Pablo")
                .put("artistRefs", new JSONArray().put(new JSONObject().put("id", "37520").put("name", "Kanye West"))));
        that("0005toRJ3QqtAx".equals(QQSource.lookupAttempts(soloTrack, solo, "0").get(0).getString("qqMid"))
                && solo.aliasTracks.size() == 1 && solo.aliasTracks.get(0).artistIds.contains("37520"),
                "official ye alias resolves the solo catalog entry");

        Fake unrelated = new Fake();
        unrelated.results.put("Song Artist", new JSONArray().put(song("bad", "badfile", "Other", "Artist", "Album", 200)));
        expectError(single(), unrelated, "0", "match_not_found", null);
        that(unrelated.requests.isEmpty(), "same duration with unrelated title never requests a vkey");

        Fake ambiguous = new Fake();
        ambiguous.results.put("FML Kanye West The Weeknd", new JSONArray()
                .put(song("one", "fileOne", "FML", "Ye (侃爷)", "The Life Of Pablo", 236))
                .put(song("two", "fileTwo", "FML", "Someone Else", "The Life Of Pablo", 236)));
        ambiguous.artistAliases = Arrays.asList("ye");
        expectError(target(), ambiguous, "0", "match_not_found", null);
        that(ambiguous.requests.isEmpty(), "ambiguous same-title recordings never request a vkey");
    }

    private static void cachedAndNative() throws Exception {
        JSONObject base = single().toJson(false);
        base.put("qqMid", "wrong");
        Fake stale = new Fake();
        stale.details.put("wrong", song("wrong", "wrongFile", "Different", "Someone", "Album", 200));
        stale.results.put("Song Artist", new JSONArray().put(song("right", "rightFile", "Song", "Artist", "Album", 200)));
        that("right".equals(QQSource.lookupAttempts(Track.fromJson(base), stale, "0").get(0).getString("qqMid")),
                "legacy MID with mismatching detail is searched again");
        that(stale.requests.size() == 1 && "right".equals(stale.requests.get(0).getJSONArray("songmid").getString(0)),
                "wrong cached MID is never sent for vkey");

        Fake unavailable = new Fake();
        unavailable.details.put("old", song("old", "oldFile", "Song", "Artist", "Album", 200));
        unavailable.noPurl.add("old");
        unavailable.results.put("Song Artist", new JSONArray().put(song("new", "newFile", "Song", "Artist", "Album", 200)));
        base.put("qqMid", "old");
        that("new".equals(QQSource.lookupAttempts(Track.fromJson(base), unavailable, "0").get(0).getString("qqMid")),
                "legacy MID reverified by detail still searches after a no-purl response");
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

    private static final class Fake implements QQSource.Lookup {
        final Map<String, JSONArray> results = new LinkedHashMap<>();
        final Map<String, JSONObject> details = new LinkedHashMap<>();
        final List<String> queries = new ArrayList<>(), detailCalls = new ArrayList<>(), noPurl = new ArrayList<>();
        final List<Track> aliasTracks = new ArrayList<>();
        final List<JSONObject> requests = new ArrayList<>();
        List<String> artistAliases = Collections.emptyList();
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
        public List<String> aliases(Track track) { aliasTracks.add(track); return artistAliases; }
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
