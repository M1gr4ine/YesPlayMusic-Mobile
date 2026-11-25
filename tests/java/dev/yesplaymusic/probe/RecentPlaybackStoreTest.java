package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;

final class RecentPlaybackStoreTest {
    private RecentPlaybackStoreTest() {}

    static void run() throws Exception {
        List<RecentPlaybackStore.Entry> history = Arrays.asList(
                entry("123", "netease", "old title", 100L),
                entry("123", "qq", "QQ catalog", 200L),
                entry("456", "netease", "other", 250L));
        List<RecentPlaybackStore.Entry> merged = RecentPlaybackStore.merge(history,
                entry("123", "netease", "new title", 300L), 100);
        that(merged.size() == 3, "same provider and id are deduplicated");
        that(merged.get(0).key().equals("netease:123") && "new title".equals(merged.get(0).title),
                "the replay moves to the top with fresh metadata");
        that(merged.get(1).key().equals("netease:456") && merged.get(2).key().equals("qq:123"),
                "the rest stay newest-first and the same id in another catalog is distinct");

        List<RecentPlaybackStore.Entry> oversized = new ArrayList<>();
        for (int i = 0; i < 105; i++) oversized.add(entry(String.valueOf(i), "netease", "track " + i, i + 1L));
        List<RecentPlaybackStore.Entry> capped = RecentPlaybackStore.merge(oversized, null, 100);
        that(capped.size() == 100 && capped.get(0).key().equals("netease:104"), "history keeps the newest 100");

        Track track = Track.fromJson(new JSONObject().put("id", "789").put("title", "Title").put("provider", "netease")
                .put("url", "https://audio.example/play?signature=fixture-only-secret"));
        Track mapped = Track.fromJson(new JSONObject().put("id", "789").put("artist", "Artist")
                .put("artistAliases", new org.json.JSONArray().put("Alias"))
                .put("artistRefs", new org.json.JSONArray().put(new JSONObject().put("id", "7").put("name", "Artist")))
                .put("albumId", "42").put("qqMid", "songMid").put("mediaMid", "fileMid").put("qqMidVersion", 2));
        JSONObject saved = RecentPlaybackStore.fromTrack(mapped, 500L).toJson();
        List<RecentPlaybackStore.Entry> loaded = RecentPlaybackStore.decode("[" + saved.toString() + "]");
        that(loaded.size() == 1, "stored history decodes its entry");
        Track restored = Track.fromJson(loaded.get(0).toJson());
        that("fileMid".equals(restored.toJson(false).optString("mediaMid"))
                && restored.toJson(false).optInt("qqMidVersion", 0) == 2, "history preserves verified mapping");
        that(restored.toJson(false).optJSONArray("artistAliases") != null
                && restored.toJson(false).optJSONArray("artistRefs") != null
                && "42".equals(restored.toJson(false).optString("albumId")), "history preserves catalog metadata");

        List<RecentPlaybackStore.Entry> legacyEntries = RecentPlaybackStore.decode(
                "[{\"id\":\"777\",\"provider\":\"netease\",\"title\":\"Old\",\"lastPlayedAt\":100}]");
        that(legacyEntries.size() == 1, "legacy history without optional fields decodes");
        Track legacyRestored = Track.fromJson(legacyEntries.get(0).toJson());
        that("netease".equals(legacyRestored.provider) && !legacyRestored.hasVerifiedQqMid()
                && legacyRestored.mediaMid.isEmpty() && legacyRestored.artistIds.isEmpty(),
                "old history with missing metadata remains readable and unverified");

        String persisted = RecentPlaybackStore.fromTrack(track, 500L).toJsonString();
        that(!persisted.contains("\"url\"") && !persisted.contains("fixture-only-secret"), "signed stream URLs are never stored");
    }

    private static RecentPlaybackStore.Entry entry(String id, String provider, String title, long playedAt) {
        return new RecentPlaybackStore.Entry(id, provider, title, "Artist", "Album", "", 180000L, "", playedAt);
    }
}
