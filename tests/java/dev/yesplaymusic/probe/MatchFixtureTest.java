package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import dev.yesplaymusic.probe.ArtistAliases.Member;
import dev.yesplaymusic.probe.TrackMatcher.Candidate;
import dev.yesplaymusic.probe.TrackMatcher.Policy;
import dev.yesplaymusic.probe.TrackMatcher.QueryStep;
import dev.yesplaymusic.probe.TrackMatcher.RankResult;
import dev.yesplaymusic.probe.TrackMatcher.RankedHit;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Table-driven runner for match fixtures under {@code tests/fixtures/match/*.json}.
 * Verifies both {@link TrackMatcher} directly and end-to-end production {@link QQSource#lookupAttempts}
 * / {@link MusicApi#matchTrack} against every fixture, asserting expected IDs, rules, top-3 ordering,
 * rejection gates via {@link MatchTrace}, and zero missing queries on {@code direct_replay} fixtures.
 */
final class MatchFixtureTest {
    private static final File FIXTURE_DIR = new File("tests/fixtures/match");

    private MatchFixtureTest() {}

    static final class Outcome {
        final String selectedId;
        final String rule;
        final List<String> top3;
        final Set<String> missingQueries;
        final Map<String, MatchTrace.Gate> decidingGatesByCandidate;

        Outcome(String selectedId, String rule, List<String> top3,
                Set<String> missingQueries, Map<String, MatchTrace.Gate> decidingGatesByCandidate) {
            this.selectedId = selectedId;
            this.rule = rule;
            this.top3 = top3;
            this.missingQueries = missingQueries;
            this.decidingGatesByCandidate = decidingGatesByCandidate;
        }
    }

    static void run() throws Exception {
        File[] files = FIXTURE_DIR.listFiles((dir, name) -> name.endsWith(".json"));
        that(files != null && files.length >= 18, "match fixture suite loaded (" + (files == null ? 0 : files.length) + ")");
        Arrays.sort(files, Comparator.comparing(File::getName));

        for (File file : files) {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject fixture = new JSONObject(raw);
            String name = fixture.getString("name");
            String kind = fixture.optString("fixtureKind", "direct_replay");
            String direction = fixture.getString("direction");
            Policy policy = Policy.valueOf(fixture.getString("policy"));
            String ruleLabel = fixture.getString("rule");
            String expect = fixture.getString("expect");
            String expectRule = fixture.optString("expectRule", "");
            String expectGate = fixture.optString("expectGate", "");
            String expectGateCandidate = fixture.optString("expectGateCandidate", "");
            boolean expandGrp = fixture.optBoolean("expandGrp", false);
            JSONObject targetJson = fixture.getJSONObject("target");
            JSONObject responses = fixture.getJSONObject("responses");
            Map<String, Member> artistDetails = parseArtistDetails(fixture.optJSONObject("artistDetails"));

            Outcome matcherResult = runTrackMatcher(direction, policy, targetJson, responses, artistDetails, expandGrp);
            Outcome prodResult = runProductionMatcher(direction, policy, targetJson, responses, artistDetails);

            that(expect.equals(matcherResult.selectedId),
                    "fixture " + name + " (" + ruleLabel + "): expected " + expect + ", got " + matcherResult.selectedId);
            if (!expectRule.isEmpty()) {
                that(expectRule.equals(matcherResult.rule),
                        "fixture " + name + ": expected rule " + expectRule + ", got " + matcherResult.rule);
            }
            if (!expectGate.isEmpty()) {
                MatchTrace.Gate requiredGate = MatchTrace.Gate.valueOf(expectGate);
                if (!expectGateCandidate.isEmpty()) {
                    that(requiredGate == matcherResult.decidingGatesByCandidate.get(expectGateCandidate),
                            "fixture " + name + " matcher candidate " + expectGateCandidate + ": expected deciding gate "
                                    + requiredGate + ", got " + matcherResult.decidingGatesByCandidate.get(expectGateCandidate));
                    that(requiredGate == prodResult.decidingGatesByCandidate.get(expectGateCandidate),
                            "fixture " + name + " production candidate " + expectGateCandidate + ": expected deciding gate "
                                    + requiredGate + ", got " + prodResult.decidingGatesByCandidate.get(expectGateCandidate));
                } else {
                    that(matcherResult.decidingGatesByCandidate.containsValue(requiredGate)
                            && prodResult.decidingGatesByCandidate.containsValue(requiredGate),
                            "fixture " + name + ": expected deciding gate " + requiredGate
                                    + ", matcher=" + matcherResult.decidingGatesByCandidate
                                    + ", prod=" + prodResult.decidingGatesByCandidate);
                }
            }
            if ("direct_replay".equals(kind)) {
                that(matcherResult.missingQueries.isEmpty() && prodResult.missingQueries.isEmpty(),
                        "direct_replay fixture " + name + " executed unrecorded queries: matcher="
                                + matcherResult.missingQueries + " prod=" + prodResult.missingQueries);
            }

            JSONArray expectTop3 = fixture.optJSONArray("expectTop3");
            if (expectTop3 != null) {
                List<String> expectedList = new ArrayList<>();
                for (int i = 0; i < expectTop3.length(); i++) expectedList.add(expectTop3.getString(i));
                that(matcherResult.top3.equals(expectedList),
                        "fixture " + name + ": expected top3 " + expectedList + ", got " + matcherResult.top3);
            }

            // Production QQSource always expands grp[] (expandGrp=true). On fml_forward_no_alias_rule_b,
            // Explicit+Clean in grp[] are two distinct MIDs/album IDs so strict production Rule B rejects,
            // while expandGrp=false verifies Rule B uniqueness cleanly.
            if ("fml_forward_no_alias_rule_b".equals(name)) {
                that("reject".equals(prodResult.selectedId) && "NONE".equals(prodResult.rule),
                        "production QQSource with grp[] expansion strictly rejects Explicit+Clean 2-MID Rule B without aliases");
            } else {
                that(expect.equals(prodResult.selectedId),
                        "production matcher for " + name + ": expected " + expect + ", got " + prodResult.selectedId);
                if (!expectRule.isEmpty()) {
                    that(expectRule.equals(prodResult.rule),
                            "production matcher rule for " + name + ": expected " + expectRule + ", got " + prodResult.rule);
                }
                if (expectTop3 != null && "netease_to_qq".equals(direction)) {
                    List<String> expectedList = new ArrayList<>();
                    for (int i = 0; i < expectTop3.length(); i++) expectedList.add(expectTop3.getString(i));
                    that(prodResult.top3.equals(expectedList),
                            "production QQSource top3 for " + name + ": expected " + expectedList + ", got " + prodResult.top3);
                }
            }

            // Verify Yueyu Canpian 2x2 separation (grp[] expansion vs A3 rule).
            if ("yueyu_canpian_remaster_1411495700".equals(name)) {
                Outcome noGrp = runTrackMatcher(direction, policy, targetJson, responses, artistDetails, false);
                that("reject".equals(noGrp.selectedId) && "qq:000SX6cK3Se2WV".equals(prodResult.selectedId),
                        "1411495700 confirms grp[] expansion recovers 276s studio master without relaxing 5s duration gate");
            } else if ("yueyu_canpian_live_65269".equals(name)) {
                Outcome noGrp = runTrackMatcher(direction, policy, targetJson, responses, artistDetails, false);
                that("qq:003oAh0u3RTCv3".equals(noGrp.selectedId) && "qq:003oAh0u3RTCv3".equals(prodResult.selectedId),
                        "65269 confirms A3 live-year rule works independently of grp[] expansion");
            }
        }
    }

    private static Outcome runTrackMatcher(String direction, Policy policy, JSONObject targetJson,
                                           JSONObject responses, Map<String, Member> artistDetails,
                                           boolean expandGrp) throws Exception {
        Track target = Track.fromJson(targetJson);
        List<QueryStep> ladder = TrackMatcher.queryLadder(
                target.title, target.artist, target.artistMembers, target.artistAliases);
        List<Candidate> accumulated = new ArrayList<>();
        Set<String> missingQueries = new LinkedHashSet<>();
        MatchTrace trace = new MatchTrace(
                "netease_to_qq".equals(direction) ? MatchTrace.Direction.NETEASE_TO_QQ : MatchTrace.Direction.QQ_TO_NETEASE,
                "fixture_runner",
                policy == Policy.WRITE ? MatchTrace.Policy.WRITE : MatchTrace.Policy.PLAYBACK,
                MatchTrace.Mode.TRACK_MATCHER,
                new MatchTrace.TargetInfo(target.id, target.title, target.artist, target.album, target.duration,
                        target.artistMembers.size(), target.artistAliases.size(),
                        MatchText.analyzeTitle(target.title).mainTitle,
                        MatchText.analyzeTitle(target.title).versionKey));
        RankResult finalResult = new RankResult(Collections.emptyList(), false, "");

        for (QueryStep step : ladder) {
            JSONArray raw = lookupResponse(responses, step.query, missingQueries);
            if ("netease_to_qq".equals(direction)) {
                accumulated.addAll(QQSource.extractMatchCandidates(raw, expandGrp, trace, step.stage.name()));
            } else {
                accumulated.addAll(extractNeteaseCandidates(raw));
            }
            RankResult mid = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, false, trace, step.stage.name());
            if (mid.hasRuleA()) {
                finalResult = mid;
                break;
            }
        }

        if (!finalResult.hasRuleA() && !"netease_to_qq".equals(direction) && !artistDetails.isEmpty()) {
            accumulated = enrichNeteaseCandidates(accumulated, artistDetails);
            RankResult afterEnrich = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, false, trace, "enriched");
            if (afterEnrich.hasRuleA()) finalResult = afterEnrich;
        }

        if (!finalResult.hasRuleA()) {
            finalResult = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, true, trace, "final");
        }

        Map<String, MatchTrace.Gate> decidingGates = decidingGatesByCandidate(trace);
        if (!finalResult.hasMatch()) {
            return new Outcome("reject", "NONE", Collections.emptyList(), missingQueries, decidingGates);
        }
        List<String> top3 = new ArrayList<>();
        int limit = policy == Policy.WRITE ? 1 : MatchRules.MAX_PLAYBACK_CANDIDATES;
        for (int i = 0; i < finalResult.hits.size() && i < limit; i++) {
            top3.add(finalResult.hits.get(i).candidate.id);
        }
        RankedHit top = finalResult.hits.get(0);
        return new Outcome(top.candidate.id, top.rule.name(), top3, missingQueries, decidingGates);
    }

    private static Outcome runProductionMatcher(String direction, Policy policy, JSONObject targetJson,
                                                JSONObject responses, Map<String, Member> artistDetails)
            throws Exception {
        Set<String> missingQueries = new LinkedHashSet<>();
        List<MatchTrace> captured = new ArrayList<>();
        if ("netease_to_qq".equals(direction)) {
            Track target = Track.fromJson(targetJson);
            List<String> vkeyMids = new ArrayList<>();
            QQSource.Lookup fake = new QQSource.Lookup() {
                @Override public JSONArray search(String query) {
                    return lookupResponse(responses, query, missingQueries);
                }
                @Override public JSONObject detail(String mid) { return null; }
                @Override public List<String> aliases(Track t) { return t.artistAliases; }
                @Override public List<Member> artistMembers(Track t) { return t.artistMembers; }
                @Override public JSONObject vkey(JSONObject params) throws Exception {
                    String mid = params.getJSONArray("songmid").getString(0);
                    vkeyMids.add("qq:" + mid);
                    JSONArray rows = new JSONArray();
                    JSONArray files = params.getJSONArray("filename");
                    for (int i = 0; i < files.length(); i++) {
                        rows.put(new JSONObject().put("filename", files.getString(i))
                                .put("songmid", mid).put("result", 0).put("purl", ""));
                    }
                    return new JSONObject().put("retcode", 0)
                            .put("sip", new JSONArray().put("https://a.qqmusic.qq.com/"))
                            .put("midurlinfo", rows);
                }
            };
            try {
                QQSource.lookupAttempts(target, fake, "0", captured::add);
            } catch (QQSource.ResolveException expected) {
                if (!"match_not_found".equals(expected.code) && !"qq_rights_unavailable".equals(expected.code)) {
                    throw expected;
                }
            }
            MatchTrace prodTrace = captured.isEmpty() ? null : captured.get(0);
            Map<String, MatchTrace.Gate> prodGates = decidingGatesByCandidate(prodTrace);
            if (vkeyMids.isEmpty()) {
                return new Outcome("reject", "NONE", Collections.emptyList(), missingQueries, prodGates);
            }
            String selected = vkeyMids.get(0);
            return new Outcome(selected, decidingRuleForCandidate(prodTrace, selected), vkeyMids, missingQueries, prodGates);
        }

        MatchTrace.Policy tracePolicy = policy == Policy.PLAYBACK
                ? MatchTrace.Policy.PLAYBACK : MatchTrace.Policy.WRITE;
        JSONObject res = MusicApi.matchTrack(targetJson,
                query -> lookupResponse(responses, query, missingQueries),
                id -> artistDetails.get(id),
                policy == Policy.PLAYBACK ? "netease_playback" : "netease_match_api",
                tracePolicy, captured::add);
        MatchTrace prodTrace = captured.isEmpty() ? null : captured.get(0);
        Map<String, MatchTrace.Gate> prodGates = decidingGatesByCandidate(prodTrace);
        if (!res.optBoolean("matched", false)) {
            return new Outcome("reject", "NONE", Collections.emptyList(), missingQueries, prodGates);
        }
        String id = res.optString("id", "");
        return new Outcome(id, decidingRuleForCandidate(prodTrace, id), Collections.singletonList(id), missingQueries, prodGates);
    }

    /** Extracts the accepted rule (A/B) for {@code selectedId} from the deciding (final) evaluation round in the trace. */
    private static String decidingRuleForCandidate(MatchTrace trace, String selectedId) {
        if (trace == null || selectedId == null || selectedId.isEmpty() || trace.candidates().isEmpty()) {
            return "NONE";
        }
        String decidingRound = "";
        for (MatchTrace.CandidateRecord rec : trace.candidates()) {
            if (rec.outcome != MatchTrace.Outcome.FILTERED) decidingRound = rec.round;
        }
        for (MatchTrace.CandidateRecord rec : trace.candidates()) {
            if (decidingRound.equals(rec.round) && rec.outcome == MatchTrace.Outcome.ACCEPTED
                    && selectedId.equals(rec.candidateId)) {
                return rec.rule.name();
            }
        }
        return "NONE";
    }

    /** Extracts each candidate's failed gate strictly from the deciding (final) evaluation round in the trace. */
    private static Map<String, MatchTrace.Gate> decidingGatesByCandidate(MatchTrace trace) {
        if (trace == null || trace.candidates().isEmpty()) return Collections.emptyMap();
        String decidingRound = "";
        for (MatchTrace.CandidateRecord rec : trace.candidates()) {
            if (rec.outcome != MatchTrace.Outcome.FILTERED) decidingRound = rec.round;
        }
        Map<String, MatchTrace.Gate> gates = new LinkedHashMap<>();
        for (MatchTrace.CandidateRecord rec : trace.candidates()) {
            if (decidingRound.equals(rec.round) && rec.outcome == MatchTrace.Outcome.REJECTED
                    && rec.failedGate != MatchTrace.Gate.NONE && !rec.candidateId.isEmpty()) {
                gates.put(rec.candidateId, rec.failedGate);
            }
        }
        return gates;
    }

    private static JSONArray lookupResponse(JSONObject responses, String query, Set<String> missingQueries) {
        if (responses == null || query == null) return new JSONArray();
        JSONArray direct = responses.optJSONArray(query);
        if (direct != null) return direct;
        String needle = query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        JSONArray names = responses.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i, "");
            if (key.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).equals(needle)) {
                JSONArray found = responses.optJSONArray(key);
                if (found != null) return found;
            }
        }
        missingQueries.add(query);
        return new JSONArray();
    }

    private static Map<String, Member> parseArtistDetails(JSONObject raw) {
        if (raw == null) return Collections.emptyMap();
        Map<String, Member> map = new LinkedHashMap<>();
        JSONArray keys = raw.names();
        for (int i = 0; keys != null && i < keys.length(); i++) {
            String id = keys.optString(i, "");
            JSONObject obj = raw.optJSONObject(id);
            if (id.isEmpty() || obj == null) continue;
            List<String> aliases = new ArrayList<>();
            JSONArray arr = obj.optJSONArray("aliases");
            for (int j = 0; arr != null && j < arr.length(); j++) {
                String a = arr.optString(j, "");
                if (!a.isEmpty()) aliases.add(a);
            }
            map.put(id, new Member(obj.optString("name", ""), id, aliases));
        }
        return map;
    }

    private static List<Candidate> enrichNeteaseCandidates(List<Candidate> input, Map<String, Member> details) {
        List<Candidate> out = new ArrayList<>(input.size());
        for (Candidate c : input) {
            List<Member> enrichedMembers = new ArrayList<>(c.artistMembers.size());
            boolean changed = false;
            for (Member m : c.artistMembers) {
                Member fetched = m.providerId.isEmpty() ? null : details.get(m.providerId);
                Member merged = fetched == null ? m : MusicApi.withOfficialAliases(m, fetched);
                if (merged != m) changed = true;
                enrichedMembers.add(merged);
            }
            if (changed) {
                out.add(new Candidate(c.id, c.mediaMid, c.title, c.artist, enrichedMembers, c.artistAliases,
                        c.album, c.durationMs, c.listIndex, c.grpIndex, c.parentMid, c.track));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    private static List<Candidate> extractNeteaseCandidates(JSONArray songs) {
        List<Candidate> out = new ArrayList<>();
        if (songs == null) return out;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            String id = song.optString("id", "");
            if (!NetEaseAuthState.hasPositiveUserId(id)) continue;
            JSONArray ar = song.optJSONArray("ar");
            List<Member> members = ArtistAliases.membersFromArtists(ar);
            StringBuilder artistNames = new StringBuilder();
            for (int j = 0; ar != null && j < ar.length(); j++) {
                JSONObject a = ar.optJSONObject(j);
                if (a == null) continue;
                String name = a.optString("name", "").trim();
                if (name.isEmpty()) continue;
                if (artistNames.length() > 0) artistNames.append(" / ");
                artistNames.append(name);
            }
            JSONObject al = song.optJSONObject("al");
            String album = al == null ? "" : al.optString("name", "");
            out.add(new Candidate(id, "", song.optString("name", ""), artistNames.toString(),
                    members, ArtistAliases.fromArtists(ar), album, song.optLong("dt", 0L), i, -1, "", null));
        }
        return out;
    }
}
