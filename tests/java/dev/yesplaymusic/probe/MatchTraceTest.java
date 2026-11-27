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

/** Replays both matching directions and checks the decision trace and its logging boundary. */
final class MatchTraceTest {
    private MatchTraceTest() {}

    static void run() throws Exception {
        testForwardTraceAndRecordedYueyu();
        testGrpChildSurvivesMissingParentMediaMid();
        testReverseTraceAndGates();
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

    private static void testForwardTraceAndRecordedYueyu() throws Exception {
        List<ArtistAliases.Member> easonMembers = Collections.singletonList(
                new ArtistAliases.Member("陈奕迅", "2116", Collections.singletonList("Eason Chan")));

        // 1. NetEase 65778 (276306ms): grp[] expansion ranks exact-album 000SX6cK3Se2WV (306ms gap) #1 via Rule A.
        TraceFake api65778 = new TraceFake();
        api65778.results.put("粤语残片 陈奕迅", recordedYueyuPrimaryResponse());
        api65778.members = easonMembers;
        Track track65778 = Track.fromJson(new JSONObject()
                .put("id", "65778").put("title", "粤语残片").put("artist", "陈奕迅")
                .put("album", "What's Going On…?").put("duration", 276306));
        List<MatchTrace> captured = new ArrayList<>();
        List<JSONObject> resolved65778 = QQSource.lookupAttempts(track65778, api65778, "0", captured::add);
        that(captured.size() == 1 && "000SX6cK3Se2WV".equals(resolved65778.get(0).getString("qqMid")),
                "65778 resolves grp[0] exact studio master first and emits one trace");
        MatchTrace trace65778 = captured.get(0);
        that(trace65778.direction == MatchTrace.Direction.NETEASE_TO_QQ
                && trace65778.policy == MatchTrace.Policy.PLAYBACK
                && trace65778.mode == MatchTrace.Mode.TRACK_MATCHER
                && "65778".equals(trace65778.target.catalogId)
                && "all_artists".equals(trace65778.shortCircuitStage())
                && "vkey_ok".equals(trace65778.finalOutcome()),
                "65778 short-circuits on all_artists Rule A and records vkey_ok");
        MatchTrace.CandidateRecord acceptedGrp0 = findCandidate(trace65778, "qq:000SX6cK3Se2WV", "all_artists");
        that(acceptedGrp0 != null
                && acceptedGrp0.outcome == MatchTrace.Outcome.ACCEPTED
                && acceptedGrp0.rule == MatchTrace.Rule.A
                && acceptedGrp0.listIndex == 0 && acceptedGrp0.grpIndex == 0
                && "000J9BOC3sAUqd".equals(acceptedGrp0.parentMid)
                && Long.valueOf(306L).equals(acceptedGrp0.durationGapMs),
                "65778 trace captures grpIndex=0, parentMid, Rule.A, and 306ms gap");

        // 2. NetEase 1411495700 (Remastered 2019, 280264ms): list[0] (272s) rejected at DURATION (8264ms),
        //    while grp[0] 000SX6cK3Se2WV (276s, gap 4264ms) is accepted via Rule A.
        TraceFake api1411 = new TraceFake();
        api1411.results.put("粤语残片 陈奕迅", recordedYueyuPrimaryResponse());
        api1411.members = easonMembers;
        Track track1411 = Track.fromJson(new JSONObject()
                .put("id", "1411495700").put("title", "粤语残片").put("artist", "陈奕迅")
                .put("album", "What's Going On...? (Remastered 2019)").put("duration", 280264));
        captured.clear();
        List<JSONObject> resolved1411 = QQSource.lookupAttempts(track1411, api1411, "0", captured::add);
        that(captured.size() == 1 && "000SX6cK3Se2WV".equals(resolved1411.get(0).getString("qqMid")),
                "1411495700 recovers 276s studio master via grp[] expansion");
        MatchTrace trace1411 = captured.get(0);
        MatchTrace.CandidateRecord durRejected = findCandidate(trace1411, "qq:000J9BOC3sAUqd", "all_artists");
        MatchTrace.CandidateRecord grpAccepted = findCandidate(trace1411, "qq:000SX6cK3Se2WV", "all_artists");
        that(durRejected != null
                && durRejected.outcome == MatchTrace.Outcome.REJECTED
                && durRejected.failedGate == MatchTrace.Gate.DURATION
                && Long.valueOf(8264L).equals(durRejected.durationGapMs)
                && grpAccepted != null
                && grpAccepted.outcome == MatchTrace.Outcome.ACCEPTED
                && grpAccepted.rule == MatchTrace.Rule.A
                && Long.valueOf(4264L).equals(grpAccepted.durationGapMs),
                "1411495700 trace records parent 8264ms DURATION rejection alongside grp[0] 4264ms Rule.A acceptance");

        // 3. NetEase 65269 (粤语残片 (2007 Live), 277223ms): A3 matches 003oAh0u3RTCv3 (Live) on same live album.
        TraceFake api65269 = new TraceFake();
        api65269.results.put("粤语残片 (2007 Live) 陈奕迅", recordedYueyu2007LivePrimary());
        api65269.members = easonMembers;
        Track track65269 = Track.fromJson(new JSONObject()
                .put("id", "65269").put("title", "粤语残片 (2007 Live)").put("artist", "陈奕迅")
                .put("album", "Eason Moving On Stage 1 (Live)").put("duration", 277223));
        captured.clear();
        List<JSONObject> resolved65269 = QQSource.lookupAttempts(track65269, api65269, "0", captured::add);
        that(captured.size() == 1 && "003oAh0u3RTCv3".equals(resolved65269.get(0).getString("qqMid")),
                "65269 resolves 003oAh0u3RTCv3 on query 1 via A3 live-year same-album rule");
        MatchTrace trace65269 = captured.get(0);
        MatchTrace.CandidateRecord liveAccepted = findCandidate(trace65269, "qq:003oAh0u3RTCv3", "all_artists");
        that(liveAccepted != null
                && liveAccepted.listIndex == 3
                && liveAccepted.outcome == MatchTrace.Outcome.ACCEPTED
                && liveAccepted.rule == MatchTrace.Rule.A
                && Long.valueOf(223L).equals(liveAccepted.durationGapMs)
                && liveAccepted.detail.contains("live_year_same_album"),
                "65269 trace captures listIndex=3, Rule.A, 223ms gap, and live_year_same_album detail");
    }

    private static void testGrpChildSurvivesMissingParentMediaMid() throws Exception {
        TraceFake api = new TraceFake();
        JSONArray grp = new JSONArray().put(
                qqSong("childMid", "childFile", "Signal", "Lead Artist", "Studio Album", 200, null));
        api.results.put("Signal Lead Artist", new JSONArray()
                .put(qqSong("parentNoFile", "", "Signal", "Lead Artist", "Studio Album", 200, grp)));
        Track target = Track.fromJson(new JSONObject()
                .put("id", "777").put("title", "Signal").put("artist", "Lead Artist")
                .put("album", "Studio Album").put("duration", 200000));
        List<MatchTrace> captured = new ArrayList<>();
        List<JSONObject> resolved = QQSource.lookupAttempts(target, api, "0", captured::add);
        that("childMid".equals(resolved.get(0).getString("qqMid")),
                "valid grp[] child is still evaluated and matched when parent lacks media_mid");
        MatchTrace trace = captured.get(0);
        MatchTrace.CandidateRecord filtered = findCandidate(trace, "qq:parentNoFile", "all_artists");
        MatchTrace.CandidateRecord accepted = findCandidate(trace, "qq:childMid", "all_artists");
        that(filtered != null && filtered.listIndex == 0 && filtered.grpIndex == -1
                && filtered.failedGate == MatchTrace.Gate.MISSING_MEDIA_MID,
                "parent without media_mid is traced as FILTERED at listIndex=0");
        that(accepted != null && accepted.listIndex == 0 && accepted.grpIndex == 0
                && "parentNoFile".equals(accepted.parentMid) && accepted.rule == MatchTrace.Rule.A,
                "grp[0] child preserves listIndex=0, grpIndex=0, and parentMid");
    }

    private static void testReverseTraceAndGates() throws Exception {
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
                && trace.mode == MatchTrace.Mode.TRACK_MATCHER
                && "qq:001Mid".equals(trace.target.catalogId)
                && "matched".equals(trace.finalOutcome()),
                "reverse trace records direction, policy, TRACK_MATCHER mode, target ID, and matched outcome");
        that(trace.candidates().get(0).failedGate == MatchTrace.Gate.INVALID_ID
                && trace.candidates().get(0).candidateId.isEmpty()
                && !trace.formatSummary().contains("secret"),
                "invalid NetEase ID is traced without echoing untrusted raw IDs");
        that(findCandidate(trace, "101", "all_artists#0").failedGate == MatchTrace.Gate.VERSION,
                "version mismatch is traced");
        that(findCandidate(trace, "102", "all_artists#0").failedGate == MatchTrace.Gate.TITLE,
                "title mismatch is traced");
        that(findCandidate(trace, "103", "all_artists#0").failedGate == MatchTrace.Gate.DURATION,
                "duration >5000ms rejection is traced");
        MatchTrace.CandidateRecord accepted = findCandidate(trace, "104", "all_artists#0");
        that(accepted != null && accepted.outcome == MatchTrace.Outcome.ACCEPTED
                && accepted.rule == MatchTrace.Rule.A
                && Long.valueOf(500L).equals(accepted.durationGapMs),
                "accepted reverse candidate is traced with Rule.A and duration gap");
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
        MatchTrace.CandidateRecord okCand = findCandidate(trace, "qq:okMid", "all_artists");
        MatchTrace.CandidateRecord filtered = findCandidate(trace, "qq:noFileMid", "all_artists");
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
