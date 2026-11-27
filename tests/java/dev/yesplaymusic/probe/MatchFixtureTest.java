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
 * Distinguishes {@code direct_replay}, {@code curated_real_excerpt}, and {@code synthetic_variant}
 * fixtures, tracks unrecorded queries explicitly, verifies expected gates via {@link MatchTrace} on
 * rejections, and compares {@link TrackMatcher} side-by-side against the legacy matcher.
 */
final class MatchFixtureTest {
    private static final File FIXTURE_DIR = new File("tests/fixtures/match");

    private MatchFixtureTest() {}

    static final class Outcome {
        final String selectedId;
        final String rule;
        final List<String> top3;
        final Set<String> missingQueries;
        final Set<MatchTrace.Gate> observedGates;

        Outcome(String selectedId, String rule, List<String> top3,
                Set<String> missingQueries, Set<MatchTrace.Gate> observedGates) {
            this.selectedId = selectedId;
            this.rule = rule;
            this.top3 = top3;
            this.missingQueries = missingQueries;
            this.observedGates = observedGates;
        }
    }

    static void run() throws Exception {
        File[] files = FIXTURE_DIR.listFiles((dir, name) -> name.endsWith(".json"));
        that(files != null && files.length >= 16, "match fixture suite loaded (" + (files == null ? 0 : files.length) + ")");
        Arrays.sort(files, Comparator.comparing(File::getName));

        boolean printReport = Boolean.getBoolean("ypm.shadow.report");
        List<String> shadowLines = new ArrayList<>();

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
            boolean expandGrp = fixture.optBoolean("expandGrp", false);
            JSONObject targetJson = fixture.getJSONObject("target");
            JSONObject responses = fixture.getJSONObject("responses");
            Map<String, Member> artistDetails = parseArtistDetails(fixture.optJSONObject("artistDetails"));

            Outcome newResult = runTrackMatcher(direction, policy, targetJson, responses, artistDetails, expandGrp);
            Outcome legacyResult = runLegacyMatcher(direction, targetJson, responses, artistDetails, false);

            that(expect.equals(newResult.selectedId),
                    "fixture " + name + " (" + ruleLabel + "): expected " + expect + ", got " + newResult.selectedId);
            if (!expectRule.isEmpty()) {
                that(expectRule.equals(newResult.rule),
                        "fixture " + name + ": expected rule " + expectRule + ", got " + newResult.rule);
            }
            if (!expectGate.isEmpty()) {
                MatchTrace.Gate requiredGate = MatchTrace.Gate.valueOf(expectGate);
                that(newResult.observedGates.contains(requiredGate),
                        "fixture " + name + ": expected rejected gate " + requiredGate
                                + ", observed " + newResult.observedGates);
            }
            if ("direct_replay".equals(kind)) {
                that(newResult.missingQueries.isEmpty(),
                        "direct_replay fixture " + name + " executed unrecorded queries: " + newResult.missingQueries);
            }

            JSONArray expectTop3 = fixture.optJSONArray("expectTop3");
            if (expectTop3 != null) {
                List<String> expectedList = new ArrayList<>();
                for (int i = 0; i < expectTop3.length(); i++) expectedList.add(expectTop3.getString(i));
                that(newResult.top3.equals(expectedList),
                        "fixture " + name + ": expected top3 " + expectedList + ", got " + newResult.top3);
            }

            boolean diff = !legacyResult.selectedId.equals(newResult.selectedId)
                    || !legacyResult.top3.equals(newResult.top3);
            String legacyStatus = legacyResult.missingQueries.isEmpty()
                    ? legacyResult.selectedId
                    : legacyResult.selectedId + "[missing:" + legacyResult.missingQueries + "]";
            String newStatus = newResult.missingQueries.isEmpty()
                    ? newResult.selectedId + "(" + newResult.rule + ")"
                    : newResult.selectedId + "(" + newResult.rule + ")[missing:" + newResult.missingQueries + "]";
            shadowLines.add(String.format("%s [%s] | dir=%s | policy=%s | rule=%s | legacy=%s top3=%s | new=%s top3=%s | diff=%s",
                    name, kind, direction, policy, ruleLabel,
                    legacyStatus, legacyResult.top3,
                    newStatus, newResult.top3,
                    diff ? "DIFF" : "SAME"));

            if (name.startsWith("yueyu_canpian_") || "fml_forward_no_alias_rule_b".equals(name)) {
                Outcome legacyNoGrp = legacyResult;
                Outcome legacyWithGrp = runLegacyMatcher(direction, targetJson, responses, artistDetails, true);
                Outcome newNoGrp = runTrackMatcher(direction, policy, targetJson, responses, artistDetails, false);
                Outcome newWithGrp = runTrackMatcher(direction, policy, targetJson, responses, artistDetails, true);
                shadowLines.add(String.format(
                        "  [2x2 %s] legacy(noGrp)=%s legacy(+Grp)=%s | new(noGrp)=%s(%s) new(+Grp)=%s(%s)",
                        name,
                        legacyNoGrp.selectedId, legacyWithGrp.selectedId,
                        newNoGrp.selectedId, newNoGrp.rule,
                        newWithGrp.selectedId, newWithGrp.rule));

                if ("yueyu_canpian_remaster_1411495700".equals(name)) {
                    that("reject".equals(legacyNoGrp.selectedId)
                            && "qq:000SX6cK3Se2WV".equals(legacyWithGrp.selectedId)
                            && "reject".equals(newNoGrp.selectedId)
                            && "qq:000SX6cK3Se2WV".equals(newWithGrp.selectedId),
                            "1411495700 2x2 confirms grp[] expansion recovers studio master without relaxing 5s duration gate");
                } else if ("yueyu_canpian_live_65269".equals(name)) {
                    that("reject".equals(legacyNoGrp.selectedId)
                            && "reject".equals(legacyWithGrp.selectedId)
                            && "qq:003oAh0u3RTCv3".equals(newNoGrp.selectedId)
                            && "qq:003oAh0u3RTCv3".equals(newWithGrp.selectedId),
                            "65269 2x2 confirms A3 live-year rule fixes 2007 Live independently of grp[] expansion");
                } else if ("fml_forward_no_alias_rule_b".equals(name)) {
                    that("qq:0005toRJ3QqtAx".equals(newNoGrp.selectedId) && "B".equals(newNoGrp.rule)
                            && "reject".equals(newWithGrp.selectedId),
                            "FML without aliases: Rule B passes without grp[] and strictly rejects Explicit+Clean 2-MID grp[] pair");
                }
            }
        }

