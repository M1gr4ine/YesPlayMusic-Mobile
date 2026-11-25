package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import org.json.JSONArray;
import org.json.JSONObject;

final class TrackTest {
    private TrackTest() {}

    static void run() throws Exception {
        Track legacy = Track.fromJson(new JSONObject().put("id", "123456").put("provider", "qq").put("qqMid", "fallbackMid"));
        that("netease".equals(legacy.provider), "numeric catalog IDs migrate to NetEase");
        Track viaQq = legacy.withVerifiedQqMid("resolvedMid", "resolvedFile");
        that("netease".equals(viaQq.provider) && "123456".equals(viaQq.id), "QQ audio keeps the NetEase catalog identity");
        that("resolvedMid".equals(viaQq.qqMid) && "resolvedFile".equals(viaQq.mediaMid) && viaQq.hasVerifiedQqMid(),
                "verified QQ audio records song and file MIDs");
        that(viaQq.withVerifiedQqMid("resolvedMid", "resolvedFile") == viaQq,
                "repeating the same verified mapping returns this without triggering queue rebuilds");
        that(!viaQq.toJson(false).has("url"), "public metadata excludes the stream URL");

        Track direct = Track.fromJson(new JSONObject().put("id", "qq:directMid").put("provider", "netease"));
        that("qq".equals(direct.provider) && "qq".equals(direct.withVerifiedQqMid("directMid", "directFile").provider),
                "QQ catalog songs stay QQ");

        JSONObject metadata = new JSONObject().put("id", "123456").put("qqMid", "songMid")
                .put("qqMidVersion", 2).put("media_mid", "fileMid").put("albumId", "42")
                .put("artistRefs", new JSONArray().put(new JSONObject().put("id", "7").put("name", "Artist")));
        JSONObject serialized = Track.fromJson(metadata).toJson(false);
        that("fileMid".equals(serialized.optString("mediaMid")), "file MID survives parsing and serialization");
        that(serialized.optInt("qqMidVersion", 0) == 2, "verified mapping version survives serialization");
        that("42".equals(serialized.optString("albumId")) && serialized.optJSONArray("artistRefs") != null,
                "catalog navigation metadata survives serialization");

        Track verified = Track.fromJson(metadata).withVerifiedQqMid("newSong", "newFile");
        that("123456".equals(verified.id) && "netease".equals(verified.provider)
                && "newSong".equals(verified.effectiveQqMid()) && "newFile".equals(verified.mediaMid)
                && verified.hasVerifiedQqMid(), "verified fallback keeps catalog identity and both MIDs");
        Track cleared = verified.clearQqMid();
        that(cleared.qqMid.isEmpty() && cleared.mediaMid.isEmpty() && !cleared.hasVerifiedQqMid()
                && "123456".equals(cleared.id), "clearing a NetEase mapping removes its associated file MID");
        Track old = Track.fromJson(new JSONObject().put("id", "5").put("qqMid", "old"));
        that(!old.hasVerifiedQqMid() && old.qqMidVersion == 0, "legacy fallback mappings need verification");
        Track orphanVersion = Track.fromJson(new JSONObject().put("id", "5").put("qqMidVersion", 2));
        that(!orphanVersion.toJson(false).has("qqMidVersion"), "version is not published without a song MID");
        Track conflict = Track.fromJson(new JSONObject().put("id", "qq:real").put("qqMid", "wrong")
                .put("mediaMid", "wrongFile").put("qqMidVersion", 2));
        that("real".equals(conflict.effectiveQqMid()) && "real".equals(conflict.qqMid)
                && conflict.mediaMid.isEmpty() && !conflict.hasVerifiedQqMid(), "native identity wins over a conflicting cached mapping");
        Track rejected = conflict.withVerifiedQqMid("wrong", "wrongFile");
        that("real".equals(rejected.effectiveQqMid()) && rejected.mediaMid.isEmpty()
                && "real".equals(rejected.clearQqMid().effectiveQqMid()), "native mapping rejects a different song and cannot be cleared");
        that(verified.artistIds.size() == 1 && "7".equals(verified.artistIds.get(0)), "artist IDs derive from refs");
        boolean immutable = false;
        try { verified.artistIds.add("8"); } catch (UnsupportedOperationException expected) { immutable = true; }
        that(immutable, "artist IDs are immutable");

        JSONArray aliases = new JSONArray().put("Sakanaction").put("鱼韵");
        Track aliased = Track.fromJson(new JSONObject().put("id", "1372726248").put("provider", "netease")
                .put("title", "新宝岛").put("artist", "サカナクション").put("artistAliases", aliases));
        JSONObject published = aliased.withVerifiedQqMid("resolvedMid", "resolvedFile").toJson(false);
        that("サカナクション".equals(published.optString("artist")), "aliases never replace the display artist");
        that(aliases.toString().equals(String.valueOf(published.optJSONArray("artistAliases"))),
                "aliases survive parsing and source resolution");
    }
}
