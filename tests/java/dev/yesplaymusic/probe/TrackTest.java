package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import org.json.JSONArray;
import org.json.JSONObject;

final class TrackTest {
    private TrackTest() {}

    static void run() throws Exception {
        Track legacy = Track.fromJson(new JSONObject().put("id", "123456").put("provider", "qq").put("qqMid", "fallbackMid"));
        that("netease".equals(legacy.provider), "numeric catalog IDs migrate to NetEase");
        Track viaQq = legacy.withQqMid("resolvedMid");
        that("netease".equals(viaQq.provider) && "123456".equals(viaQq.id), "QQ audio keeps the NetEase catalog identity");
        that("resolvedMid".equals(viaQq.qqMid), "QQ audio records the resolved MID for lyrics");
        that(!viaQq.toJson(false).has("url"), "public metadata excludes the stream URL");

        Track direct = Track.fromJson(new JSONObject().put("id", "qq:directMid").put("provider", "netease"));
        that("qq".equals(direct.provider) && "qq".equals(direct.withQqMid("directMid").provider),
                "QQ catalog songs stay QQ");

        JSONArray aliases = new JSONArray().put("Sakanaction").put("鱼韵");
        Track aliased = Track.fromJson(new JSONObject().put("id", "1372726248").put("provider", "netease")
                .put("title", "新宝岛").put("artist", "サカナクション").put("artistAliases", aliases));
        JSONObject published = aliased.withQqMid("resolvedMid").toJson(false);
        that("サカナクション".equals(published.optString("artist")), "aliases never replace the display artist");
        that(aliases.toString().equals(String.valueOf(published.optJSONArray("artistAliases"))),
                "aliases survive parsing and source resolution");
    }
}
