package dev.yesplaymusic.probe;

import org.json.JSONArray;
import org.json.JSONObject;

public final class TrackIdentityTest {
    public static void main(String[] args) throws Exception {
        Track old = Track.fromJson(new JSONObject().put("id", "123456").put("provider", "qq").put("qqMid", "fallbackMid"));
        check("netease".equals(old.provider), "old numeric catalog must migrate to NetEase");
        Track resolved = old.withProviderInfo("qq", "resolvedMid");
        check("netease".equals(resolved.provider), "QQ audio fallback must preserve catalog provider");
        check("123456".equals(resolved.id), "fallback must preserve catalog id");
        check("resolvedMid".equals(resolved.qqMid), "fallback lyrics still need resolved QQ MID");
        Track direct = Track.fromJson(new JSONObject().put("id", "qq:directMid").put("provider", "netease"));
        check("qq".equals(direct.provider), "direct QQ catalog must remain QQ");
        check("qq".equals(direct.withProviderInfo("qq", "directMid").provider), "direct QQ stays QQ after resolution");
        check(!resolved.toJson(false).has("url"), "public metadata must exclude ephemeral stream URLs");

        JSONArray aliases = new JSONArray().put("Sakanaction").put("\u9C7C\u97F5");
        String artist = "\u30B5\u30AB\u30CA\u30AF\u30B7\u30E7\u30F3";
        Track aliased = Track.fromJson(new JSONObject().put("id", "1372726248")
                .put("provider", "netease").put("title", "\u65B0\u5B9D\u5C9B")
                .put("artist", artist).put("artistAliases", aliases));
        JSONObject publicTrack = aliased.withProviderInfo("qq", "resolvedMid").toJson(false);
        check(artist.equals(publicTrack.optString("artist")), "aliases must not replace the display artist");
        JSONArray savedAliases = publicTrack.optJSONArray("artistAliases");
        check(savedAliases != null && savedAliases.length() == 2
                && "Sakanaction".equals(savedAliases.optString(0))
                && "\u9C7C\u97F5".equals(savedAliases.optString(1)),
                "aliases must survive Track parsing and source resolution");
        System.out.println("Track identity vectors: 9 passed");
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
