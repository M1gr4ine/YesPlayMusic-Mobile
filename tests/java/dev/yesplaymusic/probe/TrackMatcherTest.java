package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import dev.yesplaymusic.probe.ArtistAliases.Member;
import dev.yesplaymusic.probe.TrackMatcher.Candidate;
import dev.yesplaymusic.probe.TrackMatcher.Policy;
import dev.yesplaymusic.probe.TrackMatcher.QueryStage;
import dev.yesplaymusic.probe.TrackMatcher.QueryStep;
import dev.yesplaymusic.probe.TrackMatcher.RankResult;
import dev.yesplaymusic.probe.TrackMatcher.RankedHit;
import dev.yesplaymusic.probe.TrackMatcher.Rule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Rule-named synthetic unit tests for {@link TrackMatcher} covering all hard gates, Rules A/B, A1-A7, and trace. */
final class TrackMatcherTest {
    private TrackMatcherTest() {}

    static void run() throws Exception {
        testHardGatesTitleVersionDuration();
        testLiveYearRuleA3();
        testRuleAAndGuestOnlyRuleA4();
        testSameScriptConflictRuleA5();
        testStructuralGroupingConflictVeto();
        testRuleBAndFullLadderUniquenessA2AndA6();
        testPlaybackRankingOrder();
        testWritePolicyAndRuleA7();
        testUnifiedQueryLadder();
        testMatchTraceIntegration();
        testAdversarialAuditRegressions();
    }

    private static void testHardGatesTitleVersionDuration() throws Exception {
        Track target = track("t1", "Signal Path", "Lead Artist", "Studio Album", 210000L);

        // Hard Gate 1: different title with same artist and identical duration must be rejected (wrong-song guard).
        that(TrackMatcher.rank(target, list(
                cand("c1", "Other Track", "Lead Artist", "Studio Album", 210000L)), Policy.PLAYBACK).isEmpty(),
                "different title with same artist and duration is rejected");

        // Benign annotations and CJK translation subtitles match.
        that("c2".equals(firstId(TrackMatcher.rank(target, list(
                cand("c2", "Signal Path (Album Version|Explicit)", "Lead Artist", "Studio Album (Remastered 2020)", 212000L)),
                Policy.PLAYBACK))),
                "benign annotations and remaster markers pass title gate");
        that("c3".equals(firstId(TrackMatcher.rank(target, list(
                cand("c3", "Signal Path (中文译名)", "Lead Artist", "Studio Album", 210000L)), Policy.PLAYBACK))),
                "CJK translated subtitle passes title gate");

        // Non-benign version/edition brackets stay rejected.
        that(TrackMatcher.rank(target, list(
                cand("c4", "Signal Path (Special Version)", "Lead Artist", "Studio Album", 210000L)),
                Policy.PLAYBACK).isEmpty(),
                "unrecognized bracketed edition is rejected");

        // Hard Gate 2: version tags must match exactly.
        for (String tag : new String[]{"Live", "现场", "現場", "Instrumental", "伴奏", "Remix", "Acoustic",
                "Cover", "翻唱", "国语版", "白话版", "女版", "DJ", "Acapella", "片段版"}) {
            that(TrackMatcher.rank(target, list(
                    cand("v", "Signal Path (" + tag + ")", "Lead Artist", "Studio Album", 210000L)),
                    Policy.PLAYBACK).isEmpty(),
                    "version mismatch rejected: " + tag);
        }

        // Cross-language pure Live tags align: (Live) == (现场).
        Track liveTarget = track("t2", "Signal Path (Live)", "Lead Artist", "Concert Hall (Live)", 210000L);
        that("cLive".equals(firstId(TrackMatcher.rank(liveTarget, list(
                cand("cLive", "Signal Path (现场)", "Lead Artist", "Concert Hall (现场)", 210000L)), Policy.PLAYBACK))),
                "pure Live and 现场 brackets align cleanly");
        that(TrackMatcher.rank(liveTarget, list(
                cand("cVenue", "Signal Path (Live at Venue One)", "Lead Artist", "Concert Hall (Live)", 210000L)),
                Policy.PLAYBACK).isEmpty(),
                "venue-qualified live bracket does not collapse into bare Live");

        // Hard Gate 3: duration boundary <= 5000ms; missing duration requires Rule A.
        that("c5000".equals(firstId(TrackMatcher.rank(target, list(
                cand("c5000", "Signal Path", "Lead Artist", "Studio Album", 215000L)), Policy.PLAYBACK))),
                "duration gap of exactly 5000ms passes");
        that(TrackMatcher.rank(target, list(
                cand("c5001", "Signal Path", "Lead Artist", "Studio Album", 215001L)), Policy.PLAYBACK).isEmpty(),
                "duration gap of 5001ms is rejected");
        that("cNoDurA".equals(firstId(TrackMatcher.rank(target, list(
                cand("cNoDurA", "Signal Path", "Lead Artist", "Other Album", 0L)), Policy.PLAYBACK))),
                "missing duration passes when Rule A holds");
        that(TrackMatcher.rank(target, list(
                cand("cNoDurB", "Signal Path", "Renamed Artist", "Studio Album", 0L)), Policy.PLAYBACK).isEmpty(),
                "missing duration cannot pass Rule B");

        // Unknown / missing / punctuation-only artist rejected; symbol-only artist accepted.
        that(TrackMatcher.rank(track("t3", "Signal Path", "Unknown Artist", "Studio Album", 210000L), list(
                cand("u1", "Signal Path", "Unknown Artist", "Studio Album", 210000L)), Policy.PLAYBACK).isEmpty(),
                "unknown artist placeholder is rejected");
        that("sym1".equals(firstId(TrackMatcher.rank(
                track("tSym", "Symbol Track", "￥$ / Lead Artist", "Studio Album", 200000L), list(
                cand("sym1", "Symbol Track", "¥$ / Lead Artist", "Studio Album", 200000L)), Policy.PLAYBACK))),
                "NFKC folds fullwidth symbol-only artist token");
    }

