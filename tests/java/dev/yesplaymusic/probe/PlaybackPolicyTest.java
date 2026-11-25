package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class PlaybackPolicyTest {
    private PlaybackPolicyTest() {}

    static void run() throws Exception {
        testSelectFilteredIndexRemapsAfterInvalidRows();
        testChooseAutoFailureCodePrioritizesActionableErrors();
        testBuildLyricTargetsAndTransientStripping();
    }

    private static void testSelectFilteredIndexRemapsAfterInvalidRows() throws Exception {
        JSONArray requested = new JSONArray()
                .put(new JSONObject().put("title", "Missing ID"))
                .put(new JSONObject().put("id", "409736441").put("title", "FML").put("artist", "Kanye West"))
                .put(new JSONObject().put("id", "999").put("title", "Other").put("artist", "Other"));
        List<Track> kept = new ArrayList<>();
        int mapped = PlaybackService.selectFilteredIndex(requested, 1, kept);
        that(kept.size() == 2, "invalid row is filtered");
        that(mapped == 0 && "409736441".equals(kept.get(mapped).id),
                "clicking index 1 after an invalid row still plays FML, not Other");

        List<Track> keptInvalidClick = new ArrayList<>();
        int mappedInvalid = PlaybackService.selectFilteredIndex(requested, 0, keptInvalidClick);
        that(mappedInvalid == 0 && "409736441".equals(keptInvalidClick.get(mappedInvalid).id),
                "clicking an invalid row falls forward to the next valid song");
    }

    private static void testChooseAutoFailureCodePrioritizesActionableErrors() {
        that("qq_auth_expired".equals(PlaybackService.chooseAutoFailureCode("netease", "trial_unavailable", "qq_auth_expired")),
                "expired QQ auth beats generic fallback");
        that("netease_auth_expired".equals(PlaybackService.chooseAutoFailureCode("netease", "netease_auth_expired", "match_not_found")),
                "expired NetEase auth is surfaced");
        that("match_not_found".equals(PlaybackService.chooseAutoFailureCode("netease", "trial_unavailable", "match_not_found")),
                "unmatched fallback tells user no matching version was found");
        that("qq_region_locked".equals(PlaybackService.chooseAutoFailureCode("netease", "netease_rights_unavailable", "qq_region_locked")),
                "QQ region lock is surfaced");
        that("trial_unavailable".equals(PlaybackService.chooseAutoFailureCode("netease", "trial_unavailable", "qq_rights_unavailable")),
                "trial + QQ rights failure preserves trial reason");
        that("network_error".equals(PlaybackService.chooseAutoFailureCode("qq", "network_error", "stream_unavailable")),
                "network error is preserved");
    }

    private static void testBuildLyricTargetsAndTransientStripping() throws Exception {
        Track neteaseWithLegacyMid = Track.fromJson(new JSONObject()
                .put("id", "2640855415")
                .put("provider", "netease")
                .put("title", "Like Him")
                .put("artist", "Tyler, The Creator")
                .put("qqMid", "000wrongLegacy")
                .put("qqMidVersion", 0));

        // Opening QQ stream with verified activeAttempt exposes [netease catalog, verified qq] and ignores unverified legacy MID.
        List<PlaybackService.LyricTarget> targets = PlaybackService.buildLyricTargets(
                neteaseWithLegacyMid, "qq", "001iCu2X0UNowl", "", true);
        that(targets.size() == 2, "expected 2 ordered lyric targets");
        that("netease:2640855415".equals(targets.get(0).key()), "catalog NetEase ID comes first");
        that("qq:001iCu2X0UNowl".equals(targets.get(1).key()), "verified active QQ MID comes second");

        // Unverified active QQ attempt or numeric ID as QQ MID must never be emitted.
        List<PlaybackService.LyricTarget> unverifiedTargets = PlaybackService.buildLyricTargets(
                neteaseWithLegacyMid, "qq", "2640855415", "", false);
        that(unverifiedTargets.size() == 1 && "netease:2640855415".equals(unverifiedTargets.get(0).key()),
                "numeric or unverified QQ MID is excluded");

        // QQ catalog track bridged to NetEase stream exposes [qq catalog, matched neteaseId].
        Track qqTrack = Track.fromJson(new JSONObject()
                .put("id", "qq:001iCu2X0UNowl")
                .put("provider", "qq")
                .put("title", "Like Him")
                .put("artist", "Tyler, The Creator")
                .put("mediaMid", "003mediaOnlyMid"));
        List<PlaybackService.LyricTarget> qqBridged = PlaybackService.buildLyricTargets(
                qqTrack, "netease", "", "2640855415", false);
        that(qqBridged.size() == 2, "QQ catalog bridged to NetEase has 2 targets");
        that("qq:001iCu2X0UNowl".equals(qqBridged.get(0).key()), "QQ catalog MID comes first");
        that("netease:2640855415".equals(qqBridged.get(1).key()), "matched NetEase ID comes second");

        // Transient live-only lyricsTargets must be stripped before saving to SharedPreferences.
        JSONObject live = new JSONObject()
                .put("source", "qq")
                .put("lyricsTargets", PlaybackService.lyricTargetsJson(targets));
        that(live.has("lyricsTargets") && live.getJSONArray("lyricsTargets").length() == 2,
                "live state carries lyricsTargets");
        PlaybackService.stripTransientStateFields(live);
        that(!live.has("lyricsTargets") && "qq".equals(live.optString("source")),
                "saved state strips transient lyricsTargets while keeping durable fields");
    }
}
