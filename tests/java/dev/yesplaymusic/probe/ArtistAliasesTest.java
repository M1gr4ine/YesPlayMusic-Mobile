package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

final class ArtistAliasesTest {
    private static final String NEW_ISLAND = "新宝岛";
    private static final String SAKANACTION = "サカナクション";
    private static final String FISH_RHYME = "鱼韵";
    private static final List<String> ALIASES = list("Sakanaction", FISH_RHYME);

    private ArtistAliasesTest() {}

    static void run() throws Exception {
        that(ArtistAliases.score(NEW_ISLAND, SAKANACTION, ALIASES, 305000,
                NEW_ISLAND, "sakanaction", list(), 306000) > 140, "requested aliases match the QQ credit");
        that(ArtistAliases.score("多分、風。", SAKANACTION, ALIASES, 292000,
                "多分、風。 (或许，是风。)", "sakanaction", list(), 294000) > 140, "aliases match a translated subtitle");
        that(ArtistAliases.score(NEW_ISLAND, "sakanaction", list(), 305000,
                NEW_ISLAND, SAKANACTION, ALIASES, 306000) > 140, "candidate aliases match too");
        that(ArtistAliases.score(NEW_ISLAND, SAKANACTION, ALIASES, 305000,
                NEW_ISLAND, "Cover Artist", list(), 306000) < 0, "an unrelated artist stays unmatched");
        that(ArtistAliases.score(NEW_ISLAND, SAKANACTION, ALIASES, 305000,
                NEW_ISLAND + " (Live)", "sakanaction", list(), 306000) < 0, "aliases keep the version guard");
        that(ArtistAliases.score(NEW_ISLAND, SAKANACTION, ALIASES, 305000,
                NEW_ISLAND, "sakanaction", list(), 314001) < 0, "aliases keep the duration guard");
        that(ArtistAliases.score(NEW_ISLAND, "Artist A / Artist B", list("Sakanaction"), 305000,
                NEW_ISLAND, "Sakanaction", list(), 306000) < 0, "a member alias cannot stand in for a collaboration");
        that(ArtistAliases.score(NEW_ISLAND, SAKANACTION, list("Sakanaction"), 305000,
                NEW_ISLAND, "Sakanaction / Guest", list("Sakanaction"), 306000) < 0, "nor for a candidate collaboration");

        JSONArray artists = new JSONArray().put(new JSONObject().put("name", SAKANACTION)
                .put("alias", new JSONArray().put("Sakanaction")).put("alia", new JSONArray().put("Sakanaction"))
                .put("tns", new JSONArray().put(FISH_RHYME)).put("trans", "Fish Rhyme"));
        that(ArtistAliases.fromArtists(artists).equals(list("Sakanaction", FISH_RHYME, "Fish Rhyme")),
                "extracts deduplicated alternate names only");

        JSONObject response = new JSONObject().put("result", new JSONObject().put("songs", new JSONArray()
                .put(song("999", "Wrong Artist", "wrong alias"))
                .put(song("1372726248", SAKANACTION, FISH_RHYME))));
        that(ArtistAliases.fromExactNeteaseSong(response, "1372726248").equals(list(FISH_RHYME)),
                "alias recovery uses only the exact catalog song");
        that(ArtistAliases.fromExactNeteaseSong(response, "888").isEmpty(), "alias recovery fails closed");

        that(ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION, ALIASES)
                .equals(list(NEW_ISLAND + " Sakanaction", NEW_ISLAND + " " + FISH_RHYME)), "one retry per alias");
        that(ArtistAliases.searchQueries(NEW_ISLAND, "Sakanaction", list("SAKANACTION", "Sakanaction", "Fish Rhyme"))
                .equals(list(NEW_ISLAND + " Fish Rhyme")), "retries skip the canonical name and duplicates");
        that(ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION, list("Sakanaction", FISH_RHYME, "Third")).size() == 2,
                "retries are capped at two");
        that(ArtistAliases.searchQueries(NEW_ISLAND, "Artist A / Artist B", list("Sakanaction")).isEmpty(),
                "collaborations are never split into member queries");
        that(ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION, list("Sakanaction / Guest", FISH_RHYME))
                .equals(list(NEW_ISLAND + " " + FISH_RHYME)), "collaboration aliases are dropped whole");
        that(ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION, list()).isEmpty(), "no bare-title fallback");
    }

    private static JSONObject song(String id, String artist, String alias) throws Exception {
        return new JSONObject().put("id", id).put("ar", new JSONArray()
                .put(new JSONObject().put("name", artist).put("tns", new JSONArray().put(alias))));
    }

    private static List<String> list(String... values) { return Arrays.asList(values); }
}
