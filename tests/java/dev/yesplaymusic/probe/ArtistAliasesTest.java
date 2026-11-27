package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

final class ArtistAliasesTest {
    private ArtistAliasesTest() {}

    static void run() throws Exception {
        JSONArray artists = new JSONArray().put(new JSONObject().put("name", "サカナクション")
                .put("alias", new JSONArray().put("Sakanaction"))
                .put("alia", new JSONArray().put("Sakanaction"))
                .put("tns", new JSONArray().put("鱼韵"))
                .put("trans", "Fish Rhyme"));
        that(ArtistAliases.fromArtists(artists).equals(Arrays.asList("Sakanaction", "鱼韵", "Fish Rhyme")),
                "extracts deduplicated alternate names across alias fields");

        JSONArray rawAliases = new JSONArray().put("SAKANACTION").put("Sakanaction").put("Fish Rhyme");
        that(ArtistAliases.fromAliasArray(rawAliases, "Sakanaction").equals(Arrays.asList("Fish Rhyme")),
                "fromAliasArray omits the canonical artist and duplicates");

        JSONArray qqSingers = new JSONArray()
                .put(new JSONObject().put("mid", "qqSym").put("name", "¥$"))
                .put(new JSONObject().put("mid", "0025NhlN2yWrP4").put("name", "Ye (侃爷)")
                        .put("alias", new JSONArray().put("Kanye West")))
                .put(new JSONObject().put("id", 0).put("name", "Ty Dolla $ign"));
        List<ArtistAliases.Member> parsed = ArtistAliases.membersFromArtists(qqSingers);
        that(parsed.size() == 3 && "¥$".equals(parsed.get(0).name) && "qqSym".equals(parsed.get(0).providerId),
                "preserves member order, symbol-only names, and provider mid");
        that("0025NhlN2yWrP4".equals(parsed.get(1).providerId)
                        && parsed.get(1).aliases.equals(Arrays.asList("Kanye West")),
                "attaches per-member aliases");
        that(parsed.get(2).providerId.isEmpty() && parsed.get(2).aliases.isEmpty(),
                "drops zero provider id and keeps empty aliases empty");

        JSONArray encoded = ArtistAliases.membersToJson(parsed);
        List<ArtistAliases.Member> roundTripped = ArtistAliases.membersFromJson(encoded);
        that(roundTripped.size() == 3
                        && "Ye (侃爷)".equals(roundTripped.get(1).name)
                        && "0025NhlN2yWrP4".equals(roundTripped.get(1).providerId)
                        && roundTripped.get(1).aliases.equals(Arrays.asList("Kanye West"))
                        && !encoded.getJSONObject(2).has("providerId")
                        && !encoded.getJSONObject(2).has("aliases"),
                "artistMembers round-trips through JSON cleanly");
    }
}
