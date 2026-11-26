package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic rule-named unit tests for artist alias extraction, structured credits, and legacy bridge guards. */
final class ArtistAliasesTest {
    private static final String TRACK_JP = "架空島";
    private static final String ARTIST_KANA = "カタカナバンド";
    private static final String ARTIST_ZH = "中文译名团";
    private static final List<String> ALIASES = list("KanaBand", ARTIST_ZH);

    private ArtistAliasesTest() {}

    static void run() throws Exception {
        that(ArtistAliases.score(TRACK_JP, ARTIST_KANA, ALIASES, 305000,
                TRACK_JP, "kanaband", list(), 306000) > 140, "requested aliases match candidate credit");
        that(ArtistAliases.score("架空、風。", ARTIST_KANA, ALIASES, 292000,
                "架空、風。 (中文副标题)", "kanaband", list(), 294000) > 140, "aliases match translated subtitle");
        that(ArtistAliases.score(TRACK_JP, "kanaband", list(), 305000,
                TRACK_JP, ARTIST_KANA, ALIASES, 306000) > 140, "candidate aliases match symmetrically");
        that(ArtistAliases.score(TRACK_JP, ARTIST_KANA, ALIASES, 305000,
                TRACK_JP, "Cover Artist", list(), 306000) < 0, "unrelated artist stays unmatched");
        that(ArtistAliases.score(TRACK_JP, ARTIST_KANA, ALIASES, 305000,
                TRACK_JP + " (Live)", "kanaband", list(), 306000) < 0, "aliases preserve version guard");
        that(ArtistAliases.score(TRACK_JP, ARTIST_KANA, ALIASES, 305000,
                TRACK_JP, "kanaband", list(), 314001) < 0, "aliases preserve duration guard");
        that(ArtistAliases.score(TRACK_JP, "Artist A / Artist B", list("KanaBand"), 305000,
                TRACK_JP, "KanaBand", list(), 306000) < 0, "unattributed flat alias cannot replace collaboration");
        that(ArtistAliases.score(TRACK_JP, ARTIST_KANA, list("KanaBand"), 305000,
                TRACK_JP, "KanaBand / Guest", list("KanaBand"), 306000) < 0, "nor for candidate collaboration");

        List<ArtistAliases.Member> collabLeadAlias = members(
                member("Lead Alpha", "1001", "ShortAlias"),
                member("Guest Beta", "1002"));
        that(ArtistAliases.bridgeAccepts("Track One", "Lead Alpha / Guest Beta", collabLeadAlias, list(),
                "Distinctive Studio Album", 236000,
                "Track One (Explicit)", "ShortAlias (别名)", members(), list(),
                "Distinctive Studio Album (Explicit)", 236120, true, 0),
                "legacy album bridge accepts evidenced lead member identity");
        that(!ArtistAliases.bridgeAccepts("Track", "Artist A / Artist B", list("Guest Alias"), "Short", 200000,
                "Track", "Guest Alias", list(), "Short", 200000, true, 0),
                "unattributed flat alias on collaboration cannot justify legacy album bridge");
        that(ArtistAliases.bridgeAccepts("Track One", "Lead Alpha / Guest Beta", list(),
                "Distinctive Studio Album", 236000,
                "Track One (Explicit)", "ShortAlias (别名)", list(),
                "Distinctive Studio Album (Explicit)", 236120, true, 0),
                "legacy distinctive album and bilingual credit bridge without aliases");
        that(!ArtistAliases.bridgeAccepts("Track One", "Lead Alpha / Guest Beta", list(), "Greatest Hits", 236000,
                "Track One", "ShortAlias (别名)", list(), "Greatest Hits", 236120, true, 0),
                "generic compilation cannot justify legacy album bridge");
        that(!ArtistAliases.bridgeAccepts("Track One", "Lead Alpha / Guest Beta", collabLeadAlias, list(),
                "Distinctive Studio Album", 236000,
                "Track One (Explicit)", "Someone Else", members(), list(),
                "Distinctive Studio Album", 236120, true, 0),
                "legacy album bridge rejects contradictory same-script identity");
        that(!ArtistAliases.bridgeAccepts("Track One", "Lead Alpha / Guest Beta", collabLeadAlias, list(),
                "Distinctive Studio Album", 236000,
                "Track One (Explicit)", "ShortAlias (Conflict Name)", members(), list(),
                "Distinctive Studio Album", 236120, true, 0),
                "legacy bridge rejects conflicting visible same-script names");
        that(!ArtistAliases.bridgeAccepts("Track One", "Lead Alpha / Guest Beta", collabLeadAlias, list(),
                "Distinctive Studio Album", 236000,
                "Track One", "ShortAlias / Stranger", members(), list(),
                "Distinctive Studio Album", 236120, true, 0),
                "legacy bridge rejects ambiguous candidate collaboration");
        that(ArtistAliases.bridgeAccepts("Cross Track", "漢字カタカナ", list(), "", 257000,
                "Cross Track", "汉字歌手", list(), "", 257100, true, 0),
                "legacy rank-0 artist query bridges cross-script credit");
        that(!ArtistAliases.bridgeAccepts("Cross Track", "漢字カタカナ", list(), "", 257000,
                "Cross Track", "汉字歌手", list(), "", 257100, true, 1),
                "legacy lower-ranked cross-script credit is rejected");
        that(!ArtistAliases.bridgeAccepts("Cross Track", "漢字カタカナ / Guest", list(), "", 257000,
                "Cross Track", "汉字歌手", list(), "", 257100, true, 0),
                "legacy cross-script bridge rejects collaborations");
        that(!ArtistAliases.bridgeAccepts("Cross Track", "漢字カタカナ", list(), "", 257000,
                "Cross Track", "汉字歌手", list(), "", 262001, true, 0),
                "legacy bridge enforces duration boundary");
        that(ArtistAliases.score("Track", "Artist", list(), "Album", 200000,
                "Track", "Artist", list(), "Album", 200000) == 200, "alias album overload adds album evidence");
        that(ArtistAliases.score("Track", "Unknown Artist", list("Artist"), 200000,
                "Track", "Artist", list(), 200000) < 0, "aliases cannot rescue unknown display credit");

        that(ArtistAliases.score("Track One", "Lead Alpha", list("shortalias"), 236000,
                "Track One (Explicit)", "ShortAlias (别名)", list(), 240000) >= 140,
                "official solo alias matches bilingual credit within four seconds");
        that(ArtistAliases.score("Track One", "Lead Alpha", list("shortalias"), 0,
                "Track One (Explicit)", "ShortAlias (别名)", list(), 0) >= 140,
                "known alias and benign title match without duration");
        that(ArtistAliases.score("Track One", "Lead Alpha", list("shortalias"), 236000,
                "Track One (Live)", "ShortAlias (别名)", list(), 240000) < 0,
                "known alias cannot bypass version mismatch");

        JSONArray artists = new JSONArray().put(new JSONObject().put("name", ARTIST_KANA)
                .put("alias", new JSONArray().put("KanaBand")).put("alia", new JSONArray().put("KanaBand"))
                .put("tns", new JSONArray().put(ARTIST_ZH)).put("trans", "Trans Name"));
        that(ArtistAliases.fromArtists(artists).equals(list("KanaBand", ARTIST_ZH, "Trans Name")),
                "extracts deduplicated alternate names only");

        JSONObject response = new JSONObject().put("result", new JSONObject().put("songs", new JSONArray()
                .put(song("999", "Wrong Artist", "wrong alias"))
                .put(song("10001", ARTIST_KANA, ARTIST_ZH))));
        that(ArtistAliases.fromExactNeteaseSong(response, "10001").equals(list(ARTIST_ZH)),
                "alias recovery uses only exact catalog song");
        that(ArtistAliases.fromExactNeteaseSong(response, "888").isEmpty(), "alias recovery fails closed");

        that(ArtistAliases.searchQueries(TRACK_JP, ARTIST_KANA, ALIASES)
                .equals(list(TRACK_JP + " KanaBand", TRACK_JP + " " + ARTIST_ZH)), "one retry per alias");
        that(ArtistAliases.searchQueries(TRACK_JP, "KanaBand", list("KANABAND", "KanaBand", "Trans Name"))
                .equals(list(TRACK_JP + " Trans Name")), "retries skip canonical name and duplicates");
        that(ArtistAliases.searchQueries(TRACK_JP, ARTIST_KANA, list("KanaBand", ARTIST_ZH, "Third")).size() == 2,
                "retries are capped at two");
        that(ArtistAliases.searchQueries(TRACK_JP, "Artist A / Artist B", list("KanaBand")).isEmpty(),
                "legacy flat collaboration queries never split into member queries");
        that(ArtistAliases.searchQueries(TRACK_JP, ARTIST_KANA, list("KanaBand / Guest", ARTIST_ZH))
                .equals(list(TRACK_JP + " " + ARTIST_ZH)), "collaboration aliases are dropped whole");
        that(ArtistAliases.searchQueries(TRACK_JP, ARTIST_KANA, list()).isEmpty(), "legacy alias helper adds no bare query");

        structuredMemberMatching();
        evidencedBridgeAndConflictGuards();
        structuredSerializationAndQueries();
    }

