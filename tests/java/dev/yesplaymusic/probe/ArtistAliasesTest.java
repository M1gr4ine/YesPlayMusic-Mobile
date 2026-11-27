package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Provider alias extraction and per-member JSON round trips. */
final class ArtistAliasesTest {
    private static final String ARTIST_KANA = "カタカナバンド";
    private static final String ARTIST_ZH = "中文译名团";

    private ArtistAliasesTest() {}

    static void run() throws Exception {
        JSONArray artists = new JSONArray().put(new JSONObject().put("name", ARTIST_KANA)
                .put("alias", new JSONArray().put("KanaBand")).put("alia", new JSONArray().put("KanaBand"))
                .put("tns", new JSONArray().put(ARTIST_ZH)).put("trans", "Trans Name"));
        that(ArtistAliases.fromArtists(artists).equals(Arrays.asList("KanaBand", ARTIST_ZH, "Trans Name")),
                "extracts deduplicated alternate names only");

        JSONArray singers = new JSONArray()
                .put(new JSONObject().put("mid", "qqSym").put("name", "¥$"))
                .put(new JSONObject().put("mid", "qqLead").put("name", "ShortAlias (别名)").put("alias", "Lead Alias"))
                .put(new JSONObject().put("mid", "qqGuest").put("name", "Guest Gamma"));
        List<ArtistAliases.Member> parsed = ArtistAliases.membersFromArtists(singers);
        that(parsed.size() == 3 && "¥$".equals(parsed.get(0).name) && "qqLead".equals(parsed.get(1).providerId),
                "provider singer[] preserves order, symbol-only names, and provider mid");
        List<ArtistAliases.Member> roundTripped = ArtistAliases.membersFromJson(ArtistAliases.membersToJson(parsed));
        that(roundTripped.size() == 3 && "ShortAlias (别名)".equals(roundTripped.get(1).name)
                        && roundTripped.get(1).aliases.equals(Arrays.asList("Lead Alias"))
                        && roundTripped.get(2).aliases.isEmpty(),
                "artistMembers round-trips through JSON");
    }
}
