package dev.yesplaymusic.probe;
import org.json.JSONObject;
public final class TrackIdentityTest {
    public static void main(String[] args) throws Exception {
        Track old = Track.fromJson(new JSONObject().put("id","123456").put("provider","qq").put("qqMid","fallbackMid"));
        check("netease".equals(old.provider), "old numeric catalog must migrate to NetEase");
        Track resolved = old.withProviderInfo("qq","resolvedMid");
        check("netease".equals(resolved.provider), "QQ audio fallback must preserve catalog provider");
        check("123456".equals(resolved.id), "fallback must preserve catalog id");
        check("resolvedMid".equals(resolved.qqMid), "fallback lyrics still need resolved QQ MID");
        Track direct = Track.fromJson(new JSONObject().put("id","qq:directMid").put("provider","netease"));
        check("qq".equals(direct.provider), "direct QQ catalog must remain QQ");
        check("qq".equals(direct.withProviderInfo("qq","directMid").provider), "direct QQ stays QQ after resolution");
        check(!resolved.toJson(false).has("url"), "public metadata must exclude ephemeral stream URLs");
        System.out.println("Track identity vectors: 7 passed");
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