    private static void structuredMemberMatching() {
        List<ArtistAliases.Member> sourceCollab = members(
                member("Lead Alpha", "1001", "shortalias", "中文主唱"),
                member("Guest Beta", "1002"));
        List<ArtistAliases.Member> targetCollab = members(
                member("ShortAlias (别名)", "qqLead"),
                member("Guest Beta", "qqGuest"));
        that(ArtistAliases.score("Anthem", "Lead Alpha / Guest Beta", sourceCollab, list(), "Studio Album", 196040,
                "Anthem", "ShortAlias (别名) / Guest Beta", targetCollab, list(), "Single", 195000) >= 140,
                "per-member official alias matches collaboration forward");
        that(ArtistAliases.score("Anthem", "ShortAlias (别名) / Guest Beta", targetCollab, list(), "Single", 195000,
                "Anthem", "Lead Alpha / Guest Beta", sourceCollab, list(), "Studio Album", 196040) >= 140,
                "per-member official alias matches collaboration in reverse");
        that(ArtistAliases.score("Anthem", "Lead Alpha / Guest Beta", sourceCollab, list(), "Studio Album", 196040,
                "Anthem", "ShortAlias. (别名) / Guest Beta",
                members(member("ShortAlias. (别名)", ""), member("Guest Beta", "")), list(),
                "Single", 195000) >= 140, "trailing punctuation matches through member alias");
        that(ArtistAliases.score("Anthem", "Lead Alpha / Guest Beta", sourceCollab, list(), "Studio Album", 196040,
                "Anthem", "Lead Alpha / Guest Beta", members(), list(), "Single", 30000) < 0,
                "30-second snippet stays rejected");
        that(ArtistAliases.score("Anthem", "Lead Alpha / Guest Beta", sourceCollab, list(), "Studio Album", 196040,
                "Anthem", "ShortAlias (别名) / Stranger",
                members(member("ShortAlias (别名)", ""), member("Stranger", "")), list(),
                "Studio Album", 196000) < 0, "mismatched collaborator is rejected");
        that(ArtistAliases.score("Anthem", "Lead Alpha / Guest Beta", sourceCollab, list(), "Studio Album", 196040,
                "Anthem", "Someone Else / Guest Beta",
                members(member("Someone Else", ""), member("Guest Beta", "")), list(),
                "Studio Album", 196000) < 0, "shared guest cannot override different lead");
        that(ArtistAliases.score("Track One", "Lead Alpha", members(member("Lead Alpha", "1001", "shortalias")), list(),
                "Studio Album", 236000, "Track One", "ShortAlias (Conflict Name)", members(), list(),
                "Studio Album", 236000) < 0, "strict score rejects conflicting same-script bracketed name");

        // Official single member with internal punctuation keeps member count 1 while rejecting split credits.
        List<ArtistAliases.Member> duoMember = members(member("DuoA & DuoB", "10"));
        List<ArtistAliases.Member> twoArtists = members(member("DuoA", "11"), member("DuoB", "12"));
        that(ArtistAliases.score("Track", "DuoA & DuoB", duoMember, list(), "Album", 200000,
                "Track", "DuoA / DuoB", twoArtists, list(), "Album", 200000) < 0,
                "single structured duo DuoA & DuoB cannot match two-artist credit DuoA / DuoB");
        that(ArtistAliases.score("Track", "DuoA / DuoB", twoArtists, list(), "Album", 200000,
                "Track", "DuoA & DuoB", duoMember, list(), "Album", 200000) < 0,
                "two-artist credit DuoA / DuoB cannot match single structured duo DuoA & DuoB");
        that(ArtistAliases.score("Track", "DuoA / DuoB", duoMember, list(), "Album", 200000,
                "Track", "DuoA & DuoB", duoMember, list(), "Album", 200000) < 0,
                "contradictory display credit DuoA / DuoB with single member DuoA & DuoB cannot match DuoA & DuoB");
        that(ArtistAliases.score("Track", "DuoA & DuoB", duoMember, list(), "Album", 200000,
                "Track", "DuoA & DuoB", duoMember, list(), "Album", 200000) >= 140,
                "matching single structured duo DuoA & DuoB succeeds");
        List<ArtistAliases.Member> slashBand = members(member("BandX/BandY", "30", "乐队译名"));
        that(ArtistAliases.score("Rock Track", "BandX/BandY", slashBand, list(), "Rock Album", 255000,
                "Rock Track", "BandX/BandY", members(member("BandX/BandY", "qq30")), list(), "Rock Album", 255000) >= 140,
                "single structured slash band name is preserved");
        that(ArtistAliases.score("Rock Track", "BandX/BandY", slashBand, list(), "Rock Album", 255000,
                "Rock Track", "乐队译名", members(member("乐队译名", "qq30")), list(), "Rock Album", 255000) >= 140,
                "single structured slash band retains its official alias");
        that(ArtistAliases.score("Rock Track", "BandX/BandY", slashBand, list(), "Rock Album", 255000,
                "Rock Track", "BandX / BandY", members(member("BandX", "1"), member("BandY", "2")), list(),
                "Rock Album", 255000) < 0,
                "single structured slash band cannot match split two-artist credit");
        that(ArtistAliases.score("Comma Track", "Artist, The Suffix",
                members(member("Artist, The Suffix", "50", "带逗号歌手")), list(), "Studio Album", 278000,
                "Comma Track", "带逗号歌手", members(member("带逗号歌手", "qq50")), list(), "Studio Album", 278000) >= 140,
                "single structured artist with comma uses official member alias");

        // Symbol-only lead preserved in 3-member credit with non-lead alias resolution.
        List<ArtistAliases.Member> trioSource = members(
                member("¥$", "99001"),
                member("Lead Alpha", "1001", "shortalias", "中文主唱"),
                member("Guest Gamma", "88001"));
        List<ArtistAliases.Member> trioTarget = members(
                member("¥$", "qq0"),
                member("ShortAlias (别名)", "qq1"),
                member("Guest Gamma", "qq2"));
        that(ArtistAliases.score("Symbol Track", "¥$ / Lead Alpha / Guest Gamma", trioSource, list(),
                "Album Vol 1", 264000, "Symbol Track", "¥$ / ShortAlias (别名) / Guest Gamma", trioTarget, list(),
                "Album Vol 1", 264000) >= 140, "3-member credit matches with symbol artist preserved");
        that(ArtistAliases.score("Symbol Track", "¥$ / Lead Alpha / Guest Gamma", trioSource, list(),
                "Album Vol 1", 264000, "Symbol Track", "ShortAlias (别名) / Guest Gamma", members(), list(),
                "Album Vol 1", 264000) < 0, "dropping symbol-only lead is rejected");
        that(ArtistAliases.score("Symbol Track", "¥$ / Lead Alpha / Guest Gamma", trioSource, list(),
                "Album Vol 1", 264000, "Symbol Track",
                "¥$ / ShortAlias (别名) / Guest Gamma / Extra One / Extra Two", members(), list(),
                "Album Vol 1", 264000) < 0, "legacy strict score rejects 5-member credit against 3-member target");
        that(ArtistAliases.score("Symbol Track", "¥$ / Lead Alpha / Guest Gamma", trioSource, list(),
                "Album Vol 1", 264000, "Symbol Track (Acapella)", "¥$ / ShortAlias (别名) / Guest Gamma",
                trioTarget, list(), "Album Vol 1", 264000) < 0, "acapella version is rejected");
        that(ArtistAliases.score("Symbol Track", "¥$ / Lead Alpha / Guest Gamma", trioSource, list(),
                "Album Vol 1", 264000, "Symbol Track (CUSTOM VERSION)", "¥$ / ShortAlias (别名) / Guest Gamma",
                trioTarget, list(), "Album Vol 1", 264000) < 0, "non-benign custom version is rejected");

        // Contradictory or partial structured members can never overwrite visible artist credits.
        that(ArtistAliases.score("Anthem", "Someone Else", members(member("Lead Alpha", "1001", "shortalias")), list(),
                "Studio Album", 196000, "Anthem", "ShortAlias (别名)", members(), list(),
                "Studio Album", 196000) < 0, "contradictory structured member cannot override visible artist");
        that(ArtistAliases.score("Anthem", "Lead Alpha / Guest Beta",
                members(member("Lead Alpha", "1001", "shortalias")), list(),
                "Studio Album", 196000, "Anthem", "ShortAlias (别名)", members(), list(),
                "Studio Album", 196000) < 0, "partial structured members cannot drop visible collaborator");
        that(ArtistAliases.score("Anthem", "Lead Alpha / Guest Beta",
                members(member("Lead Alpha", "1001", "shortalias"), member("Wrong Artist", "99", "guestbeta")), list(),
                "Studio Album", 196000, "Anthem", "ShortAlias (别名) / Guest Beta", targetCollab, list(),
                "Studio Album", 196000) < 0, "mismatched member entry invalidates structured aliases");
    }

