package dev.yesplaymusic.probe;

import java.util.Arrays;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ArtistAliasesTest {
    private static final String NEW_ISLAND = "\u65B0\u5B9D\u5C9B";
    private static final String SAKANACTION = "\u30B5\u30AB\u30CA\u30AF\u30B7\u30E7\u30F3";
    private static final String FISH_RHYME = "\u9C7C\u97F5";
    private static final String WIND = "\u591A\u5206\u3001\u98A8\u3002";
    private static final String LIGHT_DANCE = "\u30E9\u30A4\u30C8\u30C0\u30F3\u30B9";

    public static void main(String[] args) throws Exception {
        int cases = 0;
        check(score(NEW_ISLAND, SAKANACTION, list("Sakanaction", FISH_RHYME), 305000,
                NEW_ISLAND, "sakanaction", list(), 306000) > 140,
                "NetEase translated artist alias should match the QQ song");
        cases++;
        check(score(WIND, SAKANACTION, list("Sakanaction", FISH_RHYME), 292000,
                WIND + " (\u6216\u8BB8\uFF0C\u662F\u98CE\u3002)", "sakanaction", list(), 294000) > 140,
                "artist aliases should match a QQ title with a translated subtitle");
        cases++;
        check(score(LIGHT_DANCE, SAKANACTION, list("Sakanaction", FISH_RHYME), 212122,
                LIGHT_DANCE, "sakanaction", list(), 212000) > 140,
                "artist aliases should match the same Japanese title");
        cases++;
        check(score(NEW_ISLAND, "sakanaction", list(), 305000,
                NEW_ISLAND, SAKANACTION, list("Sakanaction", FISH_RHYME), 306000) > 140,
                "NetEase candidate aliases should match a QQ favorite using the other catalog name");
        cases++;
        check(score(NEW_ISLAND, SAKANACTION, list("Sakanaction", FISH_RHYME), 305000,
                NEW_ISLAND, "Cover Artist", list(), 306000) < 0,
                "an unrelated artist must remain unmatched");
        cases++;
        check(score(NEW_ISLAND, SAKANACTION, list("Sakanaction"), 305000,
                NEW_ISLAND + " (Live)", "sakanaction", list(), 306000) < 0,
                "alias matches must still reject another version");
        cases++;
        check(score(NEW_ISLAND, SAKANACTION, list("Sakanaction"), 305000,
                NEW_ISLAND, "sakanaction", list(), 314001) < 0,
                "alias matches must still reject a duration difference above eight seconds");
        cases++;
        check(score(NEW_ISLAND, "Artist A / Artist B", list("Sakanaction"), 305000,
                NEW_ISLAND, "Sakanaction", list(), 306000) < 0,
                "a member alias must not stand in for an entire collaboration credit");
        cases++;
        check(score(NEW_ISLAND, SAKANACTION, list("Sakanaction"), 305000,
                NEW_ISLAND, "Sakanaction / Guest", list("Sakanaction"), 306000) < 0,
                "candidate collaboration credits must also reject single-member aliases");
        cases++;

        JSONArray artists = new JSONArray().put(new JSONObject()
                .put("name", SAKANACTION)
                .put("alias", new JSONArray().put("Sakanaction"))
                .put("alia", new JSONArray().put("Sakanaction"))
                .put("tns", new JSONArray().put(FISH_RHYME))
                .put("trans", "Fish Rhyme"));
        List<String> extracted = fromArtists(artists);
        check(extracted.size() == 3 && extracted.containsAll(list("Sakanaction", FISH_RHYME, "Fish Rhyme"))
                && !extracted.contains(SAKANACTION),
                "only deduplicated alternate names should be extracted for matching");
        cases++;

        JSONObject wrongSong = new JSONObject().put("id", "999").put("ar", new JSONArray()
                .put(new JSONObject().put("name", "Wrong Artist")
                        .put("tns", new JSONArray().put("wrong alias"))));
        JSONObject exactSong = new JSONObject().put("id", "1372726248").put("ar", new JSONArray()
                .put(new JSONObject().put("name", SAKANACTION)
                        .put("tns", new JSONArray().put(FISH_RHYME))));
        JSONObject response = new JSONObject().put("result", new JSONObject()
                .put("songs", new JSONArray().put(wrongSong).put(exactSong)));
        List<String> exact = exactNeteaseAliases(response, "1372726248");
        check(exact.equals(list(FISH_RHYME)),
                "old queues must recover aliases only from the exact catalog song id");
        cases++;
        check(exactNeteaseAliases(response, "888").isEmpty(),
                "alias recovery must fail closed when the exact catalog ID was not returned");
        cases++;

        List<String> queries = ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION,
                list("Sakanaction", FISH_RHYME));
        check(queries.equals(list(NEW_ISLAND + " Sakanaction", NEW_ISLAND + " " + FISH_RHYME)),
                "search retries should use each verified alternate name with the title");
        cases++;
        check(ArtistAliases.searchQueries(NEW_ISLAND, "Sakanaction",
                list("SAKANACTION", "Sakanaction", "Fish Rhyme"))
                .equals(list(NEW_ISLAND + " Fish Rhyme")),
                "search retries should skip aliases equal to the canonical name and deduplicate");
        cases++;
        check(ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION,
                list("Sakanaction", FISH_RHYME, "Fish Rhyme", "Alternate Four"))
                .equals(list(NEW_ISLAND + " Sakanaction", NEW_ISLAND + " " + FISH_RHYME)),
                "search retries should be capped at two aliases");
        cases++;
        check(ArtistAliases.searchQueries(NEW_ISLAND, "Artist A / Artist B", list("Sakanaction")).isEmpty(),
                "search retries must not split a collaboration into individual artist aliases");
        cases++;
        check(ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION,
                list("Sakanaction / Guest", FISH_RHYME)).equals(list(NEW_ISLAND + " " + FISH_RHYME)),
                "collaboration aliases must be discarded as a whole, not split into member queries");
        cases++;
        check(ArtistAliases.searchQueries(NEW_ISLAND, SAKANACTION, list()).isEmpty(),
                "search retries must not fall back to a bare title");
        cases++;

        System.out.println("Artist alias vectors: " + cases + " passed");
    }

    private static int score(String title, String artist, List<String> artistAliases, long duration,
                             String candidateTitle, String candidateArtist, List<String> candidateAliases,
                             long candidateDuration) throws Exception {
        return ArtistAliases.score(title, artist, artistAliases, duration,
                candidateTitle, candidateArtist, candidateAliases, candidateDuration);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fromArtists(JSONArray artists) throws Exception {
        return ArtistAliases.fromArtists(artists);
    }

    @SuppressWarnings("unchecked")
    private static List<String> exactNeteaseAliases(JSONObject response, String id) throws Exception {
        return ArtistAliases.fromExactNeteaseSong(response, id);
    }

    private static List<String> list(String... values) { return Arrays.asList(values); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