    private static void testLiveYearRuleA3() throws Exception {
        Track live2007 = track("tL1", "Ballad", "Lead Artist", "World Stage 1 (Live)", 277223L);
        Track target2007 = track("tL2", "Ballad (2007 Live)", "Lead Artist", "World Stage 1 (Live)", 277223L);
        Track targetBareLive = track("tL3", "Ballad (Live)", "Lead Artist", "World Stage 1 (Live)", 277223L);

        // One-sided live year matches on exact same live album without competition.
        that("c1".equals(firstId(TrackMatcher.rank(target2007, list(
                cand("c1", "Ballad (Live)", "Lead Artist", "World Stage 1 (Live)", 277000L)), Policy.PLAYBACK))),
                "A3: one-sided 2007 Live matches unmarked Live on same live album");
        that("c2".equals(firstId(TrackMatcher.rank(targetBareLive, list(
                cand("c2", "Ballad (2007 Live)", "Lead Artist", "World Stage 1 (Live)", 277000L)), Policy.PLAYBACK))),
                "A3: unmarked Live matches 2007 Live on same live album symmetrically");

        // Explicit different years are always rejected even on same live album.
        that(TrackMatcher.rank(target2007, list(
                cand("c2010", "Ballad (2010 Live)", "Lead Artist", "World Stage 1 (Live)", 277000L)),
                Policy.PLAYBACK).isEmpty(),
                "A3: explicit different live years are always rejected");

        // Different album or studio album or compilation album rejected.
        that(TrackMatcher.rank(target2007, list(
                cand("cDiffAl", "Ballad (Live)", "Lead Artist", "Other Concert (Live)", 277000L)),
                Policy.PLAYBACK).isEmpty(),
                "A3: one-sided live year rejected on different live album");
        that(TrackMatcher.rank(track("tStudio", "Ballad (2007 Live)", "Lead Artist", "Studio Album", 277000L), list(
                cand("cStudio", "Ballad (Live)", "Lead Artist", "Studio Album", 277000L)),
                Policy.PLAYBACK).isEmpty(),
                "A3: one-sided live year rejected on non-live studio album");
        that(TrackMatcher.rank(track("tComp", "Ballad (2007 Live)", "Lead Artist", "Live Greatest Hits", 277000L), list(
                cand("cComp", "Ballad (Live)", "Lead Artist", "Live Greatest Hits", 277000L)),
                Policy.PLAYBACK).isEmpty(),
                "A3: one-sided live year rejected on compilation album");

        // Same-album live session/year competition blocks A3.
        that(TrackMatcher.rank(target2007, list(
                cand("cUnmarked", "Ballad (Live)", "Lead Artist", "World Stage 1 (Live)", 277000L),
                cand("c2008", "Ballad (2008 Live)", "Lead Artist", "World Stage 1 (Live)", 277000L)),
                Policy.PLAYBACK).isEmpty(),
                "A3: competing live year on same live album blocks unmarked Live");
        that(TrackMatcher.rank(target2007, list(
                cand("cTake1", "Ballad (Live)", "Lead Artist", "World Stage 1 (Live)", 277000L),
                cand("cTake2", "Ballad (Live)", "Lead Artist", "World Stage 1 (Live)", 276000L)),
                Policy.PLAYBACK).isEmpty(),
                "A3: multiple unmarked live takes on same live album block A3");

        // Unrelated concert on a different/blank album (>5s or different tour) does NOT block same-album match.
        that("cMatch".equals(firstId(TrackMatcher.rank(target2007, list(
                cand("cMatch", "Ballad (Live)", "Lead Artist", "World Stage 1 (Live)", 277000L),
                cand("cOtherTour", "Ballad (2025 World Tour Concert)", "Lead Artist", "", 269000L),
                cand("cClip", "Ballad (2007 Concert 片段)", "Fan Account", "", 19000L)), Policy.PLAYBACK))),
                "A3: unrelated different-album tour live and clips do not block same-album live match");

        // When an explicit matching year candidate exists in batch, it wins and unmarked Live is rejected.
        List<RankedHit> explicitWin = TrackMatcher.rank(target2007, list(
                cand("cUnmarked", "Ballad (Live)", "Lead Artist", "World Stage 1 (Live)", 277000L),
                cand("cExplicit", "Ballad (2007 Live)", "Lead Artist", "Other Live", 277000L)), Policy.PLAYBACK);
        that(explicitWin.size() == 1 && "cExplicit".equals(explicitWin.get(0).candidate.id),
                "A3: explicit matching year wins over unmarked Live fallback");
        that(live2007 != null, "studio helper sanity");
    }

