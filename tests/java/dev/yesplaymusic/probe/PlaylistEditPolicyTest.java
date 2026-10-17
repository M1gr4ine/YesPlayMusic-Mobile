package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/** Contract tests for safe account-owned playlist edits. */
final class PlaylistEditPolicyTest {
    private PlaylistEditPolicyTest() { }

    static void run() throws Exception {
        JSONObject owned = new JSONObject().put("id", "700").put("creator", new JSONObject().put("userId", "42"));
        Check.that(PlaylistEditPolicy.isOwnedPlaylist(owned, "42"),
                "the signed-in creator can edit their playlist");
        Check.that(!PlaylistEditPolicy.isOwnedPlaylist(owned, "43"),
                "a different account cannot edit the playlist");
        Check.that(!PlaylistEditPolicy.isOwnedPlaylist(
                new JSONObject().put("id", "703").put("userId", "42"), "42"),
                "ownership requires the provider's creator user ID field");
        Check.that(!PlaylistEditPolicy.isOwnedPlaylist(
                new JSONObject().put("id", "701").put("specialType", 5)
                        .put("creator", new JSONObject().put("userId", "42")), "42"),
                "the liked-songs special playlist is never editable");
        Check.that(!PlaylistEditPolicy.isOwnedPlaylist(
                new JSONObject().put("id", "702").put("subscribed", true)
                        .put("creator", new JSONObject().put("userId", "42")), "42"),
                "a subscribed playlist is not treated as user-owned");

        JSONArray validIds = new JSONArray().put("101").put("202");
        List<String> parsed = PlaylistEditPolicy.requirePositiveIds(validIds, 2);
        Check.that(parsed.equals(Arrays.asList("101", "202")), "batch IDs retain their supplied order");
        expectInvalidIds(new JSONArray().put("0"), 10, "zero is not a song ID");
        expectInvalidIds(new JSONArray().put("101").put("bad"), 10, "malformed batch IDs are rejected");
        expectInvalidIds(new JSONArray(), 10, "an empty batch is rejected");
        expectInvalidIds(new JSONArray().put("101").put("202"), 1, "a batch over its limit is rejected");
        expectInvalidIds(new JSONArray().put("101").put("101"), 10, "duplicate batch IDs are rejected");

        JSONObject detail = new JSONObject().put("trackCount", 3)
                .put("trackIds", new JSONArray()
                        .put(new JSONObject().put("id", "101"))
                        .put(new JSONObject().put("id", "202"))
                        .put(new JSONObject().put("id", "303")))
                .put("tracks", new JSONArray().put(new JSONObject().put("id", "101")));
        List<String> current = PlaylistEditPolicy.completeTrackIds(detail, 3);
        Check.that(current.equals(Arrays.asList("101", "202", "303")),
                "the complete provider track ID list is used when detail metadata is partial");
        Check.that(PlaylistEditPolicy.sameTrackIds(current, Arrays.asList("303", "101", "202")),
                "reorder accepts the same complete set in a new order");
        Check.that(!PlaylistEditPolicy.sameTrackIds(current, Arrays.asList("303", "101")),
                "reorder rejects a partially loaded ID list");
        Check.that(!PlaylistEditPolicy.sameTrackIds(current, Arrays.asList("303", "101", "404")),
                "reorder rejects IDs outside the playlist");
        removalReadbackMustRetainEveryUnselectedTrack();
        expectInvalidDetail(new JSONObject().put("trackCount", 3)
                .put("trackIds", new JSONArray().put(new JSONObject().put("id", "101"))),
                500, "truncated provider track IDs are rejected");
        expectInvalidDetail(detail, 2, "playlists above the supported full-list limit are rejected");
    }

    private static void removalReadbackMustRetainEveryUnselectedTrack() {
        List<String> before = Arrays.asList("11", "22", "33", "44");
        List<String> removed = Arrays.asList("22", "44");
        Check.that(PlaylistEditPolicy.removalMatches(before, removed, Arrays.asList("11", "33")),
                "removal readback confirms selected IDs absent and all other songs retained");
        Check.that(!PlaylistEditPolicy.removalMatches(before, removed, Arrays.asList("11")),
                "removal readback rejects a result that dropped an unselected song");
        Check.that(!PlaylistEditPolicy.removalMatches(before, removed, Arrays.asList("11", "22", "33")),
                "removal readback rejects a selected ID that remains");
        Check.that(!PlaylistEditPolicy.removalMatches(before, removed, Arrays.asList("11", "33", "55")),
                "removal readback rejects an unexpected replacement song");
    }

    private static void expectInvalidIds(JSONArray ids, int limit, String message) {
        try {
            PlaylistEditPolicy.requirePositiveIds(ids, limit);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            Check.that(true, message);
        }
    }

    private static void expectInvalidDetail(JSONObject playlist, int limit, String message) {
        try {
            PlaylistEditPolicy.completeTrackIds(playlist, limit);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            Check.that(true, message);
        }
    }
}
