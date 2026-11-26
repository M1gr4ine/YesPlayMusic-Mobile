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

        List<ArtistAliases.Member> fmlMembers = members(
                member("Kanye West", "37520", "Ye"),
                member("The Weeknd", "2"));
        that(ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", fmlMembers, list(), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Ye (侃爷)", members(), list(), "The Life Of Pablo (Explicit)", 236120, true, 0),
                "album bridge accepts an evidenced lead member identity");
        that(!ArtistAliases.bridgeAccepts("Song", "Artist A / Artist B", list("Guest Alias"), "Short", 200000,
                "Song", "Guest Alias", list(), "Short", 200000, true, 0),
                "unattributed flat alias on a collaboration cannot justify album bridge");
        that(ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list(), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Ye (侃爷)", list(), "The Life Of Pablo (Explicit)", 236120, true, 0), "distinctive album and bilingual candidate bridge without fetched aliases");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", list(), "Greatest Hits", 236000,
                "FML", "Ye (侃爷)", list(), "Greatest Hits", 236120, true, 0), "generic compilation cannot justify album bridge");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", fmlMembers, list(), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Someone Else", members(), list(), "The Life Of Pablo", 236120, true, 0), "album bridge rejects contradictory Latin identity");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", fmlMembers, list(), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Ye (Taylor Swift)", members(), list(), "The Life Of Pablo", 236120, true, 0), "bridge rejects conflicting visible same-script names");
        that(!ArtistAliases.bridgeAccepts("FML", "Kanye West / The Weeknd", fmlMembers, list(), "The Life Of Pablo", 236000,
                "FML", "Ye / Stranger", members(), list(), "The Life Of Pablo", 236120, true, 0), "bridge rejects an ambiguous candidate collaboration");
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

        structuredMemberMatching();
        evidencedBridgeAndConflictGuards();
        structuredSerializationAndQueries();
    }

    private static void structuredMemberMatching() {
        List<ArtistAliases.Member> kanyeRihanna = members(
                member("Kanye West", "37520", "ye", "坎耶·维斯特"),
                member("Rihanna", "72724"));
        List<ArtistAliases.Member> yeRihanna = members(
                member("Ye (侃爷)", "0025NhlN2yWrP4"),
                member("Rihanna", "001Ri"));
        that(ArtistAliases.score("Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous", "Ye (侃爷) / Rihanna", yeRihanna, list(), "Famous", 195000) >= 140,
                "per-member official alias matches Famous collaboration forward");
        that(ArtistAliases.score("Famous", "Ye (侃爷) / Rihanna", yeRihanna, list(), "Famous", 195000,
                "Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040) >= 140,
                "per-member official alias matches Famous collaboration in reverse");
        that(ArtistAliases.score("Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous", "Ye. (侃爷) / Rihanna", members(member("Ye. (侃爷)", ""), member("Rihanna", "")), list(),
                "Famous", 195000) >= 140, "Ye. with period matches through member alias");
        that(ArtistAliases.score("Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous", "Kanye West / Rihanna", members(), list(), "Famous", 30000) < 0,
                "30-second snippet stays rejected");
        that(ArtistAliases.score("Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous", "Ye (侃爷) / Stranger", members(member("Ye (侃爷)", ""), member("Stranger", "")), list(),
                "The Life Of Pablo", 196000) < 0, "mismatched collaborator is rejected");
        that(ArtistAliases.score("Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous", "Someone Else / Rihanna", members(member("Someone Else", ""), member("Rihanna", "")), list(),
                "The Life Of Pablo", 196000) < 0, "shared guest cannot override different lead");
        that(ArtistAliases.score("FML", "Kanye West", members(member("Kanye West", "37520", "ye")), list(),
                "The Life Of Pablo", 236000, "FML", "Ye (Taylor Swift)", members(), list(),
                "The Life Of Pablo", 236000) < 0, "strict score rejects conflicting same-script bracketed name");

        // Official single member with internal separator ("A & B", "AC/DC", "Tyler, The Creator") keeps member count 1,
        // while conflicting explicit display grouping ("A / B" paired with [Member("A & B")]) is rejected.
        List<ArtistAliases.Member> duoMember = members(member("A & B", "10"));
        List<ArtistAliases.Member> twoArtists = members(member("A", "11"), member("B", "12"));
        that(ArtistAliases.score("Song", "A & B", duoMember, list(), "Album", 200000,
                "Song", "A / B", twoArtists, list(), "Album", 200000) < 0,
                "single structured duo A & B cannot match two-artist credit A / B");
        that(ArtistAliases.score("Song", "A / B", twoArtists, list(), "Album", 200000,
                "Song", "A & B", duoMember, list(), "Album", 200000) < 0,
                "two-artist credit A / B cannot match single structured duo A & B");
        that(ArtistAliases.score("Song", "A / B", duoMember, list(), "Album", 200000,
                "Song", "A & B", duoMember, list(), "Album", 200000) < 0,
                "display credit A / B with conflicting single member A & B cannot match A & B");
        that(ArtistAliases.score("Song", "A & B", duoMember, list(), "Album", 200000,
                "Song", "A & B", duoMember, list(), "Album", 200000) >= 140,
                "matching single structured duo A & B succeeds");
        List<ArtistAliases.Member> acdc = members(member("AC/DC", "30", "埃西迪西"));
        that(ArtistAliases.score("Back In Black", "AC/DC", acdc, list(), "Back In Black", 255000,
                "Back In Black", "AC/DC", members(member("AC/DC", "qq30")), list(), "Back In Black", 255000) >= 140,
                "single structured band name AC/DC is preserved");
        that(ArtistAliases.score("Back In Black", "AC/DC", acdc, list(), "Back In Black", 255000,
                "Back In Black", "埃西迪西", members(member("埃西迪西", "qq30")), list(), "Back In Black", 255000) >= 140,
                "single structured band name AC/DC retains its official alias");
        that(ArtistAliases.score("Back In Black", "AC/DC", acdc, list(), "Back In Black", 255000,
                "Back In Black", "AC / DC", members(member("AC", "1"), member("DC", "2")), list(), "Back In Black", 255000) < 0,
                "single structured band AC/DC cannot match two-artist credit AC / DC");
        that(ArtistAliases.score("Like Him", "Tyler, The Creator",
                members(member("Tyler, The Creator", "50", "造物主泰勒")), list(), "CHROMAKOPIA", 278000,
                "Like Him", "造物主泰勒", members(member("造物主泰勒", "qq50")), list(), "CHROMAKOPIA", 278000) >= 140,
                "single structured artist with comma uses official member alias");

        // CARNIVAL: ¥$ is preserved at index 0; Kanye West is member #2 with alias ye.
        List<ArtistAliases.Member> carnivalNetease = members(
                member("¥$", "99001"),
                member("Kanye West", "37520", "ye", "坎耶·维斯特"),
                member("Ty Dolla $ign", "88001"));
        List<ArtistAliases.Member> carnivalQq = members(
                member("¥$", "qq0"),
                member("Ye (侃爷)", "qq1"),
                member("Ty Dolla $ign", "qq2"));
        that(ArtistAliases.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", carnivalNetease, list(),
                "VULTURES 1", 264000, "CARNIVAL", "¥$ / Ye (侃爷) / Ty Dolla $ign", carnivalQq, list(),
                "VULTURES 1", 264000) >= 140, "CARNIVAL 3-member credit matches with symbol artist preserved");
        that(ArtistAliases.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", carnivalNetease, list(),
                "VULTURES 1", 264000, "CARNIVAL", "Ye (侃爷) / Ty Dolla $ign", members(), list(),
                "VULTURES 1", 264000) < 0, "CARNIVAL rejects candidate that drops ¥$");
        that(ArtistAliases.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", carnivalNetease, list(),
                "VULTURES 1", 264000, "CARNIVAL",
                "¥$ / Ye (侃爷) / Ty Dolla $ign / Rich The Kid / Playboi Carti", members(), list(),
                "VULTURES 1", 264000) < 0, "CARNIVAL rejects 5-member credit against 3-member target");
        that(ArtistAliases.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", carnivalNetease, list(),
                "VULTURES 1", 264000, "CARNIVAL (Acapella)", "¥$ / Ye (侃爷) / Ty Dolla $ign", carnivalQq, list(),
                "VULTURES 1", 264000) < 0, "CARNIVAL rejects acapella version");
        that(ArtistAliases.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", carnivalNetease, list(),
                "VULTURES 1", 264000, "CARNIVAL (HOOLIGANS VERSION)", "¥$ / Ye (侃爷) / Ty Dolla $ign", carnivalQq, list(),
                "VULTURES 1", 264000) < 0, "CARNIVAL rejects HOOLIGANS VERSION");

        // Contradictory or partial structured members can never overwrite or drop the visible artist credit.
        that(ArtistAliases.score("Famous", "Someone Else", members(member("Kanye West", "37520", "ye")), list(),
                "The Life Of Pablo", 196000, "Famous", "Ye (侃爷)", members(), list(),
                "The Life Of Pablo", 196000) < 0, "contradictory structured member cannot override visible artist");
        that(ArtistAliases.score("Famous", "Kanye West / Rihanna", members(member("Kanye West", "37520", "ye")), list(),
                "The Life Of Pablo", 196000, "Famous", "Ye (侃爷)", members(), list(),
                "The Life Of Pablo", 196000) < 0, "partial structured members cannot drop visible collaborator");
        that(ArtistAliases.score("Famous", "Kanye West / Rihanna",
                members(member("Kanye West", "37520", "ye"), member("Wrong Artist", "99", "rihanna")), list(),
                "The Life Of Pablo", 196000, "Famous", "Ye (侃爷) / Rihanna", yeRihanna, list(),
                "The Life Of Pablo", 196000) < 0, "mismatched member entry invalidates structured aliases");
    }

    private static void evidencedBridgeAndConflictGuards() {
        List<ArtistAliases.Member> kanyeRihanna = members(
                member("Kanye West", "37520", "ye", "坎耶·维斯特"),
                member("Rihanna", "72724"));
        List<ArtistAliases.Member> yeSolo = members(member("Ye (侃爷)", "0025NhlN2yWrP4"));

        that(ArtistAliases.evidencedLeadAlbumBridge(
                "Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous (Explicit)", "Ye (侃爷)", yeSolo, list(), "The Life Of Pablo (Explicit)", 196000),
                "forward evidenced album bridge accepts solo lead on same album");
        that(ArtistAliases.evidencedLeadAlbumBridge(
                "Famous (Explicit)", "Ye (侃爷)", yeSolo, list(), "The Life Of Pablo (Explicit)", 196000,
                "Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040),
                "reverse evidenced album bridge accepts QQ solo lead against NetEase collaboration");
        that(ArtistAliases.evidencedLeadAlbumBridge(
                "Bound 2", "Kanye West / Charlie Wilson",
                members(member("Kanye West", "37520", "ye"), member("Charlie Wilson", "2")), list(), "Yeezus", 229253,
                "Bound 2 (Album Version|Explicit)", "Ye (侃爷)", yeSolo, list(), "Yeezus (Explicit)", 229000),
                "evidenced album bridge accepts short real studio album Yeezus");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Runaway", "Kanye West / Pusha T",
                members(member("Kanye West", "37520", "ye"), member("Pusha T", "3")), list(),
                "My Beautiful Dark Twisted Fantasy", 548200,
                "Runaway", "Ye (侃爷)", yeSolo, list(),
                "My Beautiful Dark Twisted Fantasy", 339000),
                "Runaway album version rejects 339s single edit");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Stronger", "Kanye West / Daft Punk",
                members(member("Kanye West", "37520", "ye"), member("Daft Punk", "4")), list(), "Graduation", 311000,
                "Stronger (Explicit)", "Ye (侃爷)", yeSolo, list(), "Graduation (UK Version) [Explicit]", 311000),
                "Stronger does not prefix-merge distinct album editions");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous", "Rihanna", members(member("Rihanna", "")), list(), "The Life Of Pablo", 196000),
                "guest-only solo credit is rejected by evidenced album bridge");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "Greatest Hits", 196040,
                "Famous", "Ye (侃爷)", yeSolo, list(), "Greatest Hits", 196000),
                "generic compilation cannot justify evidenced album bridge");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "FML", "Kanye West / The Weeknd", members(), list(), "The Life Of Pablo", 236000,
                "FML (Explicit)", "Ye (侃爷)", yeSolo, list(), "The Life Of Pablo (Explicit)", 236120),
                "evidenced album bridge requires official identity proof (no weak fallback)");

        // Known identity conflicts must never fall through into the legacy weak bridge.
        that(!ArtistAliases.bridgeAccepts(
                "Famous", "Kanye West / Rihanna", members(), list(), "The Life Of Pablo", 196040,
                "Famous", "Rihanna (蕾哈娜)", members(), list(), "The Life Of Pablo", 196000, true, 0),
                "guest-only bilingual credit cannot slip through weak album bridge");
        that(!ArtistAliases.bridgeAccepts(
                "Famous", "Kanye West / Rihanna", kanyeRihanna, list(), "The Life Of Pablo", 196040,
                "Famous", "Drake (德雷克)", members(), list(), "The Life Of Pablo", 196000, true, 0),
                "stranger bilingual lead is rejected when official lead aliases exist");
        that(!ArtistAliases.bridgeAccepts(
                NEW_ISLAND, SAKANACTION, members(), ALIASES, "", 305000,
                NEW_ISLAND, "Coldplay", members(), list(), "", 305000, true, 0),
                "unrelated same-script artist cannot bypass known official alias via cross-script rank 0");
    }

    private static void structuredSerializationAndQueries() throws Exception {
        JSONArray qqSingers = new JSONArray()
                .put(new JSONObject().put("mid", "qqSym").put("name", "¥$"))
                .put(new JSONObject().put("mid", "0025NhlN2yWrP4").put("name", "Ye (侃爷)"))
                .put(new JSONObject().put("mid", "qqTy").put("name", "Ty Dolla $ign"));
        List<ArtistAliases.Member> parsed = ArtistAliases.membersFromArtists(qqSingers);
        that(parsed.size() == 3 && "¥$".equals(parsed.get(0).name) && "0025NhlN2yWrP4".equals(parsed.get(1).providerId),
                "QQ singer[] preserves order, symbol-only names, and provider mid");
        List<ArtistAliases.Member> roundTripped = ArtistAliases.membersFromJson(ArtistAliases.membersToJson(parsed));
        that(roundTripped.size() == 3 && "Ye (侃爷)".equals(roundTripped.get(1).name),
                "artistMembers round-trips through JSON");

        List<String> reverseQueries = ArtistAliases.searchQueries(
                "Famous (Explicit)", "Ye (侃爷)", members(member("Ye (侃爷)", "0025")), list());
        that(reverseQueries.equals(list("Famous Ye")),
                "reverse query strips benign Explicit and bracketed alias");
        List<String> liveQueries = ArtistAliases.searchQueries(
                "Famous (Live)", "Ye (侃爷)", members(member("Ye (侃爷)", "0025")), list());
        that(liveQueries.equals(list("Famous (Live) Ye")),
                "recall query preserves Live version marker");

        List<String> collabQueries = ArtistAliases.searchQueries(
                "Famous", "Kanye West / Rihanna",
                members(member("Kanye West", "37520", "ye", "坎耶·维斯特"), member("Rihanna", "72724")), list());
        that(collabQueries.contains("Famous ye Rihanna") && collabQueries.contains("Famous ye"),
                "collaboration alias queries substitute official member alias and lead alias");

        List<String> carnivalQueries = ArtistAliases.searchQueries(
                "CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign",
                members(member("¥$", "1"), member("Kanye West", "37520", "ye"), member("Ty Dolla $ign", "2")), list());
        that(carnivalQueries.contains("CARNIVAL ¥$ ye Ty Dolla $ign"),
                "non-lead alias substitution builds CARNIVAL recall query");
    }

    private static ArtistAliases.Member member(String name, String id, String... aliases) {
        return new ArtistAliases.Member(name, id, Arrays.asList(aliases));
    }

    private static List<ArtistAliases.Member> members(ArtistAliases.Member... items) {
        return Arrays.asList(items);
    }

    private static JSONObject song(String id, String artist, String alias) throws Exception {
        return new JSONObject().put("id", id).put("ar", new JSONArray()
                .put(new JSONObject().put("name", artist).put("tns", new JSONArray().put(alias))));
    }

    private static List<String> list(String... values) { return Arrays.asList(values); }
}
