package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MatchTraceTest {
    private MatchTraceTest() {}

    static void run() throws Exception {
        testForwardTraceAndRecordedYueyu();
        testReverseTraceAndPreConsiderGates();
        testSanitizationAndNullDuration();
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
                .put(qqSong("004OvYY92L7iJB", "004OvYY92L7iJB", "粤语残片 (DJ 阿若版)", "陈奕迅", "", 199, null));
    }

    private static JSONArray recordedYueyu2007LiveResponse() throws Exception {
        return new JSONArray()
                .put(qqSong("001LyLbL26SHYQ", "001LyLbL26SHYQ", "粤语残片 (2007香港Eason's Moving On Stage 1 演唱会片段)", "陈奕迅歌迷汇", "", 19, null))
                .put(qqSong("000J9BOC3sAUqd", "003BGqJ14ak5uu", "粤语残片", "陈奕迅", "What's Going On...? (Remastered 2019)", 272, null))
                .put(qqSong("0028qpYH1uolzX", "003254o74ZDKtG", "白色球鞋", "陈奕迅", "认了吧", 278, null))
                .put(qqSong("003oAh0u3RTCv3", "0044VTIY1sLCbW", "粤语残片 (Live)", "陈奕迅", "Eason Moving On Stage 1 (Live)", 277, null));
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

        // 3. NetEase 65269 (粤语残片 (2007 Live), 277223ms) rejects 003oAh0u3RTCv3 (粤语残片 (Live)) at Gate.TITLE.
        TraceFake api65269 = new TraceFake();
        api65269.results.put("粤语残片 (2007 Live) 陈奕迅", recordedYueyu2007LiveResponse());
        api65269.results.put("粤语残片 (2007 Live) Eason Chan", new JSONArray());
        api65269.results.put("粤语残片 (2007 Live)", recordedYueyu2007LiveResponse());
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
        MatchTrace.CandidateRecord titleRejected = findCandidate(trace65269, "qq:003oAh0u3RTCv3", "primary");
        that(titleRejected != null
                && titleRejected.outcome == MatchTrace.Outcome.REJECTED
                && titleRejected.failedGate == MatchTrace.Gate.TITLE
                && Long.valueOf(223L).equals(titleRejected.durationGapMs)
                && "粤语残片2007live".equals(titleRejected.targetMainTitle)
                && "粤语残片live".equals(titleRejected.candidateMainTitle)
                && titleRejected.targetVersionTags.equals(titleRejected.candidateVersionTags),
                "65269 trace pinpoints mainTitle mismatch despite matching live version and 223ms gap");
    }

    private static void testReverseTraceAndPreConsiderGates() throws Exception {
        JSONObject args = new JSONObject()
                .put("id", "qq:001Mid")
                .put("title", "Song")
                .put("artist", "Artist")
                .put("album", "Album")
                .put("duration", 200000);
        JSONArray songs = new JSONArray()
                .put(new JSONObject().put("id", 0).put("name", "Song").put("dt", 200000))
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
        that(findCandidate(trace, "0", "primary#0").failedGate == MatchTrace.Gate.INVALID_ID,
                "pre-consider invalid ID is traced");
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

    private static void testSanitizationAndNullDuration() throws Exception {
        TraceFake api = new TraceFake();
        api.results.put("Song Artist", new JSONArray()
                .put(qqSong("okMid", "okFile", "Song", "Artist", "Album", 0, null))
                .put(qqSong("noFileMid", "", "Song", "Artist", "Album", 200, null)));
        Track noDurationTarget = Track.fromJson(new JSONObject()
                .put("id", "999").put("title", "Song").put("artist", "Artist")
                .put("album", "Album").put("duration", 0)
                .put("url", "https://secret.example/signed?token=SHOULD_NOT_APPEAR"));
        List<MatchTrace> captured = new ArrayList<>();
        QQSource.lookupAttempts(noDurationTarget, api, "0", captured::add);
        MatchTrace trace = captured.get(0);
        MatchTrace.CandidateRecord okCand = findCandidate(trace, "qq:okMid", "primary");
        MatchTrace.CandidateRecord filtered = findCandidate(trace, "qq:noFileMid", "primary");
        that(okCand != null && okCand.durationGapMs == null,
                "missing duration records null gap instead of Long.MAX_VALUE");
        that(filtered != null && filtered.outcome == MatchTrace.Outcome.FILTERED
                && filtered.failedGate == MatchTrace.Gate.MISSING_MEDIA_MID,
                "missing media_mid is traced as FILTERED");
        String summary = trace.formatSummary();
        that(!summary.contains("SHOULD_NOT_APPEAR") && !summary.contains("vkey=fixture") && !summary.contains("https://"),
                "formatted trace never leaks stream URLs or tokens");
        that(summary.contains("gap=null") && summary.contains("vkey mid=okMid media=okFile result=ok"),
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
