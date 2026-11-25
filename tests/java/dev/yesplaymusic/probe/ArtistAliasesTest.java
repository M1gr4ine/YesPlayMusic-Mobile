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

        that(ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list("Ye"), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Ye (侃爷)", list(), "The Life Of Pablo (Explicit)", 236120, true, 0), "album bridge accepts an evidenced member identity");
        that(ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list(), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Ye (侃爷)", list(), "The Life Of Pablo (Explicit)", 236120, true, 0), "distinctive album and bilingual candidate bridge without fetched aliases");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list(), "Greatest Hits", 236000,
                "FML", "Ye (侃爷)", list(), "Greatest Hits", 236120, true, 0), "generic compilation cannot justify album bridge");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list("Ye"), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Someone Else", list(), "The Life Of Pablo", 236120, true, 0), "album bridge rejects contradictory Latin identity");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list("Ye"), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Ye (Taylor Swift)", list(), "The Life Of Pablo", 236120, true, 0), "bridge rejects conflicting visible same-script names");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list("Ye"), "The Life Of Pablo", 236000,
                "FML", "Ye / Stranger", list(), "The Life Of Pablo", 236120, true, 0), "bridge rejects an ambiguous candidate collaboration");
        that(ArtistAliases.bridgeAccepts("First Love", "宇多田ヒカル", list(), "", 257000,
                "First Love", "宇多田光", list(), "", 257100, true, 0), "top artist query bridges cross-script credit");
        that(!ArtistAliases.bridgeAccepts("First Love", "宇多田ヒカル", list(), "", 257000,
                "First Love", "宇多田光", list(), "", 257100, true, 1), "lower ranked cross-script credit is ambiguous");
        that(!ArtistAliases.bridgeAccepts("First Love", "宇多田ヒカル / Guest", list(), "", 257000,
                "First Love", "宇多田光", list(), "", 257100, true, 0), "cross-script bridge rejects collaborations");
        that(!ArtistAliases.bridgeAccepts("First Love", "宇多田ヒカル", list(), "", 257000,
                "First Love", "宇多田光", list(), "", 262001, true, 0), "bridge enforces duration boundary");
        that(ArtistAliases.score("Song", "Artist", list(), "Album", 200000,
                "Song", "Artist", list(), "Album", 200000) == 200, "alias album overload adds album evidence");

        that(ArtistAliases.score("Song", "Unknown Artist", list("Artist"), 200000,
                "Song", "Artist", list(), 200000) < 0, "aliases cannot rescue an unknown display credit");

        that(ArtistAliases.score("FML", "Kanye West", list("ye"), 236000,
                "FML (Explicit)", "Ye (侃爷)", list(), 240000) >= 140, "official solo alias matches base of bilingual credit with a four-second gap");
        that(ArtistAliases.score("FML", "Kanye West", list("ye"), 0,
                "FML (Explicit)", "Ye (侃爷)", list(), 0) >= 140, "known alias and benign title match without duration");
        that(ArtistAliases.score("FML", "Kanye West", list("ye"), 236000,
                "FML (Live)", "Ye (侃爷)", list(), 240000) < 0, "known alias cannot bypass version mismatch");

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