    private static void testRuleAAndGuestOnlyRuleA4() throws Exception {
        List<Member> collabMembers = Arrays.asList(
                new Member("Lead Alpha", "100", Arrays.asList("AliasAlpha", "中文名甲")),
                new Member("Guest Beta", "200", Collections.emptyList()));
        Track collabTarget = trackWithMembers("tC", "Anthem", "Lead Alpha / Guest Beta",
                "Studio Album", 200000L, collabMembers);

        // Full coverage, reordered collaboration, and solo lead match via official alias all pass Rule A.
        that("cFull".equals(firstId(TrackMatcher.rank(collabTarget, list(
                cand("cFull", "Anthem", "Guest Beta / AliasAlpha (中文名甲)", "Other Album", 200000L)), Policy.PLAYBACK))),
                "Rule A accepts reordered full-coverage collaboration via official alias");
        that("cLead".equals(firstId(TrackMatcher.rank(collabTarget, list(
                cand("cLead", "Anthem (Explicit)", "AliasAlpha (中文名甲)", "Other Album", 200000L)), Policy.PLAYBACK))),
                "Rule A playback accepts solo lead match across albums");

        // Superset collaboration (3-member target vs 5-member candidate with matching lead) passes Rule A.
        Track trio = trackWithMembers("t3", "Anthem", "¥$ / Lead Alpha / Guest Beta", "Studio Album", 200000L,
                Arrays.asList(
                        new Member("¥$", "1", Collections.emptyList()),
                        new Member("Lead Alpha", "100", Collections.singletonList("AliasAlpha")),
                        new Member("Guest Beta", "200", Collections.emptyList())));
        that("c5".equals(firstId(TrackMatcher.rank(trio, list(
                cand("c5", "Anthem", "¥$ / AliasAlpha / Guest Beta / Extra One / Extra Two", "Studio Album", 200000L)),
                Policy.PLAYBACK))),
                "Rule A accepts superset collaboration when lead matches");

        // A4: Guest-only overlap rejected bidirectionally without falling back to Rule B (even on same album).
        that(TrackMatcher.rank(collabTarget, list(
                cand("g1", "Anthem", "Guest Beta", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "A4: candidate solo lead matching only target guest is rejected without Rule B fallback");
        Track soloGuestTarget = track("tG", "Anthem", "Guest Beta", "Studio Album", 200000L);
        that(TrackMatcher.rank(soloGuestTarget, list(
                cand("g2", "Anthem", "Lead Alpha / Guest Beta", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "A4: target solo lead matching only candidate guest is rejected symmetrically");
        that(TrackMatcher.rank(collabTarget, list(
                cand("g3", "Anthem", "Other Lead / Guest Beta", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "A4: shared guest with different leads is rejected");
        that(TrackMatcher.rank(trio, list(
                cand("g4", "Anthem", "AliasAlpha / Guest Beta", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "A4: dropping lead member leaves guest-only overlap and is rejected");
    }

    private static void testSameScriptConflictRuleA5() throws Exception {
        Track target = trackWithMembers("tS", "Echo", "Lead Alpha", "Studio Album", 200000L,
                Collections.singletonList(new Member("Lead Alpha", "100", Collections.singletonList("Short"))));

        // Cross-script bracket passes Rule A.
        List<RankedHit> crossScript = TrackMatcher.rank(target, list(
                cand("ok", "Echo", "Short (中文别名)", "Other Album", 200000L)), Policy.PLAYBACK);
        that(crossScript.size() == 1 && crossScript.get(0).rule == Rule.A,
                "cross-script bracket passes Rule A");

        // Same-script bracket conflict vetoes Rule A; on a different album it stays rejected.
        that(TrackMatcher.rank(target, list(
                cand("badDiff", "Echo", "Short (Unrelated Name)", "Other Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "A5: same-script parenthetical conflict vetoes Rule A on different album");

        // A5: When sole hard-gate passer on same non-compilation album, may fall back to Rule B.
        List<RankedHit> fallbackB = TrackMatcher.rank(target, list(
                cand("soleSameAl", "Echo", "Short (Unrelated Name)", "Studio Album", 200000L)), Policy.PLAYBACK);
        that(fallbackB.size() == 1 && "soleSameAl".equals(fallbackB.get(0).candidate.id)
                && fallbackB.get(0).rule == Rule.B,
                "A5: same-script conflict vetoes Rule A but falls back to Rule B when unique on same album");

        // A5: When a valid Rule-A candidate exists in the batch, same-script conflict cannot take Rule B.
        List<RankedHit> blockedByA = TrackMatcher.rank(target, list(
                cand("conflict", "Echo", "Short (Unrelated Name)", "Studio Album", 200000L),
                cand("realA", "Echo", "Short (中文别名)", "Other Album", 200000L)), Policy.PLAYBACK);
        that(blockedByA.size() == 1 && "realA".equals(blockedByA.get(0).candidate.id)
                && blockedByA.get(0).rule == Rule.A,
                "A5: competing Rule A candidate blocks same-script conflict from Rule B");
    }

    private static void testStructuralGroupingConflictVeto() throws Exception {
        // Structured single duo "DuoA & DuoB" vs split two-artist "DuoA / DuoB" vetoes BOTH Rule A and Rule B.
        List<Member> duoSingle = Collections.singletonList(new Member("DuoA & DuoB", "10", Collections.emptyList()));
        List<Member> twoArtists = Arrays.asList(
                new Member("DuoA", "11", Collections.emptyList()),
                new Member("DuoB", "12", Collections.emptyList()));
        Track duoTarget = trackWithMembers("tDuo", "Track", "DuoA & DuoB", "Studio Album", 200000L, duoSingle);
        Track splitTarget = trackWithMembers("tSplit", "Track", "DuoA / DuoB", "Studio Album", 200000L, twoArtists);

        that(TrackMatcher.rank(duoTarget, list(
                candWithMembers("cSplit", "Track", "DuoA / DuoB", "Studio Album", 200000L, twoArtists)),
                Policy.PLAYBACK).isEmpty(),
                "grouping conflict vetoes DuoA & DuoB vs DuoA / DuoB on same album");
        that(TrackMatcher.rank(splitTarget, list(
                candWithMembers("cDuo", "Track", "DuoA & DuoB", "Studio Album", 200000L, duoSingle)),
                Policy.PLAYBACK).isEmpty(),
                "grouping conflict vetoes DuoA / DuoB vs DuoA & DuoB symmetrically");
        that("cDuoOk".equals(firstId(TrackMatcher.rank(duoTarget, list(
                candWithMembers("cDuoOk", "Track", "DuoA & DuoB", "Studio Album", 200000L, duoSingle)),
                Policy.PLAYBACK))),
                "matching structured duo DuoA & DuoB succeeds via Rule A");

        // Structured single band "BandX/BandY" vs split "BandX / BandY" vetoes both Rule A and Rule B.
        List<Member> slashBand = Collections.singletonList(new Member("BandX/BandY", "30", Collections.singletonList("乐队代号")));
        List<Member> splitBand = Arrays.asList(
                new Member("BandX", "31", Collections.emptyList()),
                new Member("BandY", "32", Collections.emptyList()));
        Track bandTarget = trackWithMembers("tBand", "Rock Song", "BandX/BandY", "Rock Album", 255000L, slashBand);
        that(TrackMatcher.rank(bandTarget, list(
                candWithMembers("cSplitBand", "Rock Song", "BandX / BandY", "Rock Album", 255000L, splitBand)),
                Policy.PLAYBACK).isEmpty(),
                "grouping conflict vetoes BandX/BandY vs BandX / BandY on same album");
        that("cAliasBand".equals(firstId(TrackMatcher.rank(bandTarget, list(
                candWithMembers("cAliasBand", "Rock Song", "乐队代号", "Rock Album", 255000L,
                        Collections.singletonList(new Member("乐队代号", "qq30", Collections.emptyList())))),
                Policy.PLAYBACK))),
                "structured slash band retains its official alias");

        // Contradictory display credit "DuoA / DuoB" paired with [Member("DuoA & DuoB")] cannot match DuoA & DuoB.
        Track badDisplay = trackWithMembers("tBadDisp", "Track", "DuoA / DuoB", "Studio Album", 200000L, duoSingle);
        that(TrackMatcher.rank(badDisplay, list(
                candWithMembers("cDuo", "Track", "DuoA & DuoB", "Studio Album", 200000L, duoSingle)),
                Policy.PLAYBACK).isEmpty(),
                "contradictory display grouping cannot match structured duo");

        // Joined credit collision "AB / C" vs "A / BC" vetoed even on same album.
        that(TrackMatcher.rank(track("tJoin", "Track", "AB / C", "Studio Album", 200000L), list(
                cand("cJoin", "Track", "A / BC", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "joined boundary collision AB / C vs A / BC is vetoed");

        // Official alias shared between single-entity "DuoA & DuoB" and single-entity "DuoA" still works via Rule A.
        Track aliasDuo = trackWithMembers("tAD", "Track", "DuoA & DuoB", "Studio Album", 200000L,
                Collections.singletonList(new Member("DuoA & DuoB", "", Collections.singletonList("SharedAlias"))));
        that("cOne".equals(firstId(TrackMatcher.rank(aliasDuo, list(
                candWithMembers("cOne", "Track", "DuoA", "Other Album", 200000L,
                        Collections.singletonList(new Member("DuoA", "12", Collections.singletonList("SharedAlias"))))),
                Policy.WRITE))),
                "single-member official alias still links DuoA & DuoB and DuoA cleanly");
    }

    private static void testRuleBAndFullLadderUniquenessA2AndA6() throws Exception {
        Track target = track("tB", "Horizon", "Old Name / Guest Artist", "Studio Album", 220000L);
        Candidate soloRenamed = cand("cB", "Horizon", "New Name", "Studio Album", 221000L);

        // A2: During intermediate ladder queries (allowRuleB = false), Rule B is deferred.
        RankResult mid = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                list(soloRenamed), Policy.PLAYBACK, false, null, "stage1");
        that(!mid.hasMatch() && !mid.hasRuleA(), "A2: Rule B does not fire before full query ladder completes");

        // A2 + A6: After ladder completes (allowRuleB = true), disjoint solo<->collab passes Rule B when unique.
        RankResult done = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                list(soloRenamed), Policy.PLAYBACK, true, null, "final");
        that(done.hits.size() == 1 && "cB".equals(done.hits.get(0).candidate.id) && done.hits.get(0).rule == Rule.B,
                "A2+A6: disjoint solo<->collab accepted by Rule B when globally unique on same non-compilation album");

        // Duplicate same candidate ID from multiple ladder stages does NOT break Rule B uniqueness.
        RankResult dupSameId = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                list(soloRenamed, cand("cB", "Horizon", "New Name", "Studio Album", 221000L)),
                Policy.PLAYBACK, true, null, "final");
        that(dupSameId.hits.size() == 1 && dupSameId.hits.get(0).rule == Rule.B,
                "duplicate candidate ID across stages deduplicates cleanly for Rule B");

        // Compilation album blocks Rule B.
        that(TrackMatcher.rank(track("tComp", "Horizon", "Old Name", "Greatest Hits", 220000L), list(
                cand("cComp", "Horizon", "New Name", "Greatest Hits", 220000L)), Policy.PLAYBACK).isEmpty(),
                "Rule B rejects compilation album");

        // Two distinct hard-gate passers block Rule B (NOT_UNIQUE).
        that(TrackMatcher.rank(target, list(
                soloRenamed,
                cand("cOther", "Horizon", "Another Cover", "Other Album", 220500L)), Policy.PLAYBACK).isEmpty(),
                "Rule B rejects when another candidate also passes hard gates 1-3");

        // Competing Rule A candidate blocks Rule B (COMPETING_A).
        List<RankedHit> withA = TrackMatcher.rank(target, list(
                soloRenamed,
                cand("cA", "Horizon", "Old Name", "Other Album", 222000L)), Policy.PLAYBACK);
        that(withA.size() == 1 && "cA".equals(withA.get(0).candidate.id) && withA.get(0).rule == Rule.A,
                "competing Rule A candidate blocks Rule B");
    }

    private static void testPlaybackRankingOrder() throws Exception {
        Track target = track("tR", "Starlight", "Lead Alpha / Guest Beta", "Studio Album", 200000L);
        List<RankedHit> ranked = TrackMatcher.rank(target, list(
                cand("diffAlFar", "Starlight", "Lead Alpha / Guest Beta", "Single", 204000L),
                cand("sameAlMid", "Starlight", "Lead Alpha", "Studio Album", 201000L),
                cand("diffAlClose", "Starlight", "Lead Alpha / Guest Beta", "Single", 200100L),
                cand("sameAlClosePartial", "Starlight", "Lead Alpha", "Studio Album", 200200L),
                cand("sameAlCloseFull", "Starlight", "Lead Alpha / Guest Beta", "Studio Album", 200200L)),
                Policy.PLAYBACK);
        that(ranked.size() == 5
                && "sameAlCloseFull".equals(ranked.get(0).candidate.id)
                && "sameAlClosePartial".equals(ranked.get(1).candidate.id)
                && "sameAlMid".equals(ranked.get(2).candidate.id)
                && "diffAlClose".equals(ranked.get(3).candidate.id)
                && "diffAlFar".equals(ranked.get(4).candidate.id),
                "playback sorts A before B, same album first, ascending duration gap, full coverage tie-break");
    }

    private static void testWritePolicyAndRuleA7() throws Exception {
        Track soloTarget = trackWithMembers("tW1", "Anthem", "AliasAlpha (中文名)", "Studio Album", 200000L,
                Collections.singletonList(new Member("AliasAlpha (中文名)", "q1", Collections.emptyList())));
        List<Member> collab = Arrays.asList(
                new Member("Lead Alpha", "100", Collections.singletonList("AliasAlpha")),
                new Member("Guest Beta", "200", Collections.emptyList()));

        // A7: Partial lead-only coverage accepted on same non-compilation album, rejected on different/compilation album.
        that("wSame".equals(firstId(TrackMatcher.rank(soloTarget, list(
                candWithMembers("wSame", "Anthem", "Lead Alpha / Guest Beta", "Studio Album", 200500L, collab)),
                Policy.WRITE))),
                "A7: Policy.WRITE accepts evidenced lead partial match on same non-compilation album");
        that(TrackMatcher.rank(soloTarget, list(
                candWithMembers("wDiff", "Anthem", "Lead Alpha / Guest Beta", "Other Album", 200000L, collab)),
                Policy.WRITE).isEmpty(),
                "A7: Policy.WRITE rejects partial lead match across different albums");
        that(TrackMatcher.rank(track("tWC", "Anthem", "Lead Alpha", "Best Of Compilation", 200000L), list(
                candWithMembers("wComp", "Anthem", "Lead Alpha / Guest Beta", "Best Of Compilation", 200000L, collab)),
                Policy.WRITE).isEmpty(),
                "A7: Policy.WRITE rejects partial lead match on compilation album");

        // Full coverage in Policy.WRITE may cross albums.
        Track fullTarget = trackWithMembers("tW2", "Anthem", "AliasAlpha / Guest Beta", "Single", 200000L,
                Arrays.asList(
                        new Member("AliasAlpha", "q1", Collections.emptyList()),
                        new Member("Guest Beta", "q2", Collections.emptyList())));
        that("wFullCross".equals(firstId(TrackMatcher.rank(fullTarget, list(
                candWithMembers("wFullCross", "Anthem", "Lead Alpha / Guest Beta", "Studio Album", 200000L, collab)),
                Policy.WRITE))),
                "A7: Policy.WRITE allows full member coverage across albums");

        // Contradicting collaborator rejected in Policy.WRITE.
        List<Member> strangerCollab = Arrays.asList(
                new Member("Lead Alpha", "100", Collections.singletonList("AliasAlpha")),
                new Member("Stranger", "999", Collections.emptyList()));
        that(TrackMatcher.rank(fullTarget, list(
                candWithMembers("wStranger", "Anthem", "Lead Alpha / Stranger", "Single", 200000L, strangerCollab)),
                Policy.WRITE).isEmpty(),
                "Policy.WRITE rejects contradicting collaborator");

        // Policy.WRITE Rule B enforces <= 2000ms duration limit.
        Track disjointTarget = track("tWB", "Horizon", "Old Name", "Studio Album", 200000L);
        that("wb2000".equals(firstId(TrackMatcher.rank(disjointTarget, list(
                cand("wb2000", "Horizon", "New Name", "Studio Album", 202000L)), Policy.WRITE))),
                "Policy.WRITE Rule B accepts 2000ms duration gap");
        that(TrackMatcher.rank(disjointTarget, list(
                cand("wb2001", "Horizon", "New Name", "Studio Album", 202001L)), Policy.WRITE).isEmpty(),
                "Policy.WRITE Rule B rejects 2001ms duration gap");

        // Policy.WRITE ambiguity guard: distinct artist identities tying at top tier are rejected.
        RankResult ambig = TrackMatcher.rankDetailed(soloTarget, soloTarget.artistMembers, soloTarget.artistAliases,
                list(
                        candWithMembers("id1", "Anthem", "Lead Alpha / Guest Beta", "Studio Album", 200040L, collab),
                        candWithMembers("id2", "Anthem", "Lead Alpha / Stranger", "Studio Album", 200090L, strangerCollab)),
                Policy.WRITE, true, null, "write_tie");
        that(ambig.ambiguous && ambig.hits.isEmpty(),
                "Policy.WRITE rejects tied top-tier candidates with distinct artist identities");

        // Same artist identity across two reissues is NOT ambiguous; closer duration wins.
        RankResult sameIdentity = TrackMatcher.rankDetailed(soloTarget, soloTarget.artistMembers, soloTarget.artistAliases,
                list(
                        candWithMembers("reissueFar", "Anthem", "Lead Alpha / Guest Beta", "Studio Album", 200092L, collab),
                        candWithMembers("reissueClose", "Anthem", "Lead Alpha / Guest Beta", "Studio Album", 200040L, collab)),
                Policy.WRITE, true, null, "write_same_id");
        that(!sameIdentity.ambiguous && "reissueClose".equals(sameIdentity.hits.get(0).candidate.id),
                "Policy.WRITE accepts same artist identity reissues and picks closer duration");

        // ID-less credits: case-only matches cleanly, punctuation difference stays ambiguous.
        Track hyphen = track("tH", "Track", "A-B", "Album", 200000L);
        that(!TrackMatcher.rankDetailed(hyphen, hyphen.artistMembers, hyphen.artistAliases, list(
                cand("h1", "Track", "A-B", "Album", 200000L),
                cand("h2", "Track", "a-b", "Album", 200000L)), Policy.WRITE, true, null, "case").ambiguous,
                "Policy.WRITE case-only ID-less duplicates do not conflict");
        that(TrackMatcher.rankDetailed(hyphen, hyphen.artistMembers, hyphen.artistAliases, list(
                cand("h1", "Track", "A-B", "Album", 200000L),
                cand("h2", "Track", "AB", "Album", 200000L)), Policy.WRITE, true, null, "punct").ambiguous,
                "Policy.WRITE distinct punctuation on ID-less duplicates stays ambiguous");
    }

    private static void testUnifiedQueryLadder() {
        List<QueryStep> single = TrackMatcher.queryLadder("Track (Explicit)", "Artist (AltName)",
                Collections.singletonList(new Member("Artist (AltName)", "1", Arrays.asList("Alias1", "Alias2", "Alias3"))),
                Collections.emptyList());
        that(single.size() == 4
                && single.get(0).stage == QueryStage.ALL_ARTISTS && "Track Artist".equals(single.get(0).query)
                && single.get(1).stage == QueryStage.MEMBER_ALIAS && "Track Alias1".equals(single.get(1).query)
                && single.get(2).stage == QueryStage.MEMBER_ALIAS && "Track Alias2".equals(single.get(2).query)
                && single.get(3).stage == QueryStage.BARE_TITLE && "Track".equals(single.get(3).query),
                "single-artist ladder runs all -> <=2 member aliases -> bare title");

        List<QueryStep> collab = TrackMatcher.queryLadder("Anthem (Live)", "Lead Alpha / Guest Beta",
                Arrays.asList(
                        new Member("Lead Alpha", "100", Arrays.asList("AliasAlpha", "AliasAlpha2")),
                        new Member("Guest Beta", "200", Collections.emptyList())),
                Collections.emptyList());
        that(collab.size() == 5
                && collab.get(0).stage == QueryStage.ALL_ARTISTS && "Anthem (Live) Lead Alpha Guest Beta".equals(collab.get(0).query)
                && collab.get(1).stage == QueryStage.LEAD_ARTIST && "Anthem (Live) Lead Alpha".equals(collab.get(1).query)
                && collab.get(2).stage == QueryStage.MEMBER_ALIAS && "Anthem (Live) AliasAlpha Guest Beta".equals(collab.get(2).query)
                && collab.get(3).stage == QueryStage.MEMBER_ALIAS && "Anthem (Live) AliasAlpha".equals(collab.get(3).query)
                && collab.get(4).stage == QueryStage.BARE_TITLE && "Anthem (Live)".equals(collab.get(4).query),
                "multi-artist ladder runs all -> lead -> <=2 alias queries -> bare title while preserving Live");
    }

    private static void testMatchTraceIntegration() throws Exception {
        Track target = track("1001", "Signal Path", "Lead Artist", "Studio Album", 200000L);
        MatchTrace trace = new MatchTrace(MatchTrace.Direction.NETEASE_TO_QQ, "test",
                MatchTrace.Policy.PLAYBACK, MatchTrace.Mode.TRACK_MATCHER,
                new MatchTrace.TargetInfo(target.id, target.title, target.artist, target.album, target.duration,
                        0, 0, MatchText.analyzeTitle(target.title).mainTitle, MatchText.analyzeTitle(target.title).versionKey));
        Candidate good = new Candidate("qq:midGood", "fileGood", "Signal Path", "Lead Artist",
                Collections.emptyList(), Collections.emptyList(), "Studio Album", 201000L, 0, 1, "parentMid", null);
        Candidate badDur = new Candidate("qq:midDur", "fileDur", "Signal Path", "Lead Artist",
                Collections.emptyList(), Collections.emptyList(), "Studio Album", 208000L, 1, -1, "", null);
        RankResult result = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                list(good, badDur), Policy.PLAYBACK, true, trace, "stage1");
        that(result.hasRuleA() && trace.candidates().size() == 2, "TrackMatcher records every evaluated candidate in MatchTrace");
        MatchTrace.CandidateRecord accepted = trace.candidates().get(0);
        MatchTrace.CandidateRecord rejected = trace.candidates().get(1);
        that(accepted.outcome == MatchTrace.Outcome.ACCEPTED && accepted.rule == MatchTrace.Rule.A
                && accepted.grpIndex == 1 && "parentMid".equals(accepted.parentMid)
                && Long.valueOf(1000L).equals(accepted.durationGapMs) && accepted.rankPosition == 0,
                "accepted trace captures Rule.A, grp index, parentMid, gap, and rank");
        that(rejected.outcome == MatchTrace.Outcome.REJECTED && rejected.failedGate == MatchTrace.Gate.DURATION
                && Long.valueOf(8000L).equals(rejected.durationGapMs),
                "rejected trace captures failed gate DURATION and exact gap");
    }

    /** Regression coverage for every adversarial edge case identified in Step 3 audit. */
    private static void testAdversarialAuditRegressions() throws Exception {
        // P1-1a: Separator-only candidate artist ("With") must fail UNKNOWN_ARTIST and never enter Rule B or NPE.
        Track base = track("t1", "Horizon", "Lead Artist", "Studio Album", 200000L);
        that(TrackMatcher.rank(base, list(
                cand("cWith", "Horizon", "With", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "P1-1: separator-only artist With is rejected without entering Rule B or throwing NPE");
        that(TrackMatcher.rank(base, list(
                cand("cWith", "Horizon", "With", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-1: separator-only artist With is rejected under WRITE");

        // P1-1b: An unknown-artist candidate passing hard gates (1)(2)(3) still blocks Rule B uniqueness.
        that(TrackMatcher.rank(base, list(
                cand("cUnknown", "Horizon", "Unknown Artist", "Other Album", 200000L),
                cand("cDisjoint", "Horizon", "Renamed Artist", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-1: unknown-artist candidate passing gates 1-3 prevents another disjoint candidate from Rule B");
        that(TrackMatcher.rank(base, list(
                cand("cUnknown", "Horizon", "Unknown Artist", "Other Album", 200000L),
                cand("cDisjoint", "Horizon", "Renamed Artist", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "P1-1: unknown-artist candidate passing gates 1-3 also blocks playback Rule B uniqueness");

        // P1-2: A7 Policy.WRITE partial lead coverage rejects free brackets and ID-less display-only credits.
        Track freeBracket = track("tFB", "Anthem", "甲 (North)", "Studio Album", 200000L);
        that(TrackMatcher.rank(freeBracket, list(
                cand("cNG", "Anthem", "North / Guest", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-2: free bracket alias without ID/official alias cannot authorize WRITE partial match");
        that(TrackMatcher.rank(freeBracket, list(
                candWithMembers("cNGId", "Anthem", "North / Guest", "Studio Album", 200000L, Arrays.asList(
                        new Member("North", "100", Collections.emptyList()),
                        new Member("Guest", "200", Collections.emptyList())))), Policy.WRITE).isEmpty(),
                "P1-2: free bracket alias still cannot match candidate base name without official alias");
        Track pureDisplay = track("tPD", "Anthem", "North", "Studio Album", 200000L);
        that(TrackMatcher.rank(pureDisplay, list(
                cand("cNG", "Anthem", "North / Guest", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-2: ID-less pure display lead without official alias cannot authorize WRITE partial match");
        that("cNGId".equals(firstId(TrackMatcher.rank(pureDisplay, list(
                candWithMembers("cNGId", "Anthem", "North / Guest", "Studio Album", 200000L, Arrays.asList(
                        new Member("North", "100", Collections.emptyList()),
                        new Member("Guest", "200", Collections.emptyList())))), Policy.WRITE))),
                "P1-2: canonical base lead match backed by provider ID authorizes WRITE partial on same album");
        // Unrelated official alias on lead must NOT count as evidence for a different intersecting token.
        Track unrelatedAlias = trackWithMembers("tUA", "Anthem", "甲 (North)", "Studio Album", 200000L,
                Collections.singletonList(new Member("甲 (North)", "", Collections.singletonList("Unrelated"))));
        that(TrackMatcher.rank(unrelatedAlias, list(
                cand("cNG", "Anthem", "North / Guest", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-2: unrelated official alias does not certify a free bracket overlap");

        // P1-3: A4 precedes A5 fallback; guest bracket conflict cannot be hidden by lead match; Cyrillic scripts checked.
        Track northGuest = track("tNG", "Anthem", "North / Guest", "Studio Album", 200000L);
        that(TrackMatcher.rank(northGuest, list(
                cand("cGS", "Anthem", "Guest (Stranger)", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-3: A4 guest-only overlap rejects Guest (Stranger) without falling back to Rule B (WRITE)");
        that(TrackMatcher.rank(northGuest, list(
                cand("cGS", "Anthem", "Guest (Stranger)", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "P1-3: A4 guest-only overlap rejects Guest (Stranger) without falling back to Rule B (PLAYBACK)");
        that(TrackMatcher.rank(northGuest, list(
                cand("cNGS", "Anthem", "North / Guest (Stranger)", "Other Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "P1-3: guest same-script parenthetical conflict vetoes Rule A even when lead matches");
        Track cyrillic = track("tCyr", "Song", "Нина", "Album One", 200000L);
        that(TrackMatcher.rank(cyrillic, list(
                cand("cCyrBad", "Song", "Нина (Анна)", "Album Two", 200000L)), Policy.WRITE).isEmpty(),
                "P1-3: Cyrillic same-script parenthetical conflict vetoes Rule A");
        that("cCyrCross".equals(firstId(TrackMatcher.rank(cyrillic, list(
                cand("cCyrCross", "Song", "Нина (Nina)", "Album Two", 200000L)), Policy.WRITE))),
                "P1-3: Cyrillic with Latin bracket translation passes Rule A");

        // P1-4: Year/stop CJK brackets never stripped as song translations; album never strips CJK volume brackets;
        //       A3 competition checks exact song title & artist rather than startsWith.
        that(TrackMatcher.rank(
                track("tStop1", "Ballad (Live) (2007 上海站)", "Lead Artist", "Tour Album (Live)", 200000L), list(
                cand("cStop2", "Ballad (Live) (2025 北京站)", "Lead Artist", "Tour Album (Live)", 200000L)),
                Policy.WRITE).isEmpty(),
                "P1-4: distinct concert tour stop brackets are never stripped as CJK translations");
        that(TrackMatcher.rank(
                track("tVol1", "Track", "Old Artist", "合奏（上）", 200000L), list(
                cand("cVol2", "Track", "New Artist", "合奏（下）", 200000L)), Policy.WRITE).isEmpty(),
                "P1-4: album volume brackets 上/下 are never stripped into the same album");
        that("cStarLive".equals(firstId(TrackMatcher.rank(
                track("tStar", "Star (2007 Live)", "Lead Artist", "Arena Stage (Live)", 277000L), list(
                cand("cStarLive", "Star (Live)", "Lead Artist", "Arena Stage (Live)", 277000L),
                cand("cStarlight", "Starlight (Live)", "Other Artist", "Arena Stage (Live)", 60000L),
                cand("cCoverYear", "Star (2007 Live)", "Cover Artist", "Cover Album (Live)", 277000L)),
                Policy.PLAYBACK))),
                "P1-4: A3 ignores unrelated Starlight prefix song and cover artist's same-year recording");

        // P1-5: Order-independent grouping conflict; identical A & B / A / B accepted; unstructured AC/DC vs AC / DC vetoed.
        that(TrackMatcher.rank(track("tRep", "Track", "AB / C", "Studio Album", 200000L), list(
                cand("cRep", "Track", "BC / A", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-5: order-independent grouping conflict vetoes AB / C vs BC / A");
        List<Member> abab = Arrays.asList(
                new Member("A & B", "1", Collections.emptyList()),
                new Member("A", "2", Collections.emptyList()),
                new Member("B", "3", Collections.emptyList()));
        that("cIdent".equals(firstId(TrackMatcher.rank(
                trackWithMembers("tIdent", "Track", "A & B / A / B", "Studio Album", 200000L, abab), list(
                candWithMembers("cIdent", "Track", "A & B / A / B", "Studio Album", 200000L, abab)),
                Policy.WRITE))),
                "P1-5: identical structured credit A & B / A / B matches itself without false grouping veto");
        that(TrackMatcher.rank(track("tUnstruct", "Rock Song", "AC/DC", "Rock Album", 255000L), list(
                candWithMembers("cSplit", "Rock Song", "AC / DC", "Rock Album", 255000L, Arrays.asList(
                        new Member("AC", "1", Collections.emptyList()),
                        new Member("DC", "2", Collections.emptyList())))), Policy.WRITE).isEmpty(),
                "P1-5: unstructured AC/DC never splits to match structured AC / DC");

        // P2-1: Order-independent exact bipartite matching (U->{X,Y}, V->{X} vs X / Y).
        List<Member> uv = Arrays.asList(
                new Member("U", "1", Arrays.asList("X", "Y")),
                new Member("V", "2", Collections.singletonList("X")));
        List<Member> vu = Arrays.asList(
                new Member("V", "2", Collections.singletonList("X")),
                new Member("U", "1", Arrays.asList("X", "Y")));
        Candidate xy = candWithMembers("cXY", "Track", "X / Y", "Other Album", 200000L, Arrays.asList(
                new Member("X", "10", Collections.emptyList()),
                new Member("Y", "20", Collections.emptyList())));
        that("cXY".equals(firstId(TrackMatcher.rank(
                trackWithMembers("tUV", "Track", "U / V", "Studio Album", 200000L, uv), list(xy), Policy.WRITE)))
                && "cXY".equals(firstId(TrackMatcher.rank(
                trackWithMembers("tVU", "Track", "V / U", "Studio Album", 200000L, vu), list(xy), Policy.WRITE))),
                "P2-1: exact bipartite matching finds unique cover U->Y, V->X regardless of member order");

        // P2-2: Multi-member alias query preserves structured slash band name; >200 char title still builds ladder.
        List<QueryStep> acdcGuest = TrackMatcher.queryLadder("Song", "AC/DC / Guest",
                Arrays.asList(
                        new Member("AC/DC", "1", Collections.emptyList()),
                        new Member("Guest", "2", Collections.singletonList("Alias"))),
                Collections.emptyList());
        that(acdcGuest.stream().anyMatch(s -> "Song AC/DC Alias".equals(s.query))
                && acdcGuest.stream().noneMatch(s -> s.query.contains("AC / DC")),
                "P2-2: structured AC/DC is preserved in multi-member alias substitution query");
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 25; i++) longTitle.append("MedleyPart").append(i).append(' ');
        that(!TrackMatcher.queryLadder(longTitle.toString().trim(), "Artist", Collections.emptyList(),
                Collections.emptyList()).isEmpty(),
                "P2-2: titles longer than 200 characters still build recall queries up to Track 512-char limit");

        // Step 4 audit regressions (P1-2, P1-4, P1-6, P2-2):
        // P1-2: Brackets in "AB (A) / C (BC)" must NOT let structural grouping conflict fall through to Rule B.
        that(TrackMatcher.rank(track("tG1", "Track", "AB (A) / C (BC)", "Studio Album", 200000L), list(
                cand("cG1", "Track", "A / BC", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-2(S4): bracketed AB (A) / C (BC) vs A / BC is vetoed by grouping conflict (WRITE)");
        that(TrackMatcher.rank(track("tG2", "Track", "A / BC", "Studio Album", 200000L), list(
                cand("cG2", "Track", "AB (A) / C (BC)", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "P1-2(S4): grouping conflict vetoes A / BC vs AB (A) / C (BC) symmetrically (PLAYBACK)");

        // P1-4: Cross-round same-ID merge must never overwrite known duration (113s) with missing duration (0s).
        Candidate round1Long = cand("mDur", "Horizon", "Lead Artist", "Studio Album", 113000L);
        Candidate round2MissingDur = new Candidate("mDur", "media_mDur", "Horizon", "Lead Artist",
                Collections.singletonList(new Member("Lead Artist", "1", Collections.singletonList("ExtraAlias"))),
                Collections.singletonList("ExtraAlias"), "Studio Album", 0L, 0, -1, "", null);
        that(TrackMatcher.rank(track("tDur", "Horizon", "Lead Artist", "Studio Album", 100000L),
                list(round1Long, round2MissingDur), Policy.PLAYBACK).isEmpty(),
                "P1-4(S4): cross-round merge preserves known 113s duration instead of erasing to 0s");

        // P1-6: Placeholder member ("Unknown") never confers Rule A identity and never promotes guest to lead.
        that(TrackMatcher.rank(track("tUnk1", "Track", "Unknown / North", "Album One", 200000L), list(
                cand("cUnk1", "Track", "Unknown / Other", "Album Two", 200000L)), Policy.PLAYBACK).isEmpty(),
                "P1-6(S4): Unknown placeholder in Unknown / North vs Unknown / Other cannot trigger Rule A");
        that(TrackMatcher.rank(track("tUnk2", "Track", "Unknown / North", "Studio Album", 200000L), list(
                cand("cUnk2", "Track", "North", "Studio Album", 200000L)), Policy.PLAYBACK).isEmpty(),
                "P1-6(S4): Unknown placeholder stays at lead slot so guest North is not promoted to lead");
        that(TrackMatcher.rank(track("tUnk3", "Track", "Lead Artist", "Studio Album", 200000L), list(
                cand("cAllPlace", "Track", "Unknown / 群星", "Other Album", 200000L),
                cand("cOtherDisj", "Track", "Renamed Artist", "Studio Album", 200000L)), Policy.WRITE).isEmpty(),
                "P1-6(S4): all-placeholder credit passing gates 1-3 still blocks Rule B uniqueness");

        // P2-2: Fullwidth ／ and tight CJK / align between artistParts, artistRawParts, matchesDisplayCredit, and queryLadder.
        that(MatchText.artistRawParts("甲／乙").equals(Arrays.asList("甲", "乙"))
                && MatchText.artistParts("甲／乙").size() == 2
                && MatchText.matchesDisplayCredit("甲/乙", Arrays.asList("甲", "乙"))
                && MatchText.matchesDisplayCredit("甲／乙", Arrays.asList("甲", "乙")),
                "P2-2(S4): fullwidth ／ and CJK / split consistently and pass matchesDisplayCredit lookbehind");
        List<QueryStep> cjkFullwidth = TrackMatcher.queryLadder("曲目", "甲／乙", Collections.emptyList(),
                Collections.emptyList());
        that(cjkFullwidth.stream().anyMatch(s -> s.stage == QueryStage.LEAD_ARTIST && "曲目 甲".equals(s.query)),
                "P2-2(S4): fullwidth ／ credit produces clean lead-artist query");

        // Pass 4b P1-3: Unknown placeholder tokens inside brackets or official aliases never confer Rule A identity.
        that(TrackMatcher.rank(track("tBrUnk", "Horizon", "North (未知)", "Album One", 200000L), list(
                cand("cBrUnk", "Horizon", "Other (未知)", "Album Two", 200000L)), Policy.WRITE).isEmpty(),
                "P1-3(S4b): bracketed unknown placeholder (未知) cannot establish identity across distinct artists");
        that(TrackMatcher.rank(
                trackWithMembers("tAlUnk", "Horizon", "North", "Album One", 200000L,
                        Collections.singletonList(new Member("North", "1", Collections.singletonList("Unknown")))),
                list(candWithMembers("cAlUnk", "Horizon", "Other", "Album Two", 200000L,
                        Collections.singletonList(new Member("Other", "2", Collections.singletonList("Unknown"))))),
                Policy.WRITE).isEmpty(),
                "P1-3(S4b): shared Unknown official alias cannot establish Rule A identity");
        that("cNorthClean".equals(firstId(TrackMatcher.rank(
                track("tBrNorth", "Horizon", "North (未知)", "Album One", 200000L), list(
                cand("cNorthClean", "Horizon", "North", "Album Two", 200000L)), Policy.WRITE))),
                "P1-3(S4b): filtering placeholder bracket token still allows genuine artist match");

        // Pass 4b P1-4: Same-ID cross-round merge never copies collab flat aliases onto a solo credit in either order.
        Candidate soloFirst = new Candidate("qq:m100", "f100", "Horizon", "First",
                Collections.singletonList(new Member("First", "q1", Collections.emptyList())),
                Collections.emptyList(), "Album Two", 200000L, 0, -1, "", null);
        Candidate collabFirstGuest = new Candidate("qq:m100", "f100", "Horizon", "First / Guest",
                Arrays.asList(
                        new Member("First", "q1", Collections.emptyList()),
                        new Member("Guest", "q2", Collections.singletonList("North"))),
                Collections.singletonList("North"), "Album Two", 200000L, 0, -1, "", null);
        Track northTarget = track("tNorth", "Horizon", "North", "Album One", 200000L);
        that(TrackMatcher.rank(northTarget, list(soloFirst, collabFirstGuest), Policy.PLAYBACK).isEmpty()
                && TrackMatcher.rank(northTarget, list(collabFirstGuest, soloFirst), Policy.PLAYBACK).isEmpty(),
                "P1-4(S4b): merging solo and collab observations of same MID never leaks Guest alias to solo First");

        // Pass 4b P1-5: Cross-script free brackets never mask provable base-name repartition (AB/C vs A/BC).
        Track leftBracketed = track("tAbC", "Track", "AB (甲) / C (乙)", "Album One", 200000L);
        Candidate rightBracketed = cand("cABc", "Track", "A (甲) / BC (乙)", "Album Two", 200000L);
        Track rightTarget = track("tABc", "Track", "A (甲) / BC (乙)", "Album Two", 200000L);
        Candidate leftCand = cand("cAbC", "Track", "AB (甲) / C (乙)", "Album One", 200000L);
        that(TrackMatcher.rank(leftBracketed, list(rightBracketed), Policy.WRITE).isEmpty()
                && TrackMatcher.rank(rightTarget, list(leftCand), Policy.WRITE).isEmpty()
                && TrackMatcher.rank(leftBracketed, list(rightBracketed), Policy.PLAYBACK).isEmpty()
                && TrackMatcher.rank(rightTarget, list(leftCand), Policy.PLAYBACK).isEmpty(),
                "P1-5(S4b): AB (甲) / C (乙) vs A (甲) / BC (乙) is rejected bidirectionally as A6 grouping conflict");
        that("cSameBase".equals(firstId(TrackMatcher.rank(leftBracketed, list(
                cand("cSameBase", "Track", "AB / C", "Album Two", 200000L)), Policy.WRITE))),
                "P1-5(S4b): identical base grouping AB (甲) / C (乙) vs AB / C still matches");
    }

    private static String firstId(List<RankedHit> hits) {
        return hits.isEmpty() ? "" : hits.get(0).candidate.id;
    }

    private static Track track(String id, String title, String artist, String album, long duration) throws Exception {
        return Track.fromJson(new JSONObject().put("id", id).put("title", title)
                .put("artist", artist).put("album", album).put("duration", duration));
    }

    private static Track trackWithMembers(String id, String title, String artist, String album,
                                          long duration, List<Member> members) throws Exception {
        JSONObject obj = new JSONObject().put("id", id).put("title", title)
                .put("artist", artist).put("album", album).put("duration", duration);
        if (members != null && !members.isEmpty()) {
            obj.put("artistMembers", ArtistAliases.membersToJson(members));
        }
        return Track.fromJson(obj);
    }

    private static Candidate cand(String id, String title, String artist, String album, long duration) {
        return new Candidate(id, "media_" + id, title, artist,
                Collections.emptyList(), Collections.emptyList(), album, duration, 0, -1, "", null);
    }

    private static Candidate candWithMembers(String id, String title, String artist, String album,
                                             long duration, List<Member> members) {
        return new Candidate(id, "media_" + id, title, artist,
                members, Collections.emptyList(), album, duration, 0, -1, "", null);
    }

    private static List<Candidate> list(Candidate... items) {
        return new ArrayList<>(Arrays.asList(items));
    }
}