        if (printReport) {
            System.out.println("=== SHADOW COMPARISON REPORT (" + files.length + " fixtures) ===");
            for (String line : shadowLines) System.out.println(line);
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
                accumulated.addAll(extractNeteaseCandidates(raw, Collections.emptyMap()));
            }
            RankResult mid = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, false, trace, step.stage.name());
            if (mid.hasRuleA()) {
                finalResult = mid;
                break;
            }
        }

        // For reverse matches with artistDetails, simulate official artist detail enrichment before final rank.
        if (!finalResult.hasRuleA() && !"netease_to_qq".equals(direction) && !artistDetails.isEmpty()) {
            List<Candidate> enriched = enrichNeteaseCandidates(accumulated, artistDetails);
            accumulated = enriched;
            RankResult afterEnrich = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, false, trace, "enriched");
            if (afterEnrich.hasRuleA()) {
                finalResult = afterEnrich;
            }
        }

        if (!finalResult.hasRuleA()) {
            finalResult = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, true, trace, "final");
        }

        Set<MatchTrace.Gate> gates = new LinkedHashSet<>();
        for (MatchTrace.CandidateRecord rec : trace.candidates()) {
            if (rec.outcome == MatchTrace.Outcome.REJECTED && rec.failedGate != MatchTrace.Gate.NONE) {
                gates.add(rec.failedGate);
            }
        }

        if (!finalResult.hasMatch()) {
            return new Outcome("reject", "NONE", Collections.emptyList(), missingQueries, gates);
        }
        List<String> top3 = new ArrayList<>();
        int limit = policy == Policy.WRITE ? 1 : MatchRules.MAX_PLAYBACK_CANDIDATES;
        for (int i = 0; i < finalResult.hits.size() && i < limit; i++) {
            top3.add(finalResult.hits.get(i).candidate.id);
        }
        RankedHit top = finalResult.hits.get(0);
        return new Outcome(top.candidate.id, top.rule.name(), top3, missingQueries, gates);
    }

    private static Outcome runLegacyMatcher(String direction, JSONObject targetJson,
                                            JSONObject responses, Map<String, Member> artistDetails,
                                            boolean expandGrp) throws Exception {
        Set<String> missingQueries = new LinkedHashSet<>();
        if ("netease_to_qq".equals(direction)) {
            Track target = Track.fromJson(targetJson);
            List<String> vkeyMids = new ArrayList<>();
            QQSource.Lookup fake = new QQSource.Lookup() {
                @Override public JSONArray search(String query) throws Exception {
                    JSONArray raw = lookupResponse(responses, query, missingQueries);
                    if (!expandGrp) return raw;
                    JSONArray flat = new JSONArray();
                    for (int i = 0; i < raw.length(); i++) {
                        JSONObject item = raw.optJSONObject(i);
                        if (item == null) continue;
                        flat.put(item);
                        JSONArray grp = item.optJSONArray("grp");
                        for (int j = 0; grp != null && j < grp.length(); j++) {
                            JSONObject child = grp.optJSONObject(j);
                            if (child != null) flat.put(child);
                        }
                    }
                    return flat;
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
                QQSource.lookupAttempts(target, fake, "0");
            } catch (QQSource.ResolveException expected) {
                if (!"match_not_found".equals(expected.code) && !"qq_rights_unavailable".equals(expected.code)) {
                    throw expected;
                }
            }
            if (vkeyMids.isEmpty()) {
                return new Outcome("reject", "LEGACY_NONE", Collections.emptyList(), missingQueries, Collections.emptySet());
            }
            return new Outcome(vkeyMids.get(0), "LEGACY", vkeyMids, missingQueries, Collections.emptySet());
        }

        JSONObject res = MusicApi.matchTrack(targetJson,
                query -> lookupResponse(responses, query, missingQueries),
                id -> artistDetails.get(id));
        if (!res.optBoolean("matched", false)) {
            return new Outcome("reject", "LEGACY_NONE", Collections.emptyList(), missingQueries, Collections.emptySet());
        }
        String id = res.optString("id", "");
        return new Outcome(id, "LEGACY", Collections.singletonList(id), missingQueries, Collections.emptySet());
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

    private static List<Candidate> extractNeteaseCandidates(JSONArray songs, Map<String, Member> details) {
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
