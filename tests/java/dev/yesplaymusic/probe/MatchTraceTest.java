package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MatchTraceTest {
    private MatchTraceTest() {}

    static void run() throws Exception {
        testForwardTraceAndRecordedYueyu();
        testRawIndexPreservesFilteredRank();
        testReverseTraceAndPreConsiderGates();
        testReverseExceptionRecorded();
        testSanitizationAndZeroOverheadGuard();
    }

    private static JSONObject qqSong(String mid, String mediaMid, String title, String singer,
                                     String album, int intervalSeconds, JSONArray grp) throws Exception {
        JSONObject item = new JSONObject()
                .put("mid", mid)
                .put("file", new JSONObject().put("media_mid", mediaMid))
                .put("title", title)
                .put("singer", new JSONArray().put(new JSONObject().put("name", singer)))
                .put("album", new JSONObject().put("name", album))
                .put("interval", intervalSeconds);
        if (grp != null) item.put("grp", grp);
        return item;
    }

    private static JSONArray recordedYueyuPrimaryResponse() throws Exception {
        JSONArray grp = new JSONArray()
                .put(qqSong("000SX6cK3Se2WV", "004MotpJ2JgJfV", "粤语残片", "陈奕迅", "What's Going On...?", 276, null))
                .put(qqSong("000vPmze3aWPCm", "003zT5k51maUjQ", "粤语残片", "陈奕迅", "Solidays (2 CD)", 272, null))
                .put(qqSong("00299LKs1nNO45", "00472xjp3xyEUl", "粤语残片", "陈奕迅", "What's Going On...? (2nd Edition)", 276, null));
        return new JSONArray()
                .put(qqSong("000J9BOC3sAUqd", "003BGqJ14ak5uu", "粤语残片", "陈奕迅", "What's Going On...? (Remastered 2019)", 272, grp))
                .put(qqSong("0028qpYH1uolzX", "003254o74ZDKtG", "白色球鞋", "陈奕迅", "认了吧", 278, null))
                .put(qqSong("003oAh0u3RTCv3", "0044VTIY1sLCbW", "粤语残片 (Live)", "陈奕迅", "Eason Moving On Stage 1 (Live)", 277, null))
                .put(qqSong("000iAWdB3V89ZR", "000iAWdB3V89ZR", "粤语残片 (2025 FEAR and DREAMS世界巡回演唱会澳门站)", "陈奕迅", "", 269, null))
                .put(qqSong("001LyLbL26SHYQ", "001LyLbL26SHYQ", "粤语残片 (2007香港Eason's Moving On Stage 1 演唱会片段)", "陈奕迅歌迷汇", "", 19, null))
                .put(qqSong("004CU50m2JjBjr", "000wa9ED359rBP", "单车", "陈奕迅", "Shall We Dance? Shall We Talk!", 211, null))
                .put(qqSong("004OvYY92L7iJB", "004OvYY92L7iJB", "粤语残片 (DJ 阿若版)", "陈奕迅", "", 199, null))
                .put(qqSong("000CXyE50NZOQn", "000CXyE50NZOQn", "粤语残片 (DJ 阿雪版)", "陈奕迅", "", 350, null))
                .put(qqSong("001OyHbk2MSIi4", "002AIxAT3HZwiA", "十年", "陈奕迅", "黑白灰", 205, null))
                .put(qqSong("0029Zemv0kR1ur", "000mVAmc4SRnnN", "葡萄成熟时", "陈奕迅", "U 87", 277, null));
    }

    private static JSONArray recordedYueyu2007LivePrimary() throws Exception {
        return new JSONArray()
                .put(qqSong("001LyLbL26SHYQ", "001LyLbL26SHYQ", "粤语残片 (2007香港Eason's Moving On Stage 1 演唱会片段)", "陈奕迅歌迷汇", "", 19, null))
                .put(qqSong("000J9BOC3sAUqd", "003BGqJ14ak5uu", "粤语残片", "陈奕迅", "What's Going On...? (Remastered 2019)", 272, null))
                .put(qqSong("0028qpYH1uolzX", "003254o74ZDKtG", "白色球鞋", "陈奕迅", "认了吧", 278, null))
                .put(qqSong("003oAh0u3RTCv3", "0044VTIY1sLCbW", "粤语残片 (Live)", "陈奕迅", "Eason Moving On Stage 1 (Live)", 277, null))
                .put(qqSong("002SRKJw2U4hxz", "002SRKJw2U4hxz", "白色球鞋(粤语残片)", "都叫我老詹", "白色球鞋", 260, null))
                .put(qqSong("000dSwZh0izxIu", "000dSwZh0izxIu", "粤语残片 (女生氛围版)", "信知夏", "粤语残片(女生氛围版)", 136, null))
                .put(qqSong("001XMyfL3yXL65", "001XMyfL3yXL65", "粤语残片 (港风女声版)", "嘉欣", "粤语残片（港风女声版）", 284, null))
                .put(qqSong("000iAWdB3V89ZR", "000iAWdB3V89ZR", "粤语残片 (2025 FEAR and DREAMS世界巡回演唱会澳门站)", "陈奕迅", "", 269, null))
                .put(qqSong("001YzPkf18A8U4", "001YzPkf18A8U4", "粤语残片", "叫我阿顶", "顶级黑粉翻唱集", 271, null))
                .put(qqSong("000VNlcM0udCXc", "000VNlcM0udCXc", "粤语残片", "狸昭昭Kumo", "粵語殘片", 275, null));
    }

    private static JSONArray recordedYueyu2007LiveAlias() throws Exception {
        return new JSONArray()
                .put(qqSong("000J9BOC3sAUqd", "003BGqJ14ak5uu", "粤语残片", "陈奕迅", "What's Going On...? (Remastered 2019)", 272, null))
                .put(qqSong("000dSwZh0izxIu", "000dSwZh0izxIu", "粤语残片 (女生氛围版)", "信知夏", "粤语残片(女生氛围版)", 136, null))
                .put(qqSong("001XMyfL3yXL65", "001XMyfL3yXL65", "粤语残片 (港风女声版)", "嘉欣", "粤语残片（港风女声版）", 284, null))
                .put(qqSong("0028qpYH1uolzX", "003254o74ZDKtG", "白色球鞋", "陈奕迅", "认了吧", 278, null))
                .put(qqSong("000VNlcM0udCXc", "000VNlcM0udCXc", "粤语残片", "狸昭昭Kumo", "粵語殘片", 275, null))
                .put(qqSong("001YzPkf18A8U4", "001YzPkf18A8U4", "粤语残片", "叫我阿顶", "顶级黑粉翻唱集", 271, null))
                .put(qqSong("000wWq2w1LzD1n", "000wWq2w1LzD1n", "粤语残片 (Live)", "我唔系肥晴", "", 73, null))
                .put(qqSong("002iyI3j2kwwPB", "002iyI3j2kwwPB", "粤语残片 (纯享版)", "刘艺哲", "粤语残片(纯享版)", 156, null))
                .put(qqSong("0021OHcD1nJkEJ", "0019D8Mi1Kkhx5", "粤语残片 (Live)", "木白", "", 270, null))
                .put(qqSong("0042Hy9q3wkzb7", "0042Hy9q3wkzb7", "粤语残片 (R&B版)", "金亦", "2026热门情歌R&B珍藏版", 253, null));
    }

    private static JSONArray recordedYueyu2007LiveBare() throws Exception {
        return new JSONArray()
                .put(qqSong("000J9BOC3sAUqd", "003BGqJ14ak5uu", "粤语残片", "陈奕迅", "What's Going On...? (Remastered 2019)", 272, null))
                .put(qqSong("0028qpYH1uolzX", "003254o74ZDKtG", "白色球鞋", "陈奕迅", "认了吧", 278, null))
                .put(qqSong("003oAh0u3RTCv3", "0044VTIY1sLCbW", "粤语残片 (Live)", "陈奕迅", "Eason Moving On Stage 1 (Live)", 277, null))
                .put(qqSong("000dSwZh0izxIu", "000dSwZh0izxIu", "粤语残片 (女生氛围版)", "信知夏", "粤语残片(女生氛围版)", 136, null))
                .put(qqSong("000iAWdB3V89ZR", "000iAWdB3V89ZR", "粤语残片 (2025 FEAR and DREAMS世界巡回演唱会澳门站)", "陈奕迅", "", 269, null))
                .put(qqSong("001XMyfL3yXL65", "001XMyfL3yXL65", "粤语残片 (港风女声版)", "嘉欣", "粤语残片（港风女声版）", 284, null))
                .put(qqSong("002SRKJw2U4hxz", "002SRKJw2U4hxz", "白色球鞋(粤语残片)", "都叫我老詹", "白色球鞋", 260, null))
                .put(qqSong("000VNlcM0udCXc", "000VNlcM0udCXc", "粤语残片", "狸昭昭Kumo", "粵語殘片", 275, null))
                .put(qqSong("000wWq2w1LzD1n", "000wWq2w1LzD1n", "粤语残片 (Live)", "我唔系肥晴", "", 73, null))
                .put(qqSong("001YzPkf18A8U4", "001YzPkf18A8U4", "粤语残片", "叫我阿顶", "顶级黑粉翻唱集", 271, null));
    }

    private static void testForwardTraceAndRecordedYueyu() throws Exception {
        List<ArtistAliases.Member> easonMembers = Collections.singletonList(
                new ArtistAliases.Member("陈奕迅", "2116", Collections.singletonList("Eason Chan")));

        // 1. NetEase 65778 (276306ms) accepts list[0] (272s, gap 4306ms) and notes grp=3 on list[0].
        TraceFake api65778 = new TraceFake();
        api65778.results.put("粤语残片 陈奕迅", recordedYueyuPrimaryResponse());
        api65778.members = easonMembers;
        Track track65778 = Track.fromJson(new JSONObject()
                .put("id", "65778").put("title", "粤语残片").put("artist", "陈奕迅")
                .put("album", "What's Going On…?").put("duration", 276306));
        List<MatchTrace> captured = new ArrayList<>();
        QQSource.lookupAttempts(track65778, api65778, "0", captured::add);
        that(captured.size() == 1, "forward match emits one trace");
        MatchTrace trace65778 = captured.get(0);
        that(trace65778.direction == MatchTrace.Direction.NETEASE_TO_QQ
                && trace65778.policy == MatchTrace.Policy.PLAYBACK
                && "65778".equals(trace65778.target.catalogId)
                && "primary_strict".equals(trace65778.shortCircuitStage())
                && "vkey_ok".equals(trace65778.finalOutcome()),
                "65778 short-circuits on primary strict match and records vkey_ok");
        MatchTrace.CandidateRecord accepted0 = findCandidate(trace65778, "qq:000J9BOC3sAUqd", "primary");
        that(accepted0 != null
                && accepted0.outcome == MatchTrace.Outcome.ACCEPTED
                && accepted0.rule == MatchTrace.Rule.LEGACY_STRICT
                && Long.valueOf(4306L).equals(accepted0.durationGapMs)
                && accepted0.detail.contains("grp=3"),
                "65778 trace captures gap=4306ms and collapsed grp=3 count");

        // 2. NetEase 1411495700 (Remastered 2019, 280264ms) rejects 000J9BOC3sAUqd at Gate.DURATION (gap=8264ms).
        TraceFake api1411 = new TraceFake();
        api1411.results.put("粤语残片 陈奕迅", recordedYueyuPrimaryResponse());
        api1411.results.put("粤语残片 Eason Chan", recordedYueyuPrimaryResponse());
        api1411.results.put("粤语残片", recordedYueyuPrimaryResponse());
        api1411.members = easonMembers;
        Track track1411 = Track.fromJson(new JSONObject()
                .put("id", "1411495700").put("title", "粤语残片").put("artist", "陈奕迅")
                .put("album", "What's Going On...? (Remastered 2019)").put("duration", 280264));
        captured.clear();
        boolean notFound = false;
        try {
            QQSource.lookupAttempts(track1411, api1411, "0", captured::add);
        } catch (QQSource.ResolveException expected) {
            notFound = "match_not_found".equals(expected.code);
        }
        that(notFound && captured.size() == 1, "1411495700 emits trace even on match_not_found");
        MatchTrace trace1411 = captured.get(0);
        MatchTrace.CandidateRecord durRejected = findCandidate(trace1411, "qq:000J9BOC3sAUqd", "primary");
        that(durRejected != null
                && durRejected.outcome == MatchTrace.Outcome.REJECTED
                && durRejected.failedGate == MatchTrace.Gate.DURATION
                && Long.valueOf(8264L).equals(durRejected.durationGapMs)
                && durRejected.detail.contains("grp=3")
                && trace1411.vkeyAttempts().isEmpty()
                && "match_not_found/no_verified_recording".equals(trace1411.finalErrorCode()),
                "1411495700 trace pinpoints duration gate (8264ms) and zero vkey attempts");

        // 3. NetEase 65269 (粤语残片 (2007 Live), 277223ms) across all three recorded 10-hit QQ responses.
        TraceFake api65269 = new TraceFake();
        api65269.results.put("粤语残片 (2007 Live) 陈奕迅", recordedYueyu2007LivePrimary());
        api65269.results.put("粤语残片 (2007 Live) Eason Chan", recordedYueyu2007LiveAlias());
        api65269.results.put("粤语残片 (2007 Live)", recordedYueyu2007LiveBare());
        api65269.members = easonMembers;
        Track track65269 = Track.fromJson(new JSONObject()
                .put("id", "65269").put("title", "粤语残片 (2007 Live)").put("artist", "陈奕迅")
                .put("album", "Eason Moving On Stage 1 (Live)").put("duration", 277223));
        captured.clear();
        notFound = false;
        try {
            QQSource.lookupAttempts(track65269, api65269, "0", captured::add);
        } catch (QQSource.ResolveException expected) {
            notFound = "match_not_found".equals(expected.code);
        }
        that(notFound && captured.size() == 1, "65269 emits trace on match_not_found");
        MatchTrace trace65269 = captured.get(0);
        that(trace65269.queries().size() == 3
                && trace65269.queries().get(0).rawCount == 10
                && trace65269.queries().get(1).rawCount == 10
                && trace65269.queries().get(2).rawCount == 10,
                "65269 replays all three distinct 10-hit QQ responses");
        MatchTrace.CandidateRecord titleRejected = findCandidate(trace65269, "qq:003oAh0u3RTCv3", "primary");
        that(titleRejected != null
                && titleRejected.listIndex == 3
                && titleRejected.outcome == MatchTrace.Outcome.REJECTED
                && titleRejected.failedGate == MatchTrace.Gate.TITLE
                && Long.valueOf(223L).equals(titleRejected.durationGapMs)
                && "粤语残片2007live".equals(titleRejected.targetMainTitle)
                && "粤语残片live".equals(titleRejected.candidateMainTitle)
                && titleRejected.targetVersionTags.equals(titleRejected.candidateVersionTags),
                "65269 trace pinpoints mainTitle mismatch at raw list[3] despite matching live version and 223ms gap");
    }

    private static void testRawIndexPreservesFilteredRank() throws Exception {
        TraceFake api = new TraceFake();
        api.results.put("First Love 宇多田ヒカル", new JSONArray()
                .put(qqSong("missingFile", "", "First Love", "宇多田光", "", 257, null))
                .put(qqSong("validCross", "fileCross", "First Love", "宇多田光", "", 257, null)));
        Track crossScript = Track.fromJson(new JSONObject()
                .put("id", "777").put("title", "First Love").put("artist", "宇多田ヒカル")
                .put("album", "").put("duration", 257000));
        List<MatchTrace> captured = new ArrayList<>();
        List<JSONObject> resolved = QQSource.lookupAttempts(crossScript, api, "0", captured::add);
        that("validCross".equals(resolved.get(0).getString("qqMid")),
                "leading item without media_mid does not break legacy filtered rank==0 behavior");
        MatchTrace trace = captured.get(0);
        MatchTrace.CandidateRecord filtered = findCandidate(trace, "qq:missingFile", "primary");
        MatchTrace.CandidateRecord accepted = findCandidate(trace, "qq:validCross", "after_aliases");
        that(filtered != null && filtered.listIndex == 0 && filtered.failedGate == MatchTrace.Gate.MISSING_MEDIA_MID,
                "dropped row keeps raw listIndex=0 in trace");
        that(accepted != null && accepted.listIndex == 1 && accepted.detail.contains("filteredRank=0"),
                "accepted row preserves raw listIndex=1 alongside filteredRank=0");
    }

    private static void testReverseTraceAndPreConsiderGates() throws Exception {
        JSONObject args = new JSONObject()
                .put("id", "qq:001Mid")
                .put("title", "Song")
                .put("artist", "Artist")
                .put("album", "Album")
                .put("duration", 200000);
        JSONArray songs = new JSONArray()
                .put(new JSONObject().put("id", "bad?token=secret").put("name", "Song").put("dt", 200000))
                .put(new JSONObject().put("id", 101).put("name", "Song (Live)").put("dt", 200000)
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "Artist"))))
                .put(new JSONObject().put("id", 102).put("name", "Other").put("dt", 200000)
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "Artist"))))
                .put(new JSONObject().put("id", 103).put("name", "Song").put("dt", 212000)
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "Artist"))))
                .put(new JSONObject().put("id", 104).put("name", "Song").put("dt", 200500)
                        .put("al", new JSONObject().put("name", "Album"))
                        .put("ar", new JSONArray().put(new JSONObject().put("id", 1).put("name", "Artist"))));
        List<MatchTrace> captured = new ArrayList<>();
        JSONObject result = MusicApi.matchTrack(args, q -> songs, id -> null,
                "netease_match_api", MatchTrace.Policy.WRITE, captured::add);
        that(result.getBoolean("matched") && "104".equals(result.getString("id")),
                "reverse traced match preserves exact matching decision");
        that(captured.size() == 1, "reverse match emits one trace");
        MatchTrace trace = captured.get(0);
        that(trace.direction == MatchTrace.Direction.QQ_TO_NETEASE
                && trace.policy == MatchTrace.Policy.WRITE
                && "qq:001Mid".equals(trace.target.catalogId)
                && "matched".equals(trace.finalOutcome()),
                "reverse trace records direction, policy, target ID, and matched outcome");
        that(trace.candidates().get(0).failedGate == MatchTrace.Gate.INVALID_ID
                && trace.candidates().get(0).candidateId.isEmpty()
                && !trace.formatSummary().contains("secret"),
                "pre-consider invalid ID is traced without echoing untrusted raw IDs");
        that(findCandidate(trace, "101", "primary#0").failedGate == MatchTrace.Gate.VERSION,
                "pre-consider version mismatch is traced");
        that(findCandidate(trace, "102", "primary#0").failedGate == MatchTrace.Gate.TITLE,
                "pre-consider title mismatch is traced");
        that(findCandidate(trace, "103", "primary#0").failedGate == MatchTrace.Gate.DURATION,
                "consider >8000ms duration filter is traced");
        MatchTrace.CandidateRecord accepted = findCandidate(trace, "104", "primary#0");
        that(accepted != null && accepted.outcome == MatchTrace.Outcome.ACCEPTED
                && accepted.rule == MatchTrace.Rule.LEGACY_STRICT
                && Long.valueOf(500L).equals(accepted.durationGapMs),
                "accepted reverse candidate is traced with rule and duration gap");
    }

    private static void testReverseExceptionRecorded() throws Exception {
        JSONObject args = new JSONObject().put("title", "Song").put("artist", "Artist").put("duration", 200000);
        List<MatchTrace> captured = new ArrayList<>();
        boolean threwIo = false;
        try {
            MusicApi.matchTrack(args, q -> { throw new IOException("socket reset"); }, id -> null,
                    "netease_match_api", MatchTrace.Policy.WRITE, captured::add);
        } catch (IOException expected) {
            threwIo = true;
        }
        that(threwIo && captured.size() == 1
                && "error".equals(captured.get(0).finalOutcome())
                && "IOException".equals(captured.get(0).finalErrorCode()),
                "reverse search IOException records error type in trace and rethrows unchanged");
    }

    private static void testSanitizationAndZeroOverheadGuard() throws Exception {
        that(!MatchTrace.isActive(null) && !MatchTrace.isActive(MatchTrace.NOOP),
                "NOOP and null sinks skip trace allocation completely");
        that("<invalid>".equals(MatchTrace.sanitizeId("bad/id?MUSIC_U=abc"))
                && "qq:000J9BOC3sAUqd".equals(MatchTrace.sanitizeId("qq:000J9BOC3sAUqd"))
                && "65778".equals(MatchTrace.sanitizeId("65778")),
                "catalog ID sanitizer accepts only validated ID shapes");

        TraceFake api = new TraceFake();
        api.results.put("Song https://evil.example/leak?vkey=123 Artist MUSIC_U=topsecret", new JSONArray()
                .put(qqSong("okMid", "okFile", "Song https://evil.example/leak?vkey=123",
                        "Artist MUSIC_U=topsecret", "Album", 0, null))
                .put(qqSong("noFileMid", "", "Song", "Artist", "Album", 200, null)));
        Track noisyTarget = Track.fromJson(new JSONObject()
                .put("id", "bad/id?cookie=xyz")
                .put("title", "Song https://evil.example/leak?vkey=123")
                .put("artist", "Artist MUSIC_U=topsecret")
                .put("album", "Album")
                .put("duration", 0)
                .put("url", "https://secret.example/signed?token=SHOULD_NOT_APPEAR"));
        List<MatchTrace> captured = new ArrayList<>();
        QQSource.lookupAttempts(noisyTarget, api, "0", captured::add);
        MatchTrace trace = captured.get(0);
        MatchTrace.CandidateRecord okCand = findCandidate(trace, "qq:okMid", "primary");
        MatchTrace.CandidateRecord filtered = findCandidate(trace, "qq:noFileMid", "primary");
        that(okCand != null && okCand.durationGapMs == null,
                "missing duration records null gap instead of Long.MAX_VALUE");
        that(filtered != null && filtered.outcome == MatchTrace.Outcome.FILTERED
                && filtered.failedGate == MatchTrace.Gate.MISSING_MEDIA_MID,
                "missing media_mid is traced as FILTERED");
        String summary = trace.formatSummary();
        that(!summary.contains("SHOULD_NOT_APPEAR")
                && !summary.contains("vkey=fixture")
                && !summary.contains("evil.example")
                && !summary.contains("topsecret")
                && !summary.contains("cookie=xyz")
                && !summary.contains("https://"),
                "formatted trace redacts URLs, cookies, tokens, and untrusted IDs at output boundary");
        that(summary.contains("gap=null") && summary.contains("vkey mid=okMid media=okFile result=\"ok\""),
                "formatted trace stays human-readable and includes sanitized vkey result");
    }

    private static MatchTrace.CandidateRecord findCandidate(MatchTrace trace, String id, String round) {
        for (MatchTrace.CandidateRecord record : trace.candidates()) {
            if (id.equals(record.candidateId) && round.equals(record.round)) return record;
        }
        return null;
    }

    private static final class TraceFake implements QQSource.Lookup {
        final Map<String, JSONArray> results = new LinkedHashMap<>();
        List<ArtistAliases.Member> members = Collections.emptyList();

        @Override public JSONArray search(String query) {
            return results.getOrDefault(query, new JSONArray());
        }
        @Override public JSONObject detail(String mid) { return null; }
        @Override public List<String> aliases(Track track) { return Collections.emptyList(); }
        @Override public List<ArtistAliases.Member> artistMembers(Track track) { return members; }
        @Override public JSONObject vkey(JSONObject params) throws Exception {
            String mid = params.getJSONArray("songmid").getString(0);
            String file = params.getJSONArray("filename").getString(0);
            return new JSONObject()
                    .put("retcode", 0)
                    .put("sip", new JSONArray().put("https://a.qqmusic.qq.com/"))
                    .put("midurlinfo", new JSONArray().put(new JSONObject()
                            .put("filename", file).put("songmid", mid).put("result", 0)
                            .put("purl", file + "?vkey=fixture")));
        }
    }
}
