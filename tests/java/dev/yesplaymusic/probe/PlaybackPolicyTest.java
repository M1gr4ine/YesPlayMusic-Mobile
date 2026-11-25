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
}
