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

/** Verifies reverse catalog matching, enrichment budgets and selected NetEase navigation IDs. */
final class MusicApiTest {
    private MusicApiTest() {}

    static void run() throws Exception {
        testExtractPlayableItemMatchesExactId();
        testExtractPlayableItemRejectsCrossTalk();
        testExtractPlayableItemClassifiesCodesAndTrials();
        testApplySetCookiesRotationAndDeletion();
        testExtractArtistDetailAliases();
        testReverseCatalogMatch();
        testBatchArtistEnrichment();
        testExactSongMembers();
        testPartialQueueArtistRecovery();
        testReverseArtistNames();
        testReversePolicyAndMissingId();
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

    private static JSONObject recording(int songId, String album, int artistId, String artist, String... aliases)
            throws Exception {
        JSONObject member = new JSONObject().put("id", artistId).put("name", artist)
                .put("alias", new JSONArray(Arrays.asList(aliases)));
        return new JSONObject().put("id", songId).put("name", "Signal").put("dt", 100000)
                .put("al", new JSONObject().put("id", 1000 + songId).put("name", album))
                .put("ar", new JSONArray().put(member));
    }

    private static void testBatchArtistEnrichment() throws Exception {
        JSONObject target = new JSONObject().put("title", "Signal").put("artist", "North")
                .put("album", "Record").put("duration", 100000);
        JSONObject first = recording(901, "Record", 11, "One");
        JSONObject second = recording(902, "Record", 12, "Two");
        for (boolean reverse : Arrays.asList(false, true)) {
            JSONArray batch = new JSONArray().put(reverse ? second : first);
            for (int i = 0; i < 3; i++) batch.put(recording(910 + i, "Elsewhere", 50 + i, "Other " + i));
            batch.put(reverse ? first : second);
            List<String> calls = new ArrayList<>();
            List<MatchTrace> traces = new ArrayList<>();
            JSONObject result = MusicApi.matchTrack(target, q -> batch, id -> {
                calls.add(id);
                return new Member("11".equals(id) ? "One" : "Two", id, Collections.singletonList("North"));
            }, "netease_match_api", MatchTrace.Policy.WRITE, traces::add);
            that(!result.getBoolean("matched") && "ambiguous".equals(traces.get(0).finalOutcome())
                            && calls.equals(reverse ? Arrays.asList("12", "11") : Arrays.asList("11", "12")),
                    "final enrichment verifies both same-album identities before lower-tier releases");
        }

        JSONObject aliasSource = recording(951, "Elsewhere", 12, "Two", "North");
        for (boolean reverse : Arrays.asList(false, true)) {
            JSONObject preferred = recording(952, "Record", 12, "Two");
            JSONArray batch = new JSONArray().put(reverse ? preferred : aliasSource)
                    .put(reverse ? aliasSource : preferred);
            JSONObject result = MusicApi.matchTrack(target, q -> batch,
                    id -> { throw new AssertionError("official alias already available in batch"); });
            that("952".equals(result.getString("id")) && "1952".equals(result.getString("albumId")),
                    "member aliases identify the preferred recording and navigation album in either search order");
        }
        for (JSONObject unrelated : Arrays.asList(recording(953, "Record", 12, "Different"),
                recording(954, "Record", 13, "Two"))) {
            JSONObject result = MusicApi.matchTrack(target, q -> new JSONArray().put(aliasSource).put(unrelated),
                    id -> null);
            that("951".equals(result.getString("id")), "sharing requires both provider ID and normalized name");
        }
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

    private static void testReversePolicyAndMissingId() throws Exception {
        // Missing id in JS args must still match cleanly and return artists + albumId for navigation.
        JSONObject noIdArgs = new JSONObject()
                .put("title", "Anthem (Explicit)")
                .put("artist", "AliasAlpha (别名)")
                .put("album", "Studio Album (Explicit)")
                .put("duration", 196000);
        JSONObject singleEdition = new JSONObject().put("id", 501).put("name", "Anthem").put("dt", 196040)
                .put("al", new JSONObject().put("id", 9001).put("name", "Single Edition"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 37520).put("name", "Lead Alpha"))
                        .put(new JSONObject().put("id", 72724).put("name", "Guest Beta")));
        List<String> fetched = new ArrayList<>();
        JSONObject writeCrossAlbum = MusicApi.matchTrack(noIdArgs,
                query -> "Anthem AliasAlpha".equals(query) ? new JSONArray().put(singleEdition) : new JSONArray(),
                id -> {
                    fetched.add(id);
                    return new Member("Lead Alpha", id, Collections.singletonList("AliasAlpha"));
                }, "netease_navigation", MatchTrace.Policy.WRITE, MatchTrace.NOOP);
        that(!writeCrossAlbum.getBoolean("matched") && fetched.isEmpty(),
                "Policy.WRITE rejects cross-album partial lead match without wasting artist detail lookups");

        JSONObject playbackCrossAlbum = MusicApi.matchTrack(noIdArgs,
                query -> "Anthem AliasAlpha".equals(query) ? new JSONArray().put(singleEdition) : new JSONArray(),
                id -> {
                    fetched.add(id);
                    return new Member("Lead Alpha", id, Collections.singletonList("AliasAlpha"));
                }, "netease_playback", MatchTrace.Policy.PLAYBACK, MatchTrace.NOOP);
        that(playbackCrossAlbum.getBoolean("matched")
                && "501".equals(playbackCrossAlbum.getString("id"))
                && "9001".equals(playbackCrossAlbum.getString("albumId"))
                && playbackCrossAlbum.getJSONArray("artists").length() == 2
                && fetched.equals(Collections.singletonList("37520")),
                "Policy.PLAYBACK enriches lead artist ID once and accepts cross-album match even when args omit id");

        // Artist detail lookups per request are strictly bounded to MAX_MATCH_ARTIST_LOOKUPS (4).
        JSONArray manyCandidates = new JSONArray();
        for (int i = 1; i <= 7; i++) {
            manyCandidates.put(new JSONObject().put("id", 700 + i).put("name", "Anthem").put("dt", 196000)
                    .put("al", new JSONObject().put("id", 800 + i).put("name", "Studio Album"))
                    .put("ar", new JSONArray().put(new JSONObject().put("id", 900 + i).put("name", "Other " + i))));
        }
        List<String> bounded = new ArrayList<>();
        MusicApi.matchTrack(noIdArgs, query -> manyCandidates, id -> {
            bounded.add(id);
            return null;
        });
        that(bounded.size() == 4, "artist detail enrichment is strictly capped at 4 lookups per match request");

        // P1-1: parseMatchSearchSongs distinguishes valid zero results from failed/malformed NetEase payloads;
        // null second-stage search fails closed instead of accepting Rule B from stage 1.
        that(MusicApi.parseMatchSearchSongs(new JSONObject().put("code", 200)
                .put("result", new JSONObject().put("songCount", 0))).length() == 0,
                "valid NetEase songCount=0 payload without songs array parses as zero hits");
        for (JSONObject badPayload : Arrays.asList(
                null,
                new JSONObject().put("code", 500).put("result", new JSONObject().put("songs", new JSONArray())),
                new JSONObject().put("code", 200),
                new JSONObject().put("code", 200).put("result", new JSONObject().put("songCount", 5)))) {
            boolean threw = false;
            try { MusicApi.parseMatchSearchSongs(badPayload); }
            catch (java.io.IOException expected) { threw = true; }
            that(threw, "failed or malformed NetEase search payload throws instead of returning empty");
        }
        JSONObject ruleBReq = new JSONObject().put("title", "Horizon").put("artist", "Old Artist")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject ruleBSong = new JSONObject().put("id", 100).put("name", "Horizon").put("dt", 200000)
                .put("al", new JSONObject().put("id", 10).put("name", "Studio Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 9).put("name", "New Artist")));
        boolean threwNullSearch = false;
        try {
            MusicApi.matchTrack(ruleBReq,
                    query -> "Horizon Old Artist".equals(query) ? new JSONArray().put(ruleBSong) : null,
                    id -> null);
        } catch (java.io.IOException expected) {
            threwNullSearch = true;
        }
        that(threwNullSearch, "null search result in later ladder stage aborts instead of allowing WRITE Rule B");

        // P1-4: Cross-round same-ID merge preserves richer aliases and albumId regardless of query order.
        JSONObject sparseSong = new JSONObject().put("id", 300).put("name", "Anthem").put("dt", 200000)
                .put("al", new JSONObject().put("name", "Other Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 30).put("name", "Lead Alpha")));
        JSONObject richSong = new JSONObject().put("id", 300).put("name", "Anthem").put("dt", 200000)
                .put("al", new JSONObject().put("id", 3000).put("name", "Other Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 30).put("name", "Lead Alpha")
                        .put("alias", new JSONArray().put("AliasAlpha"))));
        JSONObject mergeReq = new JSONObject().put("title", "Anthem").put("artist", "AliasAlpha")
                .put("album", "Different Album").put("duration", 200000);
        JSONObject mergedMatch = MusicApi.matchTrack(mergeReq,
                query -> "Anthem AliasAlpha".equals(query)
                        ? new JSONArray().put(sparseSong) : new JSONArray().put(richSong),
                id -> null);
        that(mergedMatch.getBoolean("matched") && "300".equals(mergedMatch.getString("id"))
                && "3000".equals(mergedMatch.getString("albumId")),
                "cross-round same-ID merge combines later inline aliases and albumId consistently");

        // P1-7: WRITE pre-short-circuit enriches same-tier rival artist IDs so alias-backed ties stay ambiguous.
        JSONObject tieReq = new JSONObject().put("title", "Track").put("artist", "North")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject directNorth = new JSONObject().put("id", 61).put("name", "Track").put("dt", 200000)
                .put("al", new JSONObject().put("id", 601).put("name", "Studio Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "North")));
        JSONObject aliasRival = new JSONObject().put("id", 62).put("name", "Track").put("dt", 200000)
                .put("al", new JSONObject().put("id", 602).put("name", "Studio Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 2).put("name", "AliasHolder")));
        List<String> rivalLookups = new ArrayList<>();
        JSONObject tieRes = MusicApi.matchTrack(tieReq,
                query -> new JSONArray().put(directNorth).put(aliasRival),
                id -> {
                    rivalLookups.add(id);
                    return "2".equals(id) ? new Member("AliasHolder", id, Collections.singletonList("North")) : null;
                });
        that(!tieRes.getBoolean("matched") && rivalLookups.equals(Collections.singletonList("2")),
                "WRITE short-circuit verifies rival artist detail in batch and rejects distinct-ID alias tie");

        // P2-1: Partial-coverage multi-member target enriches unmatched non-lead member to complete subset.
        JSONObject subsetReq = new JSONObject().put("title", "Duet").put("artist", "North / 东")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject supersetSong = new JSONObject().put("id", 200).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 201).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 10).put("name", "North"))
                        .put(new JSONObject().put("id", 20).put("name", "East"))
                        .put(new JSONObject().put("id", 30).put("name", "Guest")));
        List<String> subsetLookups = new ArrayList<>();
        JSONObject subsetRes = MusicApi.matchTrack(subsetReq,
                query -> new JSONArray().put(supersetSong),
                id -> {
                    subsetLookups.add(id);
                    return "20".equals(id) ? new Member("East", id, Collections.singletonList("东")) : null;
                });
        that(subsetRes.getBoolean("matched") && "200".equals(subsetRes.getString("id"))
                && subsetLookups.contains("20"),
                "partial-coverage multi-member target enriches unmatched member East -> 东 to complete subset");
        JSONObject reverseSubsetReq = new JSONObject().put("title", "Duet").put("artist", "North / East / Guest")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject smallerCandSong = new JSONObject().put("id", 202).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 203).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 10).put("name", "North"))
                        .put(new JSONObject().put("id", 21).put("name", "东")));
        List<String> revSubsetLookups = new ArrayList<>();
        JSONObject revSubsetRes = MusicApi.matchTrack(reverseSubsetReq,
                query -> new JSONArray().put(smallerCandSong),
                id -> {
                    revSubsetLookups.add(id);
                    return "21".equals(id) ? new Member("东", id, Collections.singletonList("East")) : null;
                });
        that(revSubsetRes.getBoolean("matched") && "202".equals(revSubsetRes.getString("id"))
                && revSubsetLookups.contains("21"),
                "partial-coverage larger target also enriches smaller candidate non-lead member 东 -> East");

        // Pass 4b P1-1: Collaboration WRITE rival with contended slots (North / East vs East / South)
        // resolves 1-to-1 member bijection whether South's alias is inline or in detail.
        JSONObject collabTieReq = new JSONObject().put("title", "Duet").put("artist", "North / East")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject directCollab = new JSONObject().put("id", 71).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 701).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 1).put("name", "North"))
                        .put(new JSONObject().put("id", 2).put("name", "East")));
        JSONObject rivalCollabInline = new JSONObject().put("id", 72).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 702).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 3).put("name", "East"))
                        .put(new JSONObject().put("id", 4).put("name", "South")
                                .put("alias", new JSONArray().put("East"))));
        List<String> collabLookupsInline = new ArrayList<>();
        JSONObject collabTieInlineRes = MusicApi.matchTrack(collabTieReq,
                query -> new JSONArray().put(directCollab).put(rivalCollabInline),
                id -> {
                    collabLookupsInline.add(id);
                    return "3".equals(id) ? new Member("East", id, Collections.singletonList("North")) : null;
                });
        that(!collabTieInlineRes.getBoolean("matched") && collabLookupsInline.equals(Collections.singletonList("3")),
                "P1-1(S4b): contended collab slot fetches East detail (id=3) and rejects distinct-ID WRITE tie");
        JSONObject rivalCollabDetail = new JSONObject().put("id", 72).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 702).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 3).put("name", "East"))
                        .put(new JSONObject().put("id", 4).put("name", "South")));
        List<String> collabLookupsDetail = new ArrayList<>();
        JSONObject collabTieDetailRes = MusicApi.matchTrack(collabTieReq,
                query -> new JSONArray().put(directCollab).put(rivalCollabDetail),
                id -> {
                    collabLookupsDetail.add(id);
                    if ("3".equals(id)) return new Member("East", id, Collections.singletonList("North"));
                    if ("4".equals(id)) return new Member("South", id, Collections.singletonList("East"));
                    return null;
                });
        that(!collabTieDetailRes.getBoolean("matched")
                && collabLookupsDetail.contains("3") && collabLookupsDetail.contains("4"),
                "P1-1(S4b): collab rival with both aliases in details resolves contention and rejects WRITE tie");

        // Pass 4b P1-2: Explicit live-year mismatches (2008 Live vs 2007 Live) never burn WRITE rival enrichment budget.
        JSONObject live2007Req = new JSONObject().put("title", "Ballad (2007 Live)").put("artist", "North")
                .put("album", "World Stage (Live)").put("duration", 277000);
        JSONArray liveBatch = new JSONArray()
                .put(new JSONObject().put("id", 801).put("name", "Ballad (2007 Live)").put("dt", 277000)
                        .put("al", new JSONObject().put("id", 81).put("name", "World Stage (Live)"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "North"))));
        for (int i = 0; i < 4; i++) {
            liveBatch.put(new JSONObject().put("id", 810 + i).put("name", "Ballad (2008 Live)").put("dt", 277000)
                    .put("al", new JSONObject().put("id", 82 + i).put("name", "World Stage (Live)"))
                    .put("ar", new JSONArray().put(new JSONObject().put("id", 3 + i).put("name", "Other" + i))));
        }
        liveBatch.put(new JSONObject().put("id", 802).put("name", "Ballad (2007 Live)").put("dt", 277000)
                .put("al", new JSONObject().put("id", 89).put("name", "World Stage (Live)"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 7).put("name", "AliasHolder"))));
        List<String> liveLookups = new ArrayList<>();
        JSONObject liveTieRes = MusicApi.matchTrack(live2007Req, query -> liveBatch, id -> {
            liveLookups.add(id);
            return "7".equals(id) ? new Member("AliasHolder", id, Collections.singletonList("North")) : null;
        });
        that(!liveTieRes.getBoolean("matched") && liveLookups.equals(Collections.singletonList("7")),
                "P1-2(S4b): explicit 2008 Live mismatches consume zero enrichment budget so 2007 Live rival 802 is checked");

        // Pass 4b P2-1: Cross-round merge never stitches an unrelated album's ID onto Record, and preserves filled title.
        JSONObject rowRecordNoId = new JSONObject().put("id", 910).put("name", "Anthem").put("dt", 200000)
                .put("al", new JSONObject().put("name", "Record"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 10).put("name", "Lead Alpha")));
        JSONObject rowElsewhereWithId = new JSONObject().put("id", 910).put("name", "Anthem").put("dt", 200000)
                .put("al", new JSONObject().put("id", 900).put("name", "Elsewhere"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 10).put("name", "Lead Alpha")
                        .put("alias", new JSONArray().put("AliasAlpha"))));
        JSONObject stitchedRes = MusicApi.matchTrack(
                new JSONObject().put("title", "Anthem").put("artist", "AliasAlpha").put("album", "Record").put("duration", 200000),
                query -> "Anthem AliasAlpha".equals(query)
                        ? new JSONArray().put(rowRecordNoId) : new JSONArray().put(rowElsewhereWithId),
                id -> null);
        that(stitchedRes.getBoolean("matched") && "Record".equals(stitchedRes.getString("album"))
                && stitchedRes.getString("albumId").isEmpty(),
                "P2-1(S4b): cross-round merge never stitches unrelated album Elsewhere's ID 900 onto Record");

        JSONObject rowBlankTitle = new JSONObject().put("id", 911).put("name", "").put("dt", 200000)
                .put("al", new JSONObject().put("name", "Record"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 10).put("name", "Lead Alpha")));
        JSONObject rowFilledTitle = new JSONObject().put("id", 911).put("name", "Anthem").put("dt", 200000)
                .put("al", new JSONObject().put("id", 912).put("name", "Record"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 10).put("name", "Lead Alpha")
                        .put("alias", new JSONArray().put("AliasAlpha"))));
        JSONObject filledTitleRes = MusicApi.matchTrack(
                new JSONObject().put("title", "Anthem").put("artist", "AliasAlpha").put("album", "Record").put("duration", 200000),
                query -> "Anthem AliasAlpha".equals(query)
                        ? new JSONArray().put(rowBlankTitle) : new JSONArray().put(rowFilledTitle),
                id -> null);
        that(filledTitleRes.getBoolean("matched") && "Anthem".equals(filledTitleRes.getString("title"))
                && "912".equals(filledTitleRes.getString("albumId")),
                "P2-1(S4b): cross-round merge populates missing title and same-album ID in returned payload");

        // Pass 4c P1-1a: A5 full-coverage rival where detail alias clears target-visible bracket conflict (North (Nord)).
        JSONObject a5Req = new JSONObject().put("title", "Signal").put("artist", "North (Nord)")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject a5Inline = new JSONObject().put("id", 5011).put("name", "Signal").put("dt", 200000)
                .put("al", new JSONObject().put("id", 51).put("name", "Studio Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 11).put("name", "North")
                        .put("alias", new JSONArray().put("Nord"))));
        JSONObject a5Detail = new JSONObject().put("id", 5012).put("name", "Signal").put("dt", 200000)
                .put("al", new JSONObject().put("id", 52).put("name", "Studio Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 12).put("name", "North")));
        List<String> a5Lookups = new ArrayList<>();
        List<MatchTrace> a5Traces = new ArrayList<>();
        JSONObject a5Res = MusicApi.matchTrack(a5Req,
                query -> new JSONArray().put(a5Inline).put(a5Detail),
                id -> {
                    a5Lookups.add(id);
                    return "12".equals(id) ? new Member("North", id, Collections.singletonList("Nord")) : null;
                },
                "netease_match_api", MatchTrace.Policy.WRITE, a5Traces::add);
        that(!a5Res.getBoolean("matched") && a5Lookups.equals(Collections.singletonList("12"))
                && !a5Traces.isEmpty() && "ambiguous".equals(a5Traces.get(0).finalOutcome()),
                "P1-1a(S4c): WRITE rival enrichment clears A5 bracket conflict via detail alias and reports ambiguous");

        // Pass 4c P1-1b: A7 partial-coverage rival where detail alias supplies missing lead official evidence (甲 (North)).
        JSONObject a7Req = new JSONObject().put("title", "Signal").put("artist", "甲 (North)")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject a7Direct = new JSONObject().put("id", 5001).put("name", "Signal").put("dt", 200000)
                .put("al", new JSONObject().put("id", 61).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 21).put("name", "甲"))
                        .put(new JSONObject().put("id", 22).put("name", "Guest1")));
        JSONObject a7DetailRival = new JSONObject().put("id", 5002).put("name", "Signal").put("dt", 200000)
                .put("al", new JSONObject().put("id", 62).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 23).put("name", "North"))
                        .put(new JSONObject().put("id", 24).put("name", "Guest2")));
        List<String> a7Lookups = new ArrayList<>();
        List<MatchTrace> a7Traces = new ArrayList<>();
        JSONObject a7Res = MusicApi.matchTrack(a7Req,
                query -> new JSONArray().put(a7Direct).put(a7DetailRival),
                id -> {
                    a7Lookups.add(id);
                    return "23".equals(id) ? new Member("North", id, Collections.singletonList("甲")) : null;
                },
                "netease_match_api", MatchTrace.Policy.WRITE, a7Traces::add);
        that(!a7Res.getBoolean("matched") && a7Lookups.equals(Collections.singletonList("23"))
                && !a7Traces.isEmpty() && "ambiguous".equals(a7Traces.get(0).finalOutcome()),
                "P1-1b(S4c): WRITE rival enrichment fetches A7 lead official alias and reports ambiguous");

        // Pass 4c P1-2: Uncompletable candidates with an unmatched ID-less member consume zero budget before rival 602.
        JSONObject p12Req = new JSONObject().put("title", "Duet").put("artist", "North / East")
                .put("album", "Studio Album").put("duration", 200000);
        JSONArray p12Batch = new JSONArray()
                .put(new JSONObject().put("id", 601).put("name", "Duet").put("dt", 200000)
                        .put("al", new JSONObject().put("id", 71).put("name", "Studio Album"))
                        .put("ar", new JSONArray()
                                .put(new JSONObject().put("id", 1).put("name", "North"))
                                .put(new JSONObject().put("id", 2).put("name", "East"))));
        for (int i = 0; i < 4; i++) {
            p12Batch.put(new JSONObject().put("id", 610 + i).put("name", "Duet").put("dt", 200000)
                    .put("al", new JSONObject().put("id", 72 + i).put("name", "Studio Album"))
                    .put("ar", new JSONArray()
                            .put(new JSONObject().put("name", "NoIdStranger" + i))
                            .put(new JSONObject().put("id", 50 + i).put("name", "Fetchable" + i))));
        }
        p12Batch.put(new JSONObject().put("id", 602).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 79).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 48).put("name", "Rival"))
                        .put(new JSONObject().put("id", 49).put("name", "East"))));
        List<String> p12Lookups = new ArrayList<>();
        JSONObject p12Res = MusicApi.matchTrack(p12Req, query -> p12Batch, id -> {
            p12Lookups.add(id);
            return "48".equals(id) ? new Member("Rival", id, Collections.singletonList("North")) : null;
        });
        that(!p12Res.getBoolean("matched") && p12Lookups.equals(Collections.singletonList("48")),
                "P1-2(S4c): uncompletable candidates with ID-less unmatched member burn zero budget before rival 602");

        // Pass 4c P2: Nameless conflicting album ID (111) never stitches onto adopted album name Record (222) in either order.
        JSONObject rowNamelessId = new JSONObject().put("id", 917).put("name", "Anthem").put("dt", 200000)
                .put("al", new JSONObject().put("id", 111).put("name", ""))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 10).put("name", "Lead Alpha")));
        JSONObject rowNamedId = new JSONObject().put("id", 917).put("name", "Anthem").put("dt", 200000)
                .put("al", new JSONObject().put("id", 222).put("name", "Record"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 10).put("name", "Lead Alpha")
                        .put("alias", new JSONArray().put("AliasAlpha"))));
        JSONObject req917 = new JSONObject().put("title", "Anthem").put("artist", "AliasAlpha")
                .put("album", "Record").put("duration", 200000);
        JSONObject forward917 = MusicApi.matchTrack(req917,
                query -> "Anthem AliasAlpha".equals(query)
                        ? new JSONArray().put(rowNamelessId) : new JSONArray().put(rowNamedId),
                id -> null);
        JSONObject reverse917 = MusicApi.matchTrack(req917,
                query -> "Anthem AliasAlpha".equals(query)
                        ? new JSONArray().put(rowNamedId) : new JSONArray().put(rowNamelessId),
                id -> null);
        that(forward917.getBoolean("matched") && reverse917.getBoolean("matched")
                && "Record".equals(forward917.getString("album")) && "222".equals(forward917.getString("albumId"))
                && "Record".equals(reverse917.getString("album")) && "222".equals(reverse917.getString("albumId")),
                "P2(S4c): nameless conflicting album ID 111 never pairs with Record; both orders return coherent Record/222");

        // Pass 4d P1-1: Permanently non-unique partial subset candidates (2+ existing subset injections) consume
        // zero budget so genuine partial rival 802 (id=48) is checked in both candidate orderings.
        JSONObject p11Req = new JSONObject().put("title", "Duet").put("artist", "North / East")
                .put("album", "Studio Album").put("duration", 200000);
        JSONObject p11Direct = new JSONObject().put("id", 801).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 81).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 1).put("name", "North"))
                        .put(new JSONObject().put("id", 2).put("name", "East"))
                        .put(new JSONObject().put("id", 3).put("name", "Guest")));
        JSONObject p11Ambig1 = new JSONObject().put("id", 803).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 83).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 50).put("name", "North"))
                        .put(new JSONObject().put("id", 51).put("name", "East"))
                        .put(new JSONObject().put("id", 52).put("name", "Alt1")
                                .put("alias", new JSONArray().put("East"))));
        JSONObject p11Ambig2 = new JSONObject().put("id", 804).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 84).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 53).put("name", "North"))
                        .put(new JSONObject().put("id", 54).put("name", "East"))
                        .put(new JSONObject().put("id", 55).put("name", "Alt2")
                                .put("alias", new JSONArray().put("East"))));
        JSONObject p11Rival = new JSONObject().put("id", 802).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 82).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 48).put("name", "Rival"))
                        .put(new JSONObject().put("id", 2).put("name", "East"))
                        .put(new JSONObject().put("id", 3).put("name", "Guest")));
        for (JSONArray batch : Arrays.asList(
                new JSONArray().put(p11Direct).put(p11Ambig1).put(p11Ambig2).put(p11Rival),
                new JSONArray().put(p11Direct).put(p11Rival).put(p11Ambig1).put(p11Ambig2))) {
            List<String> lookups = new ArrayList<>();
            List<MatchTrace> traces = new ArrayList<>();
            JSONObject res = MusicApi.matchTrack(p11Req, query -> batch, id -> {
                lookups.add(id);
                return "48".equals(id) ? new Member("Rival", id, Collections.singletonList("North")) : null;
            }, "netease_match_api", MatchTrace.Policy.WRITE, traces::add);
            that(!res.getBoolean("matched") && lookups.equals(Collections.singletonList("48"))
                    && !traces.isEmpty() && "ambiguous".equals(traces.get(0).finalOutcome()),
                    "P1-1(S4d): permanently non-unique subset candidates burn zero budget; only [48] fetched -> ambiguous");
        }

        // Pass 4d P1-2: Pending A3 one-sided live-year fallback enriches same-album live competitor (2008 Live)
        // in both PLAYBACK and WRITE so detail alias North vetoes 701.
        JSONObject liveCompReq = new JSONObject().put("title", "Song (2007 Live)").put("artist", "North")
                .put("album", "World Stage (Live)").put("duration", 277000);
        JSONArray liveCompBatch = new JSONArray()
                .put(new JSONObject().put("id", 701).put("name", "Song (Live)").put("dt", 277000)
                        .put("al", new JSONObject().put("id", 70).put("name", "World Stage (Live)"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "North"))))
                .put(new JSONObject().put("id", 702).put("name", "Song (2008 Live)").put("dt", 277000)
                        .put("al", new JSONObject().put("id", 71).put("name", "World Stage (Live)"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 12).put("name", "Other"))));
        for (MatchTrace.Policy p : Arrays.asList(MatchTrace.Policy.PLAYBACK, MatchTrace.Policy.WRITE)) {
            List<String> lookups = new ArrayList<>();
            JSONObject res = MusicApi.matchTrack(liveCompReq, query -> liveCompBatch, id -> {
                lookups.add(id);
                return "12".equals(id) ? new Member("Other", id, Collections.singletonList("North")) : null;
            }, p == MatchTrace.Policy.PLAYBACK ? "netease_playback" : "netease_match_api", p, MatchTrace.NOOP);
            that(!res.getBoolean("matched") && lookups.equals(Collections.singletonList("12")),
                    "P1-2(S4d): A3 fallback enriches same-album 2008 Live competitor [12] and rejects 701 in " + p);
        }

        // Pass 4e P1-1a: A3 same-album live competitor enrichment shares the exact 30,000ms competition window
        // (5,001ms & 30,000ms enrich and reject 701; 30,001ms does not enrich and accepts 701).
        for (long deltaMs : new long[]{5001L, 30000L, 30001L}) {
            JSONArray deltaBatch = new JSONArray()
                    .put(new JSONObject().put("id", 701).put("name", "Song (Live)").put("dt", 277000)
                            .put("al", new JSONObject().put("id", 70).put("name", "World Stage (Live)"))
                            .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "North"))))
                    .put(new JSONObject().put("id", 702).put("name", "Song (2008 Live)").put("dt", 277000 + deltaMs)
                            .put("al", new JSONObject().put("id", 71).put("name", "World Stage (Live)"))
                            .put("ar", new JSONArray().put(new JSONObject().put("id", 12).put("name", "Other"))));
            for (MatchTrace.Policy p : Arrays.asList(MatchTrace.Policy.PLAYBACK, MatchTrace.Policy.WRITE)) {
                List<String> lookups = new ArrayList<>();
                JSONObject res = MusicApi.matchTrack(liveCompReq, query -> deltaBatch, id -> {
                    lookups.add(id);
                    return "12".equals(id) ? new Member("Other", id, Collections.singletonList("North")) : null;
                }, p == MatchTrace.Policy.PLAYBACK ? "netease_playback" : "netease_match_api", p, MatchTrace.NOOP);
                if (deltaMs <= 30000L) {
                    that(!res.getBoolean("matched") && lookups.equals(Collections.singletonList("12")),
                            "P1-1a(S4e): same-album 2008 Live competitor at +" + deltaMs + "ms enriches [12] and vetoes 701 in " + p);
                } else {
                    that(res.getBoolean("matched") && "701".equals(res.getString("id")) && lookups.isEmpty(),
                            "P1-1a(S4e): same-album 2008 Live at +" + deltaMs + "ms exceeds 30s competition window in " + p);
                }
            }
        }

        // Pass 4e P1-1b: A3 same-album live competitor also enriches against winning-hit artist (Rule B Group vs A & B[Group]).
        JSONObject liveRuleBReq = new JSONObject().put("title", "Song (2007 Live)").put("artist", "A / B")
                .put("album", "World Stage (Live)").put("duration", 277000);
        JSONArray liveRuleBBatch = new JSONArray()
                .put(new JSONObject().put("id", 711).put("name", "Song (Live)").put("dt", 277000)
                        .put("al", new JSONObject().put("id", 70).put("name", "World Stage (Live)"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 30).put("name", "Group"))))
                .put(new JSONObject().put("id", 712).put("name", "Song (2008 Live)").put("dt", 277000)
                        .put("al", new JSONObject().put("id", 71).put("name", "World Stage (Live)"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 31).put("name", "A & B"))));
        List<String> liveRuleBLookups = new ArrayList<>();
        JSONObject liveRuleBRes = MusicApi.matchTrack(liveRuleBReq, query -> liveRuleBBatch, id -> {
            liveRuleBLookups.add(id);
            return "31".equals(id) ? new Member("A & B", id, Collections.singletonList("Group")) : null;
        });
        that(!liveRuleBRes.getBoolean("matched") && liveRuleBLookups.equals(Arrays.asList("30", "31")),
                "P1-1b(S4e): A3 fallback enriches same-album competitor [31] against winning-hit artist Group and vetoes 711");

        // Pass 4e P1-2: A5-fragile edge on target bracket (North (Nord) [alias: 甲]) does not count as permanent ambiguity;
        // fetching River's detail alias North breaks (0,1) via A5, collapsing 902 to a unique subset -> ambiguous.
        JSONObject fragileTarget = new JSONObject().put("title", "Duet").put("artist", "North (Nord) / River")
                .put("album", "Studio Album").put("duration", 200000)
                .put("artistMembers", ArtistAliases.membersToJson(Arrays.asList(
                        new Member("North (Nord)", "q1", Collections.singletonList("甲")),
                        new Member("River", "q2", Collections.emptyList()))));
        JSONObject fragileDirect = new JSONObject().put("id", 901).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 91).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 81).put("name", "North")
                                .put("alias", new JSONArray().put("Nord")))
                        .put(new JSONObject().put("id", 82).put("name", "River"))
                        .put(new JSONObject().put("id", 83).put("name", "Guest1")));
        JSONObject fragileRival = new JSONObject().put("id", 902).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 92).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 91).put("name", "North")
                                .put("alias", new JSONArray().put("Nord").put("River")))
                        .put(new JSONObject().put("id", 92).put("name", "River")
                                .put("alias", new JSONArray().put("甲")))
                        .put(new JSONObject().put("id", 93).put("name", "Guest2")));
        List<String> fragileLookups = new ArrayList<>();
        List<MatchTrace> fragileTraces = new ArrayList<>();
        JSONObject fragileRes = MusicApi.matchTrack(fragileTarget,
                query -> new JSONArray().put(fragileDirect).put(fragileRival),
                id -> {
                    fragileLookups.add(id);
                    return "92".equals(id) ? new Member("River", id, Collections.singletonList("North")) : null;
                }, "netease_match_api", MatchTrace.Policy.WRITE, fragileTraces::add);
        that(!fragileRes.getBoolean("matched") && fragileLookups.equals(Collections.singletonList("92"))
                && !fragileTraces.isEmpty() && "ambiguous".equals(fragileTraces.get(0).finalOutcome()),
                "P1-2(S4e): A5-fragile edge on A7 partial subset fetches only [92] and detects ambiguous rival 902");

        // Pass 4e P1-2 full-bijection analogue (same 2-member credit without Guest).
        JSONObject fragileFullDirect = new JSONObject().put("id", 903).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 93).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 81).put("name", "North")
                                .put("alias", new JSONArray().put("Nord")))
                        .put(new JSONObject().put("id", 82).put("name", "River")));
        JSONObject fragileFullRival = new JSONObject().put("id", 904).put("name", "Duet").put("dt", 200000)
                .put("al", new JSONObject().put("id", 94).put("name", "Studio Album"))
                .put("ar", new JSONArray()
                        .put(new JSONObject().put("id", 91).put("name", "North")
                                .put("alias", new JSONArray().put("Nord").put("River")))
                        .put(new JSONObject().put("id", 94).put("name", "River")
                                .put("alias", new JSONArray().put("甲"))));
        List<String> fragileFullLookups = new ArrayList<>();
        List<MatchTrace> fragileFullTraces = new ArrayList<>();
        JSONObject fragileFullRes = MusicApi.matchTrack(fragileTarget,
                query -> new JSONArray().put(fragileFullDirect).put(fragileFullRival),
                id -> {
                    fragileFullLookups.add(id);
                    return "94".equals(id) ? new Member("River", id, Collections.singletonList("North")) : null;
                }, "netease_match_api", MatchTrace.Policy.WRITE, fragileFullTraces::add);
        that(!fragileFullRes.getBoolean("matched") && fragileFullLookups.equals(Collections.singletonList("94"))
                && !fragileFullTraces.isEmpty() && "ambiguous".equals(fragileFullTraces.get(0).finalOutcome()),
                "P1-2(S4e): A5-fragile edge on full bijection fetches only [94] and detects ambiguous rival 904");

        // Pass 4f P1-1: A3 live competitor with an unrelated conflicted guest (North (Stranger), id=0) still
        // enriches its fetchable lead (Other, id=12 -> North) and vetoes 701 in both PLAYBACK and WRITE.
        JSONArray liveConflictedGuestBatch = new JSONArray()
                .put(new JSONObject().put("id", 701).put("name", "Song (Live)").put("dt", 277000)
                        .put("al", new JSONObject().put("id", 70).put("name", "World Stage (Live)"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "North"))))
                .put(new JSONObject().put("id", 702).put("name", "Song (2008 Live)").put("dt", 277000)
                        .put("al", new JSONObject().put("id", 71).put("name", "World Stage (Live)"))
                        .put("ar", new JSONArray()
                                .put(new JSONObject().put("id", 12).put("name", "Other"))
                                .put(new JSONObject().put("id", 0).put("name", "North (Stranger)"))));
        for (MatchTrace.Policy p : Arrays.asList(MatchTrace.Policy.PLAYBACK, MatchTrace.Policy.WRITE)) {
            List<String> lookups = new ArrayList<>();
            JSONObject res = MusicApi.matchTrack(liveCompReq, query -> liveConflictedGuestBatch, id -> {
                lookups.add(id);
                return "12".equals(id) ? new Member("Other", id, Collections.singletonList("North")) : null;
            }, p == MatchTrace.Policy.PLAYBACK ? "netease_playback" : "netease_match_api", p, MatchTrace.NOOP);
            that(!res.getBoolean("matched") && lookups.equals(Collections.singletonList("12")),
                    "P1-1(S4f): A3 competitor with conflicted guest enriches lead [12] and rejects 701 in " + p);
        }

        // Pass 4f P1-2: Pending Rule A winner relying on an A5-fragile edge (North (Nord)[甲] vs 甲[detail:North])
        // verifies [44] before short-circuiting and rejects cross-album match in both WRITE and PLAYBACK.
        JSONObject fragileSoloTarget = new JSONObject().put("title", "Signal").put("artist", "North (Nord)")
                .put("album", "Album One").put("duration", 200000)
                .put("artistMembers", ArtistAliases.membersToJson(Collections.singletonList(
                        new Member("North (Nord)", "q1", Collections.singletonList("甲")))));
        JSONArray fragileSoloBatch = new JSONArray().put(new JSONObject()
                .put("id", 741).put("name", "Signal").put("dt", 200000)
                .put("al", new JSONObject().put("id", 74).put("name", "Album Two"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 44).put("name", "甲"))));
        for (MatchTrace.Policy p : Arrays.asList(MatchTrace.Policy.WRITE, MatchTrace.Policy.PLAYBACK)) {
            List<String> lookups = new ArrayList<>();
            JSONObject res = MusicApi.matchTrack(fragileSoloTarget, query -> fragileSoloBatch, id -> {
                lookups.add(id);
                return "44".equals(id) ? new Member("甲", id, Collections.singletonList("North")) : null;
            }, p == MatchTrace.Policy.PLAYBACK ? "netease_playback" : "netease_match_api", p, MatchTrace.NOOP);
            that(!res.getBoolean("matched") && lookups.equals(Collections.singletonList("44")),
                    "P1-2(S4f): fragile pending winner 741 fetches [44] and rejects A5 conflict in " + p);
        }

        // Pass 4g P1-1: Lower-tier fragile Rule A hits (cross-album id=50..53) never consume budget ahead of a
        // stable same-album top hit 751; same-album rival 752 (id=48) is fetched and triggers ambiguous.
        JSONObject p4g1Target = new JSONObject().put("title", "Signal").put("artist", "North (Nord)")
                .put("album", "Studio Album").put("duration", 200000)
                .put("artistMembers", ArtistAliases.membersToJson(Collections.singletonList(
                        new Member("North (Nord)", "q1", Collections.singletonList("甲")))));
        JSONArray p4g1Batch = new JSONArray()
                .put(new JSONObject().put("id", 751).put("name", "Signal").put("dt", 200000)
                        .put("al", new JSONObject().put("id", 75).put("name", "Studio Album"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "North")
                                .put("alias", new JSONArray().put("Nord")))));
        for (int i = 0; i < 4; i++) {
            p4g1Batch.put(new JSONObject().put("id", 760 + i).put("name", "Signal").put("dt", 200000)
                    .put("al", new JSONObject().put("id", 80 + i).put("name", "Other Album " + i))
                    .put("ar", new JSONArray().put(new JSONObject().put("id", 50 + i).put("name", "甲"))));
        }
        p4g1Batch.put(new JSONObject().put("id", 752).put("name", "Signal").put("dt", 200000)
                .put("al", new JSONObject().put("id", 76).put("name", "Studio Album"))
                .put("ar", new JSONArray().put(new JSONObject().put("id", 48).put("name", "Other"))));
        List<String> p4g1Lookups = new ArrayList<>();
        List<MatchTrace> p4g1Traces = new ArrayList<>();
        JSONObject p4g1Res = MusicApi.matchTrack(p4g1Target, query -> p4g1Batch, id -> {
            p4g1Lookups.add(id);
            return "48".equals(id) ? new Member("Other", id, Arrays.asList("North", "Nord")) : null;
        }, "netease_match_api", MatchTrace.Policy.WRITE, p4g1Traces::add);
        that(!p4g1Res.getBoolean("matched") && p4g1Lookups.equals(Collections.singletonList("48"))
                && !p4g1Traces.isEmpty() && "ambiguous".equals(p4g1Traces.get(0).finalOutcome()),
                "P1-1(S4g): lower-tier fragile hits burn zero budget ahead of stable top 751; only [48] fetched -> ambiguous");

        // Pass 4g P1-2: Fixed-point verification re-checks A3 live-year competition when WRITE rival enrichment
        // promotes 802 (2007 Live, same live album) above cross-album 801, enriching 803 (2008 Live, +20s) to veto 802.
        JSONObject p4g2Target = new JSONObject().put("title", "Song (Live)").put("artist", "North")
                .put("album", "Arena Live").put("duration", 100000);
        JSONArray p4g2Batch = new JSONArray()
                .put(new JSONObject().put("id", 801).put("name", "Song (Live)").put("dt", 100000)
                        .put("al", new JSONObject().put("id", 101).put("name", "Other Live"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "North"))))
                .put(new JSONObject().put("id", 802).put("name", "Song (2007 Live)").put("dt", 100000)
                        .put("al", new JSONObject().put("id", 102).put("name", "Arena Live"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 12).put("name", "SingerA"))))
                .put(new JSONObject().put("id", 803).put("name", "Song (2008 Live)").put("dt", 120000)
                        .put("al", new JSONObject().put("id", 103).put("name", "Arena Live"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 13).put("name", "SingerB"))));
        List<String> p4g2Lookups = new ArrayList<>();
        JSONObject p4g2Res = MusicApi.matchTrack(p4g2Target, query -> p4g2Batch, id -> {
            p4g2Lookups.add(id);
            if ("12".equals(id)) return new Member("SingerA", id, Collections.singletonList("North"));
            if ("13".equals(id)) return new Member("SingerB", id, Collections.singletonList("North"));
            return null;
        });
        that(p4g2Res.getBoolean("matched") && "801".equals(p4g2Res.getString("id"))
                && p4g2Lookups.equals(Arrays.asList("12", "13")),
                "P1-2(S4g): promoted same-album A3 hit 802 re-enters A3 competitor check [13], vetoes 802, and selects 801");
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
