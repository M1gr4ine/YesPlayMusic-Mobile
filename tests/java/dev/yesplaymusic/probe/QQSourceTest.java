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

/** Exercises forward matching, QQ credentials and playback fallback with in-memory provider responses. */
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
        List<ArtistAliases.Member> fmlMembers = Arrays.asList(
                new ArtistAliases.Member("Kanye West", "37520", Collections.singletonList("ye")),
                new ArtistAliases.Member("The Weeknd", "", Collections.emptyList()));
        Fake api = new Fake();
        api.results.put("FML Kanye West The Weeknd", new JSONArray().put(
                song("0005toRJ3QqtAx", "003mHViD1MfUDY", "FML (Explicit)", "Ye (侃爷)", "The Life Of Pablo (Explicit)", 236))
                .put(song("other1", "otherfile1", "Other One", "Other", "Other", 236))
                .put(song("other2", "otherfile2", "Other Two", "Other", "Other", 236))
                .put(song("other3", "otherfile3", "Other Three", "Other", "Other", 236))
                .put(song("other4", "otherfile4", "Other Four", "Other", "Other", 236))
                .put(song("flashing", "flashingfile", "Flashing Lights (Explicit)", "Ye", "Other", 237)));
        api.structuredMembers = fmlMembers;
        api.results.put("FML", new JSONArray().put(
                song("bareConflict", "bareFile", "FML", "Random Artist", "Other", 236)));
        List<JSONObject> resolved = QQSource.lookupAttempts(target(), api, "0");
        that("0005toRJ3QqtAx".equals(resolved.get(0).getString("qqMid")), "FML resolves to the intended QQ recording");
        that(api.memberTracks.size() == 1 && api.memberTracks.get(0).artistIds.contains("37520"),
                "official NetEase artist ID reaches structured alias lookup");
        that(api.requests.size() == 1, "first playable candidate returns without extra vkey requests");

        Fake leadQuery = new Fake();
        leadQuery.results.put("FML Kanye West", api.results.get("FML Kanye West The Weeknd"));
        leadQuery.structuredMembers = fmlMembers;
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

        Fake ambiguousNoAlias = new Fake();
        ambiguousNoAlias.results.put("FML Kanye West The Weeknd", new JSONArray()
                .put(song("one", "fileOne", "FML", "Ye (侃爷)", "The Life Of Pablo", 236))
                .put(song("two", "fileTwo", "FML", "Someone Else", "The Life Of Pablo", 236)));
        expectError(target(), ambiguousNoAlias, "0", "match_not_found", null);
        that(ambiguousNoAlias.requests.isEmpty(),
                "without alias evidence, two hard-gate passers block Rule B and never request a vkey");

        Fake evidencedRuleA = new Fake();
        evidencedRuleA.results.put("FML Kanye West The Weeknd", new JSONArray()
                .put(song("one", "fileOne", "FML", "Ye (侃爷)", "The Life Of Pablo", 236))
                .put(song("two", "fileTwo", "FML", "Someone Else", "The Life Of Pablo", 236)));
        evidencedRuleA.structuredMembers = fmlMembers;
        that("one".equals(QQSource.lookupAttempts(target(), evidencedRuleA, "0").get(0).getString("qqMid"))
                && evidencedRuleA.requests.size() == 1,
                "with official lead alias, Rule A selects the evidenced artist and ignores disjoint stranger");

        famousAndStructuredCatalogCases();
    }

    private static void famousAndStructuredCatalogCases() throws Exception {
        Track famousNetease = Track.fromJson(new JSONObject()
                .put("id", "409736434").put("title", "Famous")
                .put("artist", "Kanye West / Rihanna").put("album", "The Life Of Pablo")
                .put("duration", 196040)
                .put("artistRefs", new JSONArray()
                        .put(new JSONObject().put("id", "37520").put("name", "Kanye West"))
                        .put(new JSONObject().put("id", "72724").put("name", "Rihanna"))));

        JSONArray famousPrimaryHits = new JSONArray()
                .put(song("003Z0JlC3c9Jwx", "fileCollab", "Famous", new String[]{"Ye (侃爷)", "Rihanna"}, "Famous", 195))
                .put(song("0023yUmC3Ss6Xm", "file30s", "Famous", new String[]{"Kanye West", "Rihanna"}, "Famous", 30))
                .put(song("002XelTe31rBEE", "fileAlbum", "Famous (Explicit)", new String[]{"Ye (侃爷)"}, "The Life Of Pablo (Explicit)", 196));
        List<ArtistAliases.Member> famousMembers = Arrays.asList(
                new ArtistAliases.Member("Kanye West", "37520", Arrays.asList("ye", "坎耶·维斯特")),
                new ArtistAliases.Member("Rihanna", "72724", Collections.emptyList()));

        // Under TrackMatcher Policy.PLAYBACK, same-album Rule A (40ms gap) ranks #1 ahead of different-album single (1040ms gap).
        Fake famous = new Fake();
        famous.results.put("Famous Kanye West Rihanna", famousPrimaryHits);
        famous.structuredMembers = famousMembers;
        List<JSONObject> resolvedFamous = QQSource.lookupAttempts(famousNetease, famous, "0");
        that("002XelTe31rBEE".equals(resolvedFamous.get(0).getString("qqMid")),
                "Rule A ranks same-album recording first ahead of different-album single");
        that(famous.requests.size() == 1 && famous.memberTracks.size() == 1 && famous.aliasTracks.isEmpty(),
                "structured member lookup avoids redundant legacy alias request and stops after first playable candidate");

        // If #1 has no_purl, #2 backs it up cleanly while the 30-second snippet never requests a vkey.
        Fake famousFallback = new Fake();
        famousFallback.results.put("Famous Kanye West Rihanna", famousPrimaryHits);
        famousFallback.structuredMembers = famousMembers;
        famousFallback.noPurl.add("002XelTe31rBEE");
        List<JSONObject> singleFallback = QQSource.lookupAttempts(famousNetease, famousFallback, "0");
        that("003Z0JlC3c9Jwx".equals(singleFallback.get(0).getString("qqMid")),
                "falls back to #2 Rule A candidate when #1 has no purl");
        that(famousFallback.requests.size() == 2
                && !"0023yUmC3Ss6Xm".equals(famousFallback.requests.get(0).getJSONArray("songmid").getString(0))
                && !"0023yUmC3Ss6Xm".equals(famousFallback.requests.get(1).getJSONArray("songmid").getString(0)),
                "30-second snippet never triggers a vkey request");

        // Short studio album with official lead alias resolves cleanly via Rule A.
        Track bound2 = Track.fromJson(new JSONObject()
                .put("id", "26599525").put("title", "Bound 2")
                .put("artist", "Kanye West / Charlie Wilson").put("album", "Yeezus")
                .put("duration", 229253));
        Fake bound2Api = new Fake();
        bound2Api.results.put("Bound 2 Kanye West Charlie Wilson", new JSONArray()
                .put(song("00Bound2Mid", "fileBound2", "Bound 2 (Album Version|Explicit)",
                        new String[]{"Ye (侃爷)"}, "Yeezus (Explicit)", 229)));
        bound2Api.structuredMembers = Arrays.asList(
                new ArtistAliases.Member("Kanye West", "37520", Collections.singletonList("ye")),
                new ArtistAliases.Member("Charlie Wilson", "99", Collections.emptyList()));
        that("00Bound2Mid".equals(QQSource.lookupAttempts(bound2, bound2Api, "0").get(0).getString("qqMid")),
                "lead alias with benign edition markers resolves via Rule A");

        // Symbol-only lead collaboration: dropped-lead (guest-only A4), Acapella (VERSION), and custom edition (TITLE)
        // are rejected, while non-lead alias query finds exact 3-member match (and full coverage outranks 5-member superset).
        Track carnival = Track.fromJson(new JSONObject()
                .put("id", "2125735002").put("title", "CARNIVAL")
                .put("artist", "¥$ / Kanye West / Ty Dolla $ign").put("album", "VULTURES 1")
                .put("duration", 264000));
        Fake carnivalApi = new Fake();
        carnivalApi.results.put("CARNIVAL ¥$ Kanye West Ty Dolla $ign", new JSONArray()
                .put(song("badDrop", "fDrop", "CARNIVAL", new String[]{"Ye (侃爷)", "Ty Dolla $ign"}, "VULTURES 1", 264))
                .put(song("badAca", "fAca", "CARNIVAL (Acapella)", new String[]{"¥$", "Ye (侃爷)", "Ty Dolla $ign"}, "VULTURES 1", 264))
                .put(song("badHool", "fHool", "CARNIVAL (HOOLIGANS VERSION)", new String[]{"¥$", "Ye (侃爷)", "Ty Dolla $ign"}, "VULTURES 1", 264)));
        carnivalApi.results.put("CARNIVAL ¥$ ye Ty Dolla $ign", new JSONArray()
                .put(song("superset5", "f5", "CARNIVAL",
                        new String[]{"¥$", "Ye (侃爷)", "Ty Dolla $ign", "Rich The Kid", "Playboi Carti"}, "VULTURES 1", 264))
                .put(song("000cThFa1hAdAV", "fileCarnival", "CARNIVAL",
                        new String[]{"¥$", "Ye (侃爷)", "Ty Dolla $ign"}, "VULTURES 1", 264)));
        carnivalApi.structuredMembers = Arrays.asList(
                new ArtistAliases.Member("¥$", "99001", Collections.emptyList()),
                new ArtistAliases.Member("Kanye West", "37520", Collections.singletonList("ye")),
                new ArtistAliases.Member("Ty Dolla $ign", "88001", Collections.emptyList()));
        List<JSONObject> carnivalResolved = QQSource.lookupAttempts(carnival, carnivalApi, "0");
        that("000cThFa1hAdAV".equals(carnivalResolved.get(0).getString("qqMid")),
                "full-coverage 3-artist recording outranks 5-artist superset on same album/duration");
        that(carnivalApi.requests.size() == 1, "rejected dropped-lead and bad version variants never request a vkey");

        // Guest-only solo candidate is rejected by A4 even on exact same album (never falls back to Rule B).
        Fake guestOnly = new Fake();
        guestOnly.results.put("Famous Kanye West Rihanna", new JSONArray()
                .put(song("guestMid", "fileGuest", "Famous", new String[]{"Rihanna"}, "The Life Of Pablo", 196)));
        guestOnly.structuredMembers = famousMembers;
        expectError(famousNetease, guestOnly, "0", "match_not_found", null);
        that(guestOnly.requests.isEmpty(), "A4 guest-only solo hit never triggers a vkey request");

        // Legacy unverified cached MID with disjoint artist must NOT infer Rule B uniqueness from a single cache entry.
        JSONObject legacyDisjoint = single().toJson(false).put("qqMid", "oldRenamed");
        Fake legacyCacheApi = new Fake();
        legacyCacheApi.details.put("oldRenamed", song("oldRenamed", "fileOld", "Song", "Renamed Artist", "Album", 200));
        legacyCacheApi.results.put("Song Artist", new JSONArray()
                .put(song("verifiedNew", "fileNew", "Song", "Artist", "Album", 200)));
        that("verifiedNew".equals(QQSource.lookupAttempts(Track.fromJson(legacyDisjoint), legacyCacheApi, "0")
                .get(0).getString("qqMid")),
                "legacy unverified cached MID never infers global Rule B uniqueness from a single detail");
        that(legacyCacheApi.requests.size() == 1
                && "verifiedNew".equals(legacyCacheApi.requests.get(0).getJSONArray("songmid").getString(0)),
                "disjoint legacy cached MID triggers full search instead of requesting vkey directly");

        // P1-1: parseMatchSearchList distinguishes valid zero results from missing/malformed payloads; null search aborts before Rule B.
        that(QQSource.parseMatchSearchList(new JSONObject()
                .put("code", 0)
                .put("body", new JSONObject().put("song", new JSONObject().put("list", new JSONArray())))).length() == 0,
                "valid empty QQ search list parses as zero hits");
        for (JSONObject badPayload : Arrays.asList(
                null,
                new JSONObject().put("code", 2001),
                new JSONObject().put("body", new JSONObject()),
                new JSONObject().put("body", new JSONObject().put("song", new JSONObject())))) {
            boolean threw = false;
            try { QQSource.parseMatchSearchList(badPayload); }
            catch (QQSource.ResolveException expected) { threw = "stream_unavailable".equals(expected.code); }
            that(threw, "malformed or failed QQ search payload fails closed instead of returning empty list");
        }
        Fake failedLadder = new Fake() {
            @Override public JSONArray search(String query) {
                queries.add(query);
                return "Song Artist".equals(query) ? results.get(query) : null;
            }
        };
        failedLadder.results.put("Song Artist", new JSONArray()
                .put(song("bOnly", "fileB", "Song", "Renamed", "Album", 200)));
        expectError(single(), failedLadder, "0", "stream_unavailable", "null_search_result");
        that(failedLadder.requests.isEmpty(), "failed later ladder query aborts instead of falling back to Rule B");

        // P1-3: Mismatched fetched member aliases never leak into flat target aliases.
        Track northTrack = Track.fromJson(new JSONObject().put("id", "901").put("title", "Horizon")
                .put("artist", "North").put("album", "Album One").put("duration", 200000));
        Fake leakedAliasApi = new Fake();
        leakedAliasApi.structuredMembers = Collections.singletonList(
                new ArtistAliases.Member("Unrelated", "999", Collections.singletonList("Other")));
        leakedAliasApi.results.put("Horizon North", new JSONArray()
                .put(song("mWrong", "fWrong", "Horizon", "Other", "Album Two", 200)));
        expectError(northTrack, leakedAliasApi, "0", "match_not_found", null);
        that(leakedAliasApi.requests.isEmpty(),
                "unattributed alias from mismatched fetched member never leaks onto target lead");

        // P1-5: Legacy unverified cached MID relying on A3 one-sided live-year fallback defers to full search pool.
        Track live2007Track = Track.fromJson(new JSONObject().put("id", "902").put("title", "Ballad (2007 Live)")
                .put("artist", "Lead Artist").put("album", "World Stage (Live)").put("duration", 277000)
                .put("qqMid", "m3Unmarked"));
        Fake liveLegacyApi = new Fake();
        liveLegacyApi.details.put("m3Unmarked",
                song("m3Unmarked", "f3", "Ballad (Live)", "Lead Artist", "World Stage (Live)", 277));
        liveLegacyApi.results.put("Ballad (2007 Live) Lead Artist", new JSONArray()
                .put(song("m3Unmarked", "f3", "Ballad (Live)", "Lead Artist", "World Stage (Live)", 277))
                .put(song("m4Exact", "f4", "Ballad (2007 Live)", "Lead Artist", "Other Live", 277)));
        List<JSONObject> liveResolved = QQSource.lookupAttempts(live2007Track, liveLegacyApi, "0");
        that("m4Exact".equals(liveResolved.get(0).getString("qqMid")) && !liveLegacyApi.queries.isEmpty(),
                "legacy unverified hint relying on A3 live-year fallback searches full pool and picks explicit 2007 Live");

        // Pass 4b P1-4: End-to-end QQSource cross-round merge of solo First and collab First / Guest(North) rejects in both orders.
        JSONObject qqSoloFirst = song("m100", "f100", "Horizon", new String[]{"First"}, "Album Two", 200);
        JSONObject qqCollabGuestNorth = new JSONObject().put("mid", "m100").put("title", "Horizon").put("interval", 200)
                .put("file", new JSONObject().put("media_mid", "f100"))
                .put("album", new JSONObject().put("name", "Album Two"))
                .put("singer", new JSONArray()
                        .put(new JSONObject().put("mid", "q1").put("name", "First"))
                        .put(new JSONObject().put("mid", "q2").put("name", "Guest")
                                .put("alias", new JSONArray().put("North"))));
        Fake mergeForward = new Fake();
        mergeForward.results.put("Horizon North", new JSONArray().put(qqSoloFirst));
        mergeForward.results.put("Horizon", new JSONArray().put(qqCollabGuestNorth));
        expectError(northTrack, mergeForward, "0", "match_not_found", null);
        Fake mergeReverse = new Fake();
        mergeReverse.results.put("Horizon North", new JSONArray().put(qqCollabGuestNorth));
        mergeReverse.results.put("Horizon", new JSONArray().put(qqSoloFirst));
        expectError(northTrack, mergeReverse, "0", "match_not_found", null);
        that(mergeForward.requests.isEmpty() && mergeReverse.requests.isEmpty(),
                "same MID observed as solo and collab never leaks Guest alias onto First in either order");

        // Pass 4b P2-2: UI search parser keeps lenient empty-list contract while match parser fails closed.
        JSONObject missingSongList = new JSONObject().put("code", 0).put("body", new JSONObject());
        that(QQSource.parseUiSearchList(missingSongList).length() == 0,
                "UI search parser preserves lenient empty-list contract when body.song.list is absent");
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
    private static class Fake implements QQSource.Lookup {
        final Map<String, JSONArray> results = new LinkedHashMap<>();
        final Map<String, JSONObject> details = new LinkedHashMap<>();
        final List<String> queries = new ArrayList<>(), detailCalls = new ArrayList<>(), noPurl = new ArrayList<>();
        final List<Track> aliasTracks = new ArrayList<>();
        final List<Track> memberTracks = new ArrayList<>();
        final List<JSONObject> requests = new ArrayList<>();
        List<String> artistAliases = Collections.emptyList();
        List<ArtistAliases.Member> structuredMembers = null;
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
        @Override public List<ArtistAliases.Member> artistMembers(Track track) {
            if (structuredMembers == null) return null;
            memberTracks.add(track);
            return structuredMembers;
        }
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