    private static void evidencedBridgeAndConflictGuards() {
        List<ArtistAliases.Member> collab = members(
                member("Lead Alpha", "1001", "shortalias", "中文主唱"),
                member("Guest Beta", "1002"));
        List<ArtistAliases.Member> soloLead = members(member("ShortAlias (别名)", "qqLead"));

        that(ArtistAliases.evidencedLeadAlbumBridge(
                "Anthem", "Lead Alpha / Guest Beta", collab, list(), "Studio Album", 196040,
                "Anthem (Explicit)", "ShortAlias (别名)", soloLead, list(), "Studio Album (Explicit)", 196000),
                "legacy forward evidenced album bridge accepts solo lead on same album");
        that(ArtistAliases.evidencedLeadAlbumBridge(
                "Anthem (Explicit)", "ShortAlias (别名)", soloLead, list(), "Studio Album (Explicit)", 196000,
                "Anthem", "Lead Alpha / Guest Beta", collab, list(), "Studio Album", 196040),
                "legacy reverse evidenced album bridge accepts solo lead against collaboration");
        that(ArtistAliases.evidencedLeadAlbumBridge(
                "Track Two", "Lead Alpha / Guest Delta",
                members(member("Lead Alpha", "1001", "shortalias"), member("Guest Delta", "2")), list(), "ShortAl", 229253,
                "Track Two (Album Version|Explicit)", "ShortAlias (别名)", soloLead, list(), "ShortAl (Explicit)", 229000),
                "legacy evidenced album bridge accepts short real studio album");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Long Track", "Lead Alpha / Guest Echo",
                members(member("Lead Alpha", "1001", "shortalias"), member("Guest Echo", "3")), list(),
                "Long Studio Album", 548200,
                "Long Track", "ShortAlias (别名)", soloLead, list(), "Long Studio Album", 339000),
                "legacy evidenced album bridge rejects radio edit with large duration gap");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Edition Track", "Lead Alpha / Guest Foxtrot",
                members(member("Lead Alpha", "1001", "shortalias"), member("Guest Foxtrot", "4")), list(), "AlbumBase", 311000,
                "Edition Track (Explicit)", "ShortAlias (别名)", soloLead, list(), "AlbumBase (UK Version) [Explicit]", 311000),
                "distinct album editions do not prefix-merge");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Anthem", "Lead Alpha / Guest Beta", collab, list(), "Studio Album", 196040,
                "Anthem", "Guest Beta", members(member("Guest Beta", "")), list(), "Studio Album", 196000),
                "guest-only solo credit is rejected");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Anthem", "Lead Alpha / Guest Beta", collab, list(), "Greatest Hits", 196040,
                "Anthem", "ShortAlias (别名)", soloLead, list(), "Greatest Hits", 196000),
                "generic compilation cannot justify evidenced bridge");
        that(!ArtistAliases.evidencedLeadAlbumBridge(
                "Track One", "Lead Alpha / Guest Beta", members(), list(), "Studio Album", 236000,
                "Track One (Explicit)", "ShortAlias (别名)", soloLead, list(), "Studio Album (Explicit)", 236120),
                "evidenced album bridge requires official identity proof");

        // Identity conflict guards on legacy bridge.
        that(!ArtistAliases.bridgeAccepts(
                "Anthem", "Lead Alpha / Guest Beta", members(), list(), "Studio Album", 196040,
                "Anthem", "Guest Beta (客串译名)", members(), list(), "Studio Album", 196000, true, 0),
                "guest-only bilingual credit cannot slip through legacy weak bridge");
        that(!ArtistAliases.bridgeAccepts(
                "Anthem", "Lead Alpha / Guest Beta", collab, list(), "Studio Album", 196040,
                "Anthem", "Stranger (陌生人)", members(), list(), "Studio Album", 196000, true, 0),
                "stranger bilingual lead is rejected when official lead aliases exist");
        that(!ArtistAliases.bridgeAccepts(
                TRACK_JP, ARTIST_KANA, members(), ALIASES, "", 305000,
                TRACK_JP, "Unrelated Band", members(), list(), "", 305000, true, 0),
                "unrelated same-script artist cannot bypass known official alias via rank 0");
    }

    private static void structuredSerializationAndQueries() throws Exception {
        JSONArray singers = new JSONArray()
                .put(new JSONObject().put("mid", "qqSym").put("name", "¥$"))
                .put(new JSONObject().put("mid", "qqLead").put("name", "ShortAlias (别名)"))
                .put(new JSONObject().put("mid", "qqGuest").put("name", "Guest Gamma"));
        List<ArtistAliases.Member> parsed = ArtistAliases.membersFromArtists(singers);
        that(parsed.size() == 3 && "¥$".equals(parsed.get(0).name) && "qqLead".equals(parsed.get(1).providerId),
                "provider singer[] preserves order, symbol-only names, and provider mid");
        List<ArtistAliases.Member> roundTripped = ArtistAliases.membersFromJson(ArtistAliases.membersToJson(parsed));
        that(roundTripped.size() == 3 && "ShortAlias (别名)".equals(roundTripped.get(1).name),
                "artistMembers round-trips through JSON");

        List<String> reverseQueries = ArtistAliases.searchQueries(
                "Anthem (Explicit)", "ShortAlias (别名)", members(member("ShortAlias (别名)", "qqLead")), list());
        that(reverseQueries.equals(list("Anthem ShortAlias")),
                "reverse query strips benign Explicit and bracketed alias");
        List<String> liveQueries = ArtistAliases.searchQueries(
                "Anthem (Live)", "ShortAlias (别名)", members(member("ShortAlias (别名)", "qqLead")), list());
        that(liveQueries.equals(list("Anthem (Live) ShortAlias")),
                "recall query preserves Live version marker");

        List<String> collabQueries = ArtistAliases.searchQueries(
                "Anthem", "Lead Alpha / Guest Beta",
                members(member("Lead Alpha", "1001", "shortalias", "中文主唱"), member("Guest Beta", "1002")), list());
        that(collabQueries.contains("Anthem shortalias Guest Beta") && collabQueries.contains("Anthem shortalias"),
                "collaboration alias queries substitute official member alias and lead alias");

        List<String> nonLeadQueries = ArtistAliases.searchQueries(
                "Symbol Track", "¥$ / Lead Alpha / Guest Gamma",
                members(member("¥$", "1"), member("Lead Alpha", "1001", "shortalias"), member("Guest Gamma", "2")), list());
        that(nonLeadQueries.contains("Symbol Track ¥$ shortalias Guest Gamma"),
                "non-lead alias substitution builds multi-member recall query");
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
