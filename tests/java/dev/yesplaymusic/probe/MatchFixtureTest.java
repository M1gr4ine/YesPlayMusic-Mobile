package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import dev.yesplaymusic.probe.ArtistAliases.Member;
import dev.yesplaymusic.probe.TrackMatcher.Candidate;
import dev.yesplaymusic.probe.TrackMatcher.Policy;
import dev.yesplaymusic.probe.TrackMatcher.QueryStep;
import dev.yesplaymusic.probe.TrackMatcher.RankResult;
import dev.yesplaymusic.probe.TrackMatcher.RankedHit;
import dev.yesplaymusic.probe.TrackMatcher.Rule;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Table-driven runner for real recorded match fixtures under {@code tests/fixtures/match/*.json}.
 * Runs both the legacy matcher and {@link TrackMatcher} side-by-side (shadow comparison) and verifies
 * all expected outcomes, rules, top-3 ordering, and grp[] expansion vs rule-change separation.
 */
final class MatchFixtureTest {
    private static final File FIXTURE_DIR = new File("tests/fixtures/match");

    private MatchFixtureTest() {}

    static final class Outcome {
        final String selectedId;
        final String rule;
        final List<String> top3;

        Outcome(String selectedId, String rule, List<String> top3) {
            this.selectedId = selectedId;
            this.rule = rule;
            this.top3 = top3;
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
            String direction = fixture.getString("direction");
            Policy policy = Policy.valueOf(fixture.getString("policy"));
            String ruleLabel = fixture.getString("rule");
            String expect = fixture.getString("expect");
            String expectRule = fixture.optString("expectRule", "");
            boolean expandGrp = fixture.optBoolean("expandGrp", false);
            JSONObject targetJson = fixture.getJSONObject("target");
            JSONObject responses = fixture.getJSONObject("responses");

            Outcome newResult = runTrackMatcher(direction, policy, targetJson, responses, expandGrp);
            Outcome legacyResult = runLegacyMatcher(direction, targetJson, responses, false);

            that(expect.equals(newResult.selectedId),
                    "fixture " + name + " (" + ruleLabel + "): expected " + expect + ", got " + newResult.selectedId);
            if (!expectRule.isEmpty()) {
                that(expectRule.equals(newResult.rule),
                        "fixture " + name + ": expected rule " + expectRule + ", got " + newResult.rule);
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
            shadowLines.add(String.format("%s | dir=%s | policy=%s | rule=%s | legacy=%s top3=%s | new=%s(%s) top3=%s | diff=%s",
                    name, direction, policy, ruleLabel,
                    legacyResult.selectedId, legacyResult.top3,
                    newResult.selectedId, newResult.rule, newResult.top3,
                    diff ? "DIFF" : "SAME"));

            // For Yueyu Canpian & FML fixtures, also evaluate the 2x2 matrix (grp expansion vs matcher rule).
            if (name.startsWith("yueyu_canpian_") || "fml_forward_no_alias_rule_b".equals(name)) {
                Outcome legacyNoGrp = legacyResult;
                Outcome legacyWithGrp = runLegacyMatcher(direction, targetJson, responses, true);
                Outcome newNoGrp = runTrackMatcher(direction, policy, targetJson, responses, false);
                Outcome newWithGrp = runTrackMatcher(direction, policy, targetJson, responses, true);
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
                                           JSONObject responses, boolean expandGrp) throws Exception {
        Track target = Track.fromJson(targetJson);
        List<QueryStep> ladder = TrackMatcher.queryLadder(
                target.title, target.artist, target.artistMembers, target.artistAliases);
        List<Candidate> accumulated = new ArrayList<>();
        RankResult finalResult = new RankResult(Collections.emptyList(), false, "");

        for (QueryStep step : ladder) {
            JSONArray raw = lookupResponse(responses, step.query);
            if ("netease_to_qq".equals(direction)) {
                accumulated.addAll(QQSource.extractMatchCandidates(raw, expandGrp, null, step.stage.name()));
            } else {
                accumulated.addAll(extractNeteaseCandidates(raw));
            }
            RankResult mid = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, false, null, step.stage.name());
            if (mid.hasRuleA()) {
                finalResult = mid;
                break;
            }
        }
        if (!finalResult.hasRuleA()) {
            finalResult = TrackMatcher.rankDetailed(target, target.artistMembers, target.artistAliases,
                    accumulated, policy, true, null, "final");
        }
        if (!finalResult.hasMatch()) {
            return new Outcome("reject", "NONE", Collections.emptyList());
        }
        List<String> top3 = new ArrayList<>();
        int limit = policy == Policy.WRITE ? 1 : MatchRules.MAX_PLAYBACK_CANDIDATES;
        for (int i = 0; i < finalResult.hits.size() && i < limit; i++) {
            top3.add(finalResult.hits.get(i).candidate.id);
        }
        RankedHit top = finalResult.hits.get(0);
        return new Outcome(top.candidate.id, top.rule.name(), top3);
    }

    private static Outcome runLegacyMatcher(String direction, JSONObject targetJson,
                                            JSONObject responses, boolean expandGrp) throws Exception {
        if ("netease_to_qq".equals(direction)) {
            Track target = Track.fromJson(targetJson);
            List<String> vkeyMids = new ArrayList<>();
            QQSource.Lookup fake = new QQSource.Lookup() {
                @Override public JSONArray search(String query) throws Exception {
                    JSONArray raw = lookupResponse(responses, query);
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
                    // Return empty purl so lookupAttempts reveals up to top 3 candidates in legacy order.
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
            } catch (QQSource.ResolveException ignored) {
                // Expected because fake vkey returns empty purl to enumerate ranked candidates.
            }
            if (vkeyMids.isEmpty()) return new Outcome("reject", "LEGACY_NONE", Collections.emptyList());
            return new Outcome(vkeyMids.get(0), "LEGACY", vkeyMids);
        }

        // Reverse: QQ -> NetEase via legacy MusicApi.matchTrack.
        JSONObject res = MusicApi.matchTrack(targetJson, query -> lookupResponse(responses, query), id -> null);
        if (!res.optBoolean("matched", false)) {
            return new Outcome("reject", "LEGACY_NONE", Collections.emptyList());
        }
        String id = res.optString("id", "");
        return new Outcome(id, "LEGACY", Collections.singletonList(id));
    }

    private static JSONArray lookupResponse(JSONObject responses, String query) {
        if (responses == null || query == null) return new JSONArray();
        JSONArray direct = responses.optJSONArray(query);
        if (direct != null) return direct;
        String needle = query.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        JSONArray names = responses.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String key = names.optString(i, "");
            if (key.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT).equals(needle)) {
                JSONArray found = responses.optJSONArray(key);
                if (found != null) return found;
            }
        }
        return new JSONArray();
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
