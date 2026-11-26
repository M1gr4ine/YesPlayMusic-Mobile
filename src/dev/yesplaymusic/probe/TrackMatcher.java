package dev.yesplaymusic.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Single general-rule cross-source track matcher shared by NetEase -> QQ playback and QQ -> NetEase write/playback.
 *
 * <p>Hard gates:
 * <ol>
 *   <li>Normalized main title equality (allowing benign annotations, CJK subtitle translations, and
 *       single-sided live year markers only on the same non-compilation live album without competition).</li>
 *   <li>Version tag set equality (Live, Instrumental, Remix, language variants, etc. are never interchangeable).</li>
 *   <li>Duration gap &lt;= 5s when both sides report duration; missing duration requires Rule A.</li>
 * </ol>
 *
 * <p>Artist rules:
 * <ul>
 *   <li>Rule A: valid member name intersection (canonical + official aliases + bracket aliases) without
 *       same-script parenthetical conflict, requiring either full 1-to-1 coverage or lead-to-lead overlap
 *       (guest-only overlap is rejected bidirectionally). Under {@link Policy#WRITE}, partial coverage
 *       further requires one-side subset coverage, official/canonical lead evidence, and same non-compilation album.</li>
 *   <li>Rule B: when artists are disjoint (or Rule A was vetoed by same-script parenthetical conflict),
 *       accept only on the same non-compilation album, when globally unique among hard-gate passers across
 *       the completed query ladder, with no Rule-A candidate in the batch, both durations present
 *       (&lt;= 5s for playback, &lt;= 2s for write), and no structural artist-grouping conflict.</li>
 * </ul>
 */
final class TrackMatcher {
    private TrackMatcher() {}

    enum Policy {
        PLAYBACK,
        WRITE
    }

    enum Rule {
        NONE,
        A,
        B
    }

    enum QueryStage {
        ALL_ARTISTS,
        LEAD_ARTIST,
        MEMBER_ALIAS,
        BARE_TITLE
    }

    static final class QueryStep {
        final QueryStage stage;
        final String query;

        QueryStep(QueryStage stage, String query) {
            this.stage = stage;
            this.query = query;
        }
    }

    static final class Candidate {
        final String id;
        final String mediaMid;
        final String title;
        final String artist;
        final List<ArtistAliases.Member> artistMembers;
        final List<String> artistAliases;
        final String album;
        final long durationMs;
        final int listIndex;
        final int grpIndex;
        final String parentMid;
        final Track track;

        Candidate(String id, String mediaMid, String title, String artist,
                  List<ArtistAliases.Member> artistMembers, List<String> artistAliases,
                  String album, long durationMs, int listIndex, int grpIndex, String parentMid, Track track) {
            this.id = id == null ? "" : id.trim();
            this.mediaMid = mediaMid == null ? "" : mediaMid.trim();
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
            this.artistMembers = artistMembers == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(artistMembers));
            this.artistAliases = artistAliases == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(artistAliases));
            this.album = album == null ? "" : album.trim();
            this.durationMs = Math.max(0L, durationMs);
            this.listIndex = listIndex;
            this.grpIndex = grpIndex;
            this.parentMid = parentMid == null ? "" : parentMid.trim();
            this.track = track;
        }

        static Candidate fromTrack(Track track) {
            return fromTrack(track, -1, -1, "");
        }

        static Candidate fromTrack(Track track, int listIndex, int grpIndex, String parentMid) {
            if (track == null) return null;
            return new Candidate(track.id, track.mediaMid, track.title, track.artist,
                    track.artistMembers, track.artistAliases, track.album, track.duration,
                    listIndex, grpIndex, parentMid, track);
        }
    }

    static final class RankedHit {
        final Candidate candidate;
        final Rule rule;
        final boolean sameAlbum;
        final boolean fullMemberCoverage;
        final boolean oneSideSubset;
        final boolean leadMatched;
        final long durationGapMs;
        final String matchedMembersSummary;
        final List<String> artistIdentity;

        RankedHit(Candidate candidate, Rule rule, boolean sameAlbum, boolean fullMemberCoverage,
                  boolean oneSideSubset, boolean leadMatched, long durationGapMs,
                  String matchedMembersSummary, List<String> artistIdentity) {
            this.candidate = candidate;
            this.rule = rule;
            this.sameAlbum = sameAlbum;
            this.fullMemberCoverage = fullMemberCoverage;
            this.oneSideSubset = oneSideSubset;
            this.leadMatched = leadMatched;
            this.durationGapMs = durationGapMs;
            this.matchedMembersSummary = matchedMembersSummary == null ? "" : matchedMembersSummary;
            this.artistIdentity = artistIdentity;
        }
    }

    static final class RankResult {
        final List<RankedHit> hits;
        final boolean ambiguous;
        final String conflictCandidateId;

        RankResult(List<RankedHit> hits, boolean ambiguous, String conflictCandidateId) {
            this.hits = Collections.unmodifiableList(hits);
            this.ambiguous = ambiguous;
            this.conflictCandidateId = conflictCandidateId == null ? "" : conflictCandidateId;
        }

        boolean hasRuleA() {
            return !ambiguous && !hits.isEmpty() && hits.get(0).rule == Rule.A;
        }

        boolean hasMatch() {
            return !ambiguous && !hits.isEmpty();
        }
    }

    private static final class ResolvedCredit {
        final List<String> rawMemberNames;
        final List<Set<String>> visibleParts;
        final List<Set<String>> expandedParts;
        final boolean[] hasOfficialAliases;
        final List<String> identityKey;

        ResolvedCredit(List<String> rawMemberNames, List<Set<String>> visibleParts,
                       List<Set<String>> expandedParts, boolean[] hasOfficialAliases,
                       List<String> identityKey) {
            this.rawMemberNames = rawMemberNames;
            this.visibleParts = visibleParts;
            this.expandedParts = expandedParts;
            this.hasOfficialAliases = hasOfficialAliases;
            this.identityKey = identityKey;
        }

        int size() {
            return visibleParts.size();
        }
    }

    private static final class Eval {
        final Candidate candidate;
        final MatchText.TitleInfo titleInfo;
        final Long durationGap;
        final boolean sameAlbum;
        final boolean compilationAlbum;
        final boolean liveYearFallback;

        boolean passedHardGates;
        MatchTrace.Gate failedGate = MatchTrace.Gate.NONE;
        String conflictCandidateId = "";
        String detail = "";

        ResolvedCredit credit;
        boolean groupingConflict;
        boolean ruleAAccepted;
        boolean ruleAFullCoverage;
        boolean ruleAOneSideSubset;
        boolean ruleALeadMatched;
        boolean ruleAGuestOnly;
        boolean ruleASameScriptVeto;
        boolean ruleAEligibleForBatchBlock;
        String matchedMembers = "";

        boolean ruleBAccepted;

        Eval(Candidate candidate, MatchText.TitleInfo titleInfo, Long durationGap,
             boolean sameAlbum, boolean compilationAlbum, boolean liveYearFallback) {
            this.candidate = candidate;
            this.titleInfo = titleInfo;
            this.durationGap = durationGap;
            this.sameAlbum = sameAlbum;
            this.compilationAlbum = compilationAlbum;
            this.liveYearFallback = liveYearFallback;
        }
    }

    /** Ranks candidates using the unified TrackMatcher rules on a completed candidate pool. */
    static List<RankedHit> rank(Track target, List<Candidate> hits, Policy policy) {
        if (target == null) return Collections.emptyList();
        return rankDetailed(target, target.artistMembers, target.artistAliases, hits, policy, true, null, "rank").hits;
    }

    /**
     * Evaluates and ranks candidates with optional Rule-B gating (pass {@code allowRuleB = false} during
     * intermediate query ladder stages so Rule B only fires after the entire ladder completes) and
     * optional {@link MatchTrace} recording.
     */
    static RankResult rankDetailed(Track target, List<ArtistAliases.Member> targetMembers,
                                   List<String> targetAliases, List<Candidate> hits, Policy policy,
                                   boolean allowRuleB, MatchTrace trace, String round) {
        if (target == null || hits == null || hits.isEmpty()) {
            return new RankResult(Collections.emptyList(), false, "");
        }
        Policy activePolicy = policy == null ? Policy.PLAYBACK : policy;
        MatchText.TitleInfo targetTitle = MatchText.analyzeTitle(target.title);
        boolean targetKnownArtist = MatchText.knownArtist(target.artist);
        ResolvedCredit targetCredit = targetKnownArtist
                ? resolveCredit(target.artist, targetMembers, targetAliases) : null;

        // Deduplicate by candidate ID while keeping first-seen order and preferring richer member metadata.
        List<Candidate> deduped = deduplicate(hits, trace, target, targetTitle, round);
        if (deduped.isEmpty()) {
            return new RankResult(Collections.emptyList(), false, "");
        }

        List<MatchText.TitleInfo> candTitles = new ArrayList<>(deduped.size());
        for (Candidate c : deduped) {
            candTitles.add(MatchText.analyzeTitle(c.title));
        }

        List<Eval> evals = new ArrayList<>(deduped.size());
        int hardGatePassers = 0;
        String firstHardGateId = "";
        String secondHardGateId = "";

        // Pass 1: Hard gates (0: known artist, 1: main title + A3 live year, 2: version tags, 3: duration <= 5s).
        for (int i = 0; i < deduped.size(); i++) {
            Candidate cand = deduped.get(i);
            MatchText.TitleInfo candTitle = candTitles.get(i);
            Long gap = MatchTrace.durationGapOrNull(target.duration, cand.durationMs);
            boolean compAlbum = MatchText.isCompilationAlbum(target.album)
                    || MatchText.isCompilationAlbum(cand.album);
            boolean sameAlbum = MatchText.sameAlbumTitle(target.album, cand.album);
            boolean sameNonCompAlbum = sameAlbum && !compAlbum;
            boolean liveYearFallback = false;

            Eval eval = new Eval(cand, candTitle, gap, sameAlbum, compAlbum, false);
            evals.add(eval);

            if (!targetKnownArtist || targetCredit == null || !MatchText.knownArtist(cand.artist)) {
                eval.failedGate = MatchTrace.Gate.UNKNOWN_ARTIST;
                continue;
            }
            if (targetTitle.mainTitle.isEmpty() || candTitle.mainTitle.isEmpty()) {
                eval.failedGate = MatchTrace.Gate.TITLE;
                continue;
            }
            if (!targetTitle.versionTags.equals(candTitle.versionTags)) {
                eval.failedGate = MatchTrace.Gate.VERSION;
                continue;
            }
            if (!targetTitle.mainTitle.equals(candTitle.mainTitle)) {
                eval.failedGate = MatchTrace.Gate.TITLE;
                continue;
            }
            if (MatchText.TitleInfo.LIVE_YEAR_CONFLICT.equals(targetTitle.liveYear)
                    || MatchText.TitleInfo.LIVE_YEAR_CONFLICT.equals(candTitle.liveYear)) {
                eval.failedGate = MatchTrace.Gate.VERSION;
                eval.detail = "live_year_conflict";
                continue;
            }

            if (!targetTitle.liveYear.equals(candTitle.liveYear)) {
                if (!targetTitle.liveYear.isEmpty() && !candTitle.liveYear.isEmpty()) {
                    eval.failedGate = MatchTrace.Gate.VERSION;
                    eval.detail = "live_year_mismatch";
                    continue;
                }
                // One-sided live year marker (A3): allowed only on the same non-compilation live album
                // with no competing live session/year in the batch.
                if (!(targetTitle.hasLiveTag && candTitle.hasLiveTag)
                        || !sameNonCompAlbum
                        || !(MatchText.isLiveAlbum(target.album) || MatchText.isLiveAlbum(cand.album))) {
                    eval.failedGate = MatchTrace.Gate.TITLE;
                    eval.detail = "live_year_requires_same_live_album";
                    continue;
                }
                String liveConflictId = findLiveYearCompetition(target, targetTitle, cand, deduped, candTitles);
                if (!liveConflictId.isEmpty()) {
                    eval.failedGate = MatchTrace.Gate.VERSION;
                    eval.conflictCandidateId = liveConflictId;
                    eval.detail = "live_year_competition";
                    continue;
                }
                liveYearFallback = true;
            }

            if (gap != null && gap > MatchRules.PLAYBACK_MAX_DURATION_GAP_MS) {
                eval.failedGate = MatchTrace.Gate.DURATION;
                eval.detail = "gap>5000ms";
                continue;
            }

            Eval passed = new Eval(cand, candTitle, gap, sameAlbum, compAlbum, liveYearFallback);
            passed.passedHardGates = true;
            if (liveYearFallback) passed.detail = "live_year_same_album";
            evals.set(i, passed);

            hardGatePassers++;
            if (firstHardGateId.isEmpty()) firstHardGateId = cand.id;
            else if (secondHardGateId.isEmpty()) secondHardGateId = cand.id;
        }

        // Pass 2: Evaluate artist credits, structural grouping conflicts, and Rule A.
        boolean anyRuleAInBatch = false;
        String firstRuleAId = "";
        for (Eval eval : evals) {
            if (!eval.passedHardGates) continue;
            ResolvedCredit candCredit = resolveCredit(
                    eval.candidate.artist, eval.candidate.artistMembers, eval.candidate.artistAliases);
            eval.credit = candCredit;
            if (candCredit == null) {
                eval.failedGate = MatchTrace.Gate.UNKNOWN_ARTIST;
                continue;
            }
            if (hasGroupingConflict(targetCredit, candCredit)) {
                eval.groupingConflict = true;
                eval.failedGate = MatchTrace.Gate.VETO;
                eval.detail = appendDetail(eval.detail, "grouping_conflict");
                continue;
            }
            evaluateRuleA(targetCredit, candCredit, eval, activePolicy);
            if (eval.ruleAAccepted || eval.ruleAEligibleForBatchBlock) {
                if (!anyRuleAInBatch) {
                    anyRuleAInBatch = true;
                    firstRuleAId = eval.candidate.id;
                }
            }
        }

        // Pass 3: Evaluate Rule B for non-Rule-A hard-gate passers (only when allowRuleB is true).
        for (Eval eval : evals) {
            if (!eval.passedHardGates || eval.groupingConflict || eval.ruleAAccepted) continue;
            if (eval.ruleAGuestOnly) {
                eval.failedGate = MatchTrace.Gate.ARTIST;
                eval.detail = appendDetail(eval.detail, "guest_only_overlap");
                continue;
            }
            if (eval.failedGate == MatchTrace.Gate.WRITE_POLICY) {
                // Lead overlapped under Policy.WRITE but failed write policy (e.g. different album or
                // contradicting collaborator); artist sets are NOT disjoint, so never fall through to Rule B.
                continue;
            }
            if (!allowRuleB) {
                eval.failedGate = MatchTrace.Gate.ARTIST;
                eval.detail = appendDetail(eval.detail, "rule_b_deferred");
                continue;
            }
            if (eval.durationGap == null) {
                eval.failedGate = MatchTrace.Gate.DURATION;
                eval.detail = appendDetail(eval.detail, "missing_duration_requires_a");
                continue;
            }
            if (activePolicy == Policy.WRITE && eval.durationGap > MatchRules.WRITE_RULE_B_MAX_DURATION_GAP_MS) {
                eval.failedGate = MatchTrace.Gate.DURATION;
                eval.detail = appendDetail(eval.detail, "write_b>2000ms");
                continue;
            }
            if (eval.compilationAlbum) {
                eval.failedGate = MatchTrace.Gate.COMPILATION;
                continue;
            }
            if (!eval.sameAlbum) {
                eval.failedGate = MatchTrace.Gate.ARTIST;
                eval.detail = appendDetail(eval.detail, "rule_b_requires_same_album");
                continue;
            }
            if (anyRuleAInBatch) {
                eval.failedGate = MatchTrace.Gate.COMPETING_A;
                eval.conflictCandidateId = firstRuleAId;
                continue;
            }
            if (hardGatePassers != 1) {
                eval.failedGate = MatchTrace.Gate.NOT_UNIQUE;
                eval.conflictCandidateId = eval.candidate.id.equals(firstHardGateId)
                        ? secondHardGateId : firstHardGateId;
                continue;
            }
            eval.ruleBAccepted = true;
            eval.failedGate = MatchTrace.Gate.NONE;
            if (eval.ruleASameScriptVeto) {
                eval.detail = appendDetail(eval.detail, "same_script_fallback_b");
            }
        }

        // Collect accepted hits and sort according to policy.
        List<RankedHit> ranked = new ArrayList<>();
        Map<String, Eval> evalById = new LinkedHashMap<>();
        for (Eval eval : evals) {
            evalById.put(eval.candidate.id, eval);
            if (eval.ruleAAccepted || eval.ruleBAccepted) {
                Rule rule = eval.ruleAAccepted ? Rule.A : Rule.B;
                long sortGap = eval.durationGap == null ? Long.MAX_VALUE : eval.durationGap;
                ranked.add(new RankedHit(
                        eval.candidate, rule, eval.sameAlbum, eval.ruleAFullCoverage,
                        eval.ruleAOneSideSubset, eval.ruleALeadMatched, sortGap,
                        eval.matchedMembers, eval.credit.identityKey));
            }
        }
        ranked.sort(comparatorFor(activePolicy));

        // Under Policy.WRITE, reject if top-tier candidates tie with distinct artist identities.
        boolean ambiguous = false;
        String ambiguousConflictId = "";
        if (activePolicy == Policy.WRITE && ranked.size() > 1) {
            RankedHit top = ranked.get(0);
            for (int i = 1; i < ranked.size(); i++) {
                RankedHit other = ranked.get(i);
                if (!sameWriteTier(top, other)) break;
                if (!top.artistIdentity.equals(other.artistIdentity)) {
                    ambiguous = true;
                    ambiguousConflictId = other.candidate.id;
                    break;
                }
            }
        }

        if (trace != null) {
            emitTrace(trace, target, targetTitle, round, evals, ranked, ambiguous, ambiguousConflictId);
        }

        if (ambiguous) {
            return new RankResult(Collections.emptyList(), true, ambiguousConflictId);
        }
        return new RankResult(ranked, false, "");
    }

    /**
     * Unified bounded query ladder shared by NetEase -> QQ and QQ -> NetEase:
     * {@code title+all artists -> title+lead artist -> title+member aliases (<=2) -> title}.
     */
    static List<QueryStep> queryLadder(String title, String artist,
                                       List<ArtistAliases.Member> members, List<String> flatAliases) {
        String rawTitle = title == null ? "" : title.trim();
        if (rawTitle.isEmpty() || rawTitle.length() > 200) return Collections.emptyList();
        String cleanTitle = MatchText.cleanSearchTitle(rawTitle);
        if (cleanTitle.isEmpty()) return Collections.emptyList();

        String rawArtist = artist == null ? "" : artist.trim();
        String cleanArtist = MatchText.cleanSearchArtist(rawArtist);
        List<QueryStep> steps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<Set<String>> visibleParts = MatchText.artistParts(rawArtist);
        boolean completeStructured = members != null && !members.isEmpty()
                && isCompleteConsistentMembers(rawArtist, members);
        int memberCount = completeStructured ? members.size() : visibleParts.size();

        // Stage 1: title + all artists (preserve structured member names like "AC/DC" without splitting internal "/").
        if (completeStructured) {
            StringBuilder allMembers = new StringBuilder();
            for (ArtistAliases.Member m : members) {
                String base = stripBrackets(m.name);
                String part = base.isEmpty() ? m.name.trim() : base;
                if (part.isEmpty()) continue;
                if (allMembers.length() > 0) allMembers.append(' ');
                allMembers.append(part);
            }
            if (allMembers.length() > 0) {
                addQueryStep(steps, seen, QueryStage.ALL_ARTISTS, cleanTitle + " " + allMembers);
            }
        } else if (!cleanArtist.isEmpty()) {
            addQueryStep(steps, seen, QueryStage.ALL_ARTISTS, MatchText.keyword(cleanTitle, cleanArtist));
        }

        // Stage 2: title + lead artist (only for multi-artist credits)
        if (memberCount >= 2) {
            String leadRaw = completeStructured ? members.get(0).name : firstRawPart(rawArtist);
            String cleanLead = completeStructured
                    ? (stripBrackets(leadRaw).isEmpty() ? leadRaw.trim() : stripBrackets(leadRaw))
                    : MatchText.cleanSearchArtist(leadRaw);
            if (!cleanLead.isEmpty()) {
                addQueryStep(steps, seen, QueryStage.LEAD_ARTIST, cleanTitle + " " + cleanLead);
            }
        }

        // Stage 3: title + member aliases (strictly capped at MatchRules.MAX_ALIAS_SEARCH_QUERIES = 2)
        int aliasQueriesAdded = 0;
        if (memberCount == 1) {
            List<String> candidateAliases = new ArrayList<>();
            if (completeStructured) {
                candidateAliases.addAll(members.get(0).aliases);
            } else if (members != null && members.size() == 1) {
                int[] mapped = mapConsistentMembers(visibleParts, members);
                if (mapped != null && mapped[0] == 0) candidateAliases.addAll(members.get(0).aliases);
            }
            if (flatAliases != null) candidateAliases.addAll(flatAliases);
            candidateAliases.addAll(extractBracketAliases(rawArtist));

            String canonicalToken = MatchText.normalizeArtistToken(cleanArtist);
            for (String alias : candidateAliases) {
                if (aliasQueriesAdded >= MatchRules.MAX_ALIAS_SEARCH_QUERIES) break;
                String trimmed = cleanAliasToken(alias);
                if (trimmed.isEmpty() || !isSingleArtistCredit(trimmed)) continue;
                String token = MatchText.normalizeArtistToken(trimmed);
                if (token.isEmpty() || token.equals(canonicalToken)) continue;
                if (addQueryStep(steps, seen, QueryStage.MEMBER_ALIAS, cleanTitle + " " + trimmed)) {
                    aliasQueriesAdded++;
                }
            }
        } else if (memberCount >= 2) {
            String[] canonicalParts = new String[memberCount];
            List<List<String>> memberAliasLists = new ArrayList<>(memberCount);
            for (int i = 0; i < memberCount; i++) memberAliasLists.add(new ArrayList<>());

            if (completeStructured) {
                for (int i = 0; i < memberCount; i++) {
                    canonicalParts[i] = MatchText.cleanSearchArtist(members.get(i).name);
                    memberAliasLists.get(i).addAll(members.get(i).aliases);
                    memberAliasLists.get(i).addAll(extractBracketAliases(members.get(i).name));
                }
            } else {
                List<String> rawParts = MatchText.artistRawParts(rawArtist);
                for (int i = 0; i < memberCount; i++) {
                    String rawPart = i < rawParts.size() ? rawParts.get(i) : "";
                    canonicalParts[i] = MatchText.cleanSearchArtist(rawPart);
                    memberAliasLists.get(i).addAll(extractBracketAliases(rawPart));
                }
                int[] mapped = (members != null && !members.isEmpty())
                        ? mapConsistentMembers(visibleParts, members) : null;
                if (mapped != null) {
                    for (int i = 0; i < members.size(); i++) {
                        int idx = mapped[i];
                        if (idx >= 0 && idx < memberCount) {
                            List<String> combined = new ArrayList<>(members.get(i).aliases);
                            combined.addAll(memberAliasLists.get(idx));
                            memberAliasLists.set(idx, combined);
                        }
                    }
                }
            }

            // 3a: Substitute first valid alias per member into the full collaboration query.
            String[] substituted = new String[memberCount];
            boolean anySubstituted = false;
            for (int i = 0; i < memberCount; i++) {
                String chosen = firstDistinctAlias(canonicalParts[i], memberAliasLists.get(i));
                if (!chosen.isEmpty()) {
                    substituted[i] = chosen;
                    anySubstituted = true;
                } else {
                    substituted[i] = canonicalParts[i];
                }
            }
            if (anySubstituted && aliasQueriesAdded < MatchRules.MAX_ALIAS_SEARCH_QUERIES) {
                StringBuilder joined = new StringBuilder();
                for (String part : substituted) {
                    if (part == null || part.trim().isEmpty()) continue;
                    if (joined.length() > 0) joined.append(' ');
                    joined.append(part.trim());
                }
                if (joined.length() > 0
                        && addQueryStep(steps, seen, QueryStage.MEMBER_ALIAS, cleanTitle + " " + joined)) {
                    aliasQueriesAdded++;
                }
            }

            // 3b: Lead member aliases alone (up to cap of 2 total alias queries).
            String leadCanonical = MatchText.normalizeArtistToken(canonicalParts[0]);
            for (String alias : memberAliasLists.get(0)) {
                if (aliasQueriesAdded >= MatchRules.MAX_ALIAS_SEARCH_QUERIES) break;
                String trimmed = cleanAliasToken(alias);
                if (trimmed.isEmpty() || !isSingleArtistCredit(trimmed)) continue;
                String token = MatchText.normalizeArtistToken(trimmed);
                if (token.isEmpty() || token.equals(leadCanonical)) continue;
                if (addQueryStep(steps, seen, QueryStage.MEMBER_ALIAS, cleanTitle + " " + trimmed)) {
                    aliasQueriesAdded++;
                }
            }
        }

        // Stage 4: bare title
        addQueryStep(steps, seen, QueryStage.BARE_TITLE, cleanTitle);
        return Collections.unmodifiableList(steps);
    }

    private static List<Candidate> deduplicate(List<Candidate> hits, MatchTrace trace,
                                               Track target, MatchText.TitleInfo targetTitle, String round) {
        LinkedHashMap<String, Candidate> byId = new LinkedHashMap<>();
        for (Candidate c : hits) {
            if (c == null || c.id.isEmpty()) {
                if (trace != null && c != null) {
                    MatchText.TitleInfo ct = MatchText.analyzeTitle(c.title);
                    trace.addCandidate(new MatchTrace.CandidateRecord(
                            "", c.mediaMid, c.listIndex, c.grpIndex, c.parentMid, round,
                            c.title, c.artist, c.album, c.durationMs,
                            MatchTrace.durationGapOrNull(target.duration, c.durationMs),
                            targetTitle.mainTitle, ct.mainTitle, targetTitle.versionKey, ct.versionKey,
                            null, null, "", MatchTrace.Outcome.FILTERED, MatchTrace.Rule.NONE,
                            MatchTrace.Gate.INVALID_ID, null, -1, "", "empty_candidate_id"));
                }
                continue;
            }
            Candidate existing = byId.get(c.id);
            if (existing == null || metadataRichness(c) > metadataRichness(existing)) {
                byId.put(c.id, c);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static int metadataRichness(Candidate c) {
        int score = c.artistAliases.size();
        for (ArtistAliases.Member m : c.artistMembers) {
            score += 2 + m.aliases.size() + (m.providerId.isEmpty() ? 0 : 1);
        }
        return score;
    }

    private static String findLiveYearCompetition(Track target, MatchText.TitleInfo targetTitle,
                                                  Candidate cand, List<Candidate> deduped,
                                                  List<MatchText.TitleInfo> candTitles) {
        for (int i = 0; i < deduped.size(); i++) {
            Candidate other = deduped.get(i);
            if (other.id.equals(cand.id)) continue;
            MatchText.TitleInfo otherTitle = candTitles.get(i);
            if (!otherTitle.hasLiveTag) continue;

            // 1. Another live recording of the same song (matching version tags) on the same live album.
            boolean otherSameAlbum = MatchText.sameNonCompilationAlbum(target.album, other.album);
            if (otherSameAlbum
                    && otherTitle.versionTags.equals(targetTitle.versionTags)
                    && (otherTitle.mainTitle.equals(targetTitle.mainTitle)
                    || (!targetTitle.mainTitle.isEmpty() && otherTitle.mainTitle.startsWith(targetTitle.mainTitle)))) {
                return other.id;
            }

            // 2. An explicit same-year live match passing hard gates already exists in the batch.
            if (!targetTitle.liveYear.isEmpty()
                    && targetTitle.liveYear.equals(otherTitle.liveYear)
                    && otherTitle.mainTitle.equals(targetTitle.mainTitle)
                    && otherTitle.versionTags.equals(targetTitle.versionTags)) {
                Long otherGap = MatchTrace.durationGapOrNull(target.duration, other.durationMs);
                if (otherGap == null || otherGap <= MatchRules.PLAYBACK_MAX_DURATION_GAP_MS) {
                    return other.id;
                }
            }
        }
        return "";
    }

    private static void evaluateRuleA(ResolvedCredit target, ResolvedCredit cand, Eval eval, Policy policy) {
        int m = target.size();
        int n = cand.size();
        boolean[][] validPair = new boolean[m][n];
        boolean anyValidPair = false;
        boolean anySameScriptConflict = false;
        StringBuilder matchedSummary = new StringBuilder();

        for (int i = 0; i < m; i++) {
            Set<String> tVis = target.visibleParts.get(i);
            Set<String> tExp = target.expandedParts.get(i);
            for (int j = 0; j < n; j++) {
                Set<String> cVis = cand.visibleParts.get(j);
                Set<String> cExp = cand.expandedParts.get(j);
                if (Collections.disjoint(tExp, cExp)) continue;
                if (hasSameScriptConflict(cVis, tExp) || hasSameScriptConflict(tVis, cExp)) {
                    anySameScriptConflict = true;
                    continue;
                }
                validPair[i][j] = true;
                anyValidPair = true;
                if (matchedSummary.length() > 0) matchedSummary.append(',');
                boolean directVisible = !Collections.disjoint(tVis, cVis);
                matchedSummary.append('t').append(i).append("=c").append(j)
                        .append(directVisible ? "" : "(alias)");
            }
        }

        eval.matchedMembers = matchedSummary.toString();
        if (!anyValidPair) {
            eval.ruleASameScriptVeto = anySameScriptConflict;
            if (anySameScriptConflict) {
                eval.detail = appendDetail(eval.detail, "same_script_conflict");
            }
            return;
        }

        boolean fullCoverage = (m == n) && hasUniqueBijection(validPair, m, n);
        boolean leadMatched = validPair[0][0];
        boolean oneSideSubset = fullCoverage || isSubsetCovered(validPair, m, n);

        // A4: Guest-only overlap when leads differ (except full 1-to-1 permutation) is rejected bidirectionally.
        if (!fullCoverage && !leadMatched) {
            eval.ruleAGuestOnly = true;
            return;
        }

        eval.ruleAFullCoverage = fullCoverage;
        eval.ruleAOneSideSubset = oneSideSubset;
        eval.ruleALeadMatched = leadMatched;

        if (policy == Policy.PLAYBACK) {
            eval.ruleAAccepted = true;
            eval.ruleAEligibleForBatchBlock = true;
            return;
        }

        // Policy.WRITE: full member coverage may cross albums; partial coverage requires lead evidence,
        // one-side subset coverage (no contradicting collaborators), and same non-compilation album (A7).
        if (fullCoverage) {
            eval.ruleAAccepted = true;
            eval.ruleAEligibleForBatchBlock = true;
            return;
        }

        eval.ruleAEligibleForBatchBlock = true;
        if (!oneSideSubset) {
            eval.failedGate = MatchTrace.Gate.WRITE_POLICY;
            eval.detail = appendDetail(eval.detail, "write_contradicting_collaborator");
            return;
        }
        boolean leadVisibleMatch = !Collections.disjoint(target.visibleParts.get(0), cand.visibleParts.get(0));
        boolean leadOfficialAlias = target.hasOfficialAliases[0] || cand.hasOfficialAliases[0];
        if (!leadVisibleMatch && !leadOfficialAlias) {
            eval.failedGate = MatchTrace.Gate.WRITE_POLICY;
            eval.detail = appendDetail(eval.detail, "write_missing_lead_evidence");
            return;
        }
        if (eval.compilationAlbum || !eval.sameAlbum) {
            eval.failedGate = MatchTrace.Gate.WRITE_POLICY;
            eval.detail = appendDetail(eval.detail,
                    eval.compilationAlbum ? "write_partial_compilation" : "write_partial_requires_same_album");
            return;
        }
        eval.ruleAAccepted = true;
    }

    private static boolean hasUniqueBijection(boolean[][] matrix, int m, int n) {
        if (m == 0 || m != n) return false;
        boolean[] used = new boolean[n];
        for (int i = 0; i < m; i++) {
            int match = -1;
            for (int j = 0; j < n; j++) {
                if (!used[j] && matrix[i][j]) {
                    if (match >= 0) return false; // ambiguous alias attribution
                    match = j;
                }
            }
            if (match < 0) return false;
            used[match] = true;
        }
        return true;
    }

    /** True when every member on the smaller credit uniquely matches a distinct member on the larger credit. */
    private static boolean isSubsetCovered(boolean[][] matrix, int m, int n) {
        if (m <= n) {
            boolean[] usedRight = new boolean[n];
            for (int i = 0; i < m; i++) {
                int match = -1;
                for (int j = 0; j < n; j++) {
                    if (!usedRight[j] && matrix[i][j]) {
                        if (match >= 0) return false;
                        match = j;
                    }
                }
                if (match < 0) return false;
                usedRight[match] = true;
            }
            return true;
        }
        boolean[] usedLeft = new boolean[m];
        for (int j = 0; j < n; j++) {
            int match = -1;
            for (int i = 0; i < m; i++) {
                if (!usedLeft[i] && matrix[i][j]) {
                    if (match >= 0) return false;
                    match = i;
                }
            }
            if (match < 0) return false;
            usedLeft[match] = true;
        }
        return true;
    }

    private static Comparator<RankedHit> comparatorFor(Policy policy) {
        return (a, b) -> {
            // 1. Rule A before Rule B.
            int byRule = Integer.compare(ruleRank(a.rule), ruleRank(b.rule));
            if (byRule != 0) return byRule;
            // 2. Under WRITE, full member coverage outranks partial lead-only coverage.
            if (policy == Policy.WRITE) {
                int byFull = Boolean.compare(b.fullMemberCoverage, a.fullMemberCoverage);
                if (byFull != 0) return byFull;
            }
            // 3. Same non-compilation album first.
            int byAlbum = Boolean.compare(b.sameAlbum, a.sameAlbum);
            if (byAlbum != 0) return byAlbum;
            // 4. Ascending duration gap.
            int byGap = Long.compare(a.durationGapMs, b.durationGapMs);
            if (byGap != 0) return byGap;
            // 5. Playback tie-breakers: full coverage -> subset -> lead match.
            int byFull = Boolean.compare(b.fullMemberCoverage, a.fullMemberCoverage);
            if (byFull != 0) return byFull;
            int bySubset = Boolean.compare(b.oneSideSubset, a.oneSideSubset);
            if (bySubset != 0) return bySubset;
            return Boolean.compare(b.leadMatched, a.leadMatched);
        };
    }

    private static int ruleRank(Rule rule) {
        if (rule == Rule.A) return 0;
        if (rule == Rule.B) return 1;
        return 2;
    }

    private static boolean sameWriteTier(RankedHit a, RankedHit b) {
        return a.rule == b.rule
                && a.fullMemberCoverage == b.fullMemberCoverage
                && a.sameAlbum == b.sameAlbum;
    }

    /**
     * Detects structural artist-grouping conflicts across credits — e.g., single structured band
     * {@code "A & B"} vs two-artist credit {@code "A / B"}, {@code "AC/DC"} vs {@code "AC / DC"},
     * or {@code "AB / C"} vs {@code "A / BC"} — vetoing both Rule A and Rule B.
     */
    private static boolean hasGroupingConflict(ResolvedCredit left, ResolvedCredit right) {
        if (left.size() != right.size() || !simpleMemberBijection(left, right)) {
            String leftConcat = concatBaseNames(left);
            String rightConcat = concatBaseNames(right);
            if (!leftConcat.isEmpty() && leftConcat.equals(rightConcat)
                    && (left.size() > 1 || right.size() > 1)) {
                return true;
            }
        }
        return splitsIntoMultipleMembers(left, right) || splitsIntoMultipleMembers(right, left);
    }

    private static boolean simpleMemberBijection(ResolvedCredit left, ResolvedCredit right) {
        int m = left.size();
        if (m != right.size()) return false;
        boolean[][] pair = new boolean[m][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                pair[i][j] = !Collections.disjoint(left.expandedParts.get(i), right.expandedParts.get(j));
            }
        }
        return hasUniqueBijection(pair, m, m);
    }

    private static String concatBaseNames(ResolvedCredit credit) {
        StringBuilder sb = new StringBuilder();
        for (String raw : credit.rawMemberNames) {
            String base = stripBrackets(raw);
            String norm = MatchText.normalizeArtistToken(base.isEmpty() ? raw : base);
            if (norm.isEmpty()) return "";
            sb.append(norm);
        }
        return sb.toString();
    }

    private static boolean splitsIntoMultipleMembers(ResolvedCredit source, ResolvedCredit other) {
        if (other.size() < 2) return false;
        for (String raw : source.rawMemberNames) {
            String base = stripBrackets(raw);
            List<Set<String>> subParts = MatchText.artistParts(base.isEmpty() ? raw : base);
            if (subParts.size() < 2) continue;
            boolean[] matchedOther = new boolean[other.size()];
            int distinctMatched = 0;
            for (Set<String> sub : subParts) {
                for (int j = 0; j < other.size(); j++) {
                    if (!matchedOther[j] && !Collections.disjoint(sub, other.visibleParts.get(j))) {
                        matchedOther[j] = true;
                        distinctMatched++;
                        break;
                    }
                }
            }
            if (distinctMatched >= 2 && distinctMatched == subParts.size()) {
                return true;
            }
        }
        return false;
    }

    private static ResolvedCredit resolveCredit(String artist, List<ArtistAliases.Member> members,
                                                List<String> flatAliases) {
        List<Set<String>> visibleParts = MatchText.artistParts(artist);
        if (visibleParts.isEmpty()) return null;

        if (members != null && !members.isEmpty() && isCompleteConsistentMembers(artist, members)) {
            List<String> rawNames = new ArrayList<>(members.size());
            List<Set<String>> atomVisible = new ArrayList<>(members.size());
            List<Set<String>> atomExpanded = new ArrayList<>(members.size());
            boolean[] hasOfficial = new boolean[members.size()];
            List<String> identity = new ArrayList<>(members.size());
            for (int i = 0; i < members.size(); i++) {
                ArtistAliases.Member member = members.get(i);
                rawNames.add(member.name);
                Set<String> visible = MatchText.memberAtomNames(member.name);
                Set<String> expanded = new LinkedHashSet<>(visible);
                for (String alias : member.aliases) {
                    for (String token : MatchText.memberAtomNames(alias)) {
                        if (expanded.add(token)) hasOfficial[i] = true;
                    }
                }
                atomVisible.add(visible);
                atomExpanded.add(expanded);
                identity.add(member.providerId.isEmpty()
                        ? "name:" + MatchText.foldCompact(member.name)
                        : "id:" + member.providerId);
            }
            if (members.size() == 1 && flatAliases != null) {
                Set<String> targetSet = atomExpanded.get(0);
                for (String alias : flatAliases) {
                    String cleanAlias = cleanAliasToken(alias);
                    if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
                    for (String token : MatchText.memberAtomNames(cleanAlias)) {
                        if (targetSet.add(token)) hasOfficial[0] = true;
                    }
                }
            }
            Collections.sort(identity);
            return new ResolvedCredit(rawNames, atomVisible, atomExpanded, hasOfficial,
                    Collections.unmodifiableList(identity));
        }

        List<String> rawParts = MatchText.artistRawParts(artist);
        List<Set<String>> expanded = new ArrayList<>(visibleParts.size());
        for (Set<String> part : visibleParts) expanded.add(new LinkedHashSet<>(part));
        boolean[] hasOfficial = new boolean[visibleParts.size()];
        String[] mappedProviderIds = new String[visibleParts.size()];

        if (members != null && !members.isEmpty()) {
            int[] mapped = mapConsistentMembers(visibleParts, members);
            if (mapped != null) {
                for (int i = 0; i < members.size(); i++) {
                    int visibleIndex = mapped[i];
                    if (visibleIndex < 0) continue;
                    ArtistAliases.Member member = members.get(i);
                    if (!member.providerId.isEmpty()) mappedProviderIds[visibleIndex] = member.providerId;
                    Set<String> targetSet = expanded.get(visibleIndex);
                    for (String alias : member.aliases) {
                        for (String token : MatchText.memberAtomNames(alias)) {
                            if (targetSet.add(token)) hasOfficial[visibleIndex] = true;
                        }
                    }
                }
            }
        }

        if (visibleParts.size() == 1 && isSingleArtistCredit(artist) && flatAliases != null) {
            Set<String> targetSet = expanded.get(0);
            for (String alias : flatAliases) {
                String cleanAlias = cleanAliasToken(alias);
                if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
                for (String token : MatchText.memberAtomNames(cleanAlias)) {
                    if (targetSet.add(token)) hasOfficial[0] = true;
                }
            }
        }

        List<String> rawNames = new ArrayList<>(visibleParts.size());
        List<String> identity = new ArrayList<>(visibleParts.size());
        for (int i = 0; i < visibleParts.size(); i++) {
            String raw = i < rawParts.size() ? rawParts.get(i) : "";
            rawNames.add(raw);
            String pid = mappedProviderIds[i];
            identity.add(pid != null && !pid.isEmpty()
                    ? "id:" + pid
                    : "name:" + MatchText.foldCompact(raw));
        }
        Collections.sort(identity);
        return new ResolvedCredit(rawNames, visibleParts, expanded, hasOfficial,
                Collections.unmodifiableList(identity));
    }

    private static boolean isCompleteConsistentMembers(String artist, List<ArtistAliases.Member> members) {
        List<String> memberNames = new ArrayList<>(members.size());
        for (ArtistAliases.Member member : members) {
            if (member == null || member.name.isEmpty() || containsExplicitJoinSeparator(member.name)) {
                return false;
            }
            if (MatchText.memberAtomNames(member.name).isEmpty()) return false;
            memberNames.add(member.name);
        }
        return MatchText.matchesDisplayCredit(artist, memberNames);
    }

    private static int[] mapConsistentMembers(List<Set<String>> visibleParts, List<ArtistAliases.Member> members) {
        int[] mapped = new int[members.size()];
        boolean[] used = new boolean[visibleParts.size()];
        for (int i = 0; i < members.size(); i++) {
            ArtistAliases.Member member = members.get(i);
            if (member == null || member.name.isEmpty() || containsExplicitJoinSeparator(member.name)) return null;
            List<Set<String>> memberParts = MatchText.artistParts(member.name);
            if (memberParts.size() != 1) return null;
            Set<String> canonical = memberParts.get(0);
            int found = -1;
            for (int j = 0; j < visibleParts.size(); j++) {
                if (!used[j] && visibleParts.get(j).equals(canonical)) {
                    found = j;
                    break;
                }
            }
            if (found < 0) return null;
            used[found] = true;
            mapped[i] = found;
        }
        return mapped;
    }

    private static boolean containsExplicitJoinSeparator(String value) {
        return value != null && MatchRules.EXPLICIT_JOIN_SEPARATOR.matcher(value).find();
    }

    private static boolean hasSameScriptConflict(Set<String> visibleNames, Set<String> expectedFromOther) {
        for (String known : visibleNames) {
            if (!expectedFromOther.contains(known)) continue;
            int knownScripts = scripts(known);
            if (knownScripts == 0) continue;
            for (String other : visibleNames) {
                if (!expectedFromOther.contains(other) && (knownScripts & scripts(other)) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int scripts(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) == '\u0000') return 0;
        int mask = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(c);
            if (script == Character.UnicodeScript.LATIN) mask |= 1;
            else if (script == Character.UnicodeScript.HAN) mask |= 2;
            else if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA) mask |= 4;
            else if (script == Character.UnicodeScript.HANGUL) mask |= 8;
        }
        // Kana distinguishes Japanese from Chinese even where both contain Han.
        return (mask & 4) != 0 ? (mask & ~2) : mask;
    }

    private static boolean isSingleArtistCredit(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '/' || c == '\\' || c == ',' || c == '\u3001' || c == '\uFF0C'
                    || c == '&' || c == '\uFF06' || c == ';' || c == '\n' || c == '\r') return false;
        }
        return !MatchRules.COLLABORATION.matcher(clean.toLowerCase(Locale.ROOT)).find();
    }

    private static String stripBrackets(String value) {
        if (value == null || value.isEmpty()) return "";
        return MatchRules.BRACKET.matcher(value).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static List<String> extractBracketAliases(String value) {
        if (value == null || value.isEmpty()) return Collections.emptyList();
        List<String> aliases = new ArrayList<>();
        Matcher m = MatchRules.BRACKET.matcher(value);
        while (m.find()) {
            String inside = cleanAliasToken(m.group(1));
            if (!inside.isEmpty()) aliases.add(inside);
        }
        return aliases;
    }

    private static String firstRawPart(String artist) {
        List<String> parts = MatchText.artistRawParts(artist);
        return parts.isEmpty() ? "" : parts.get(0);
    }

    private static String firstDistinctAlias(String canonicalName, List<String> aliases) {
        String canonicalToken = MatchText.normalizeArtistToken(canonicalName);
        for (String alias : aliases) {
            String trimmed = cleanAliasToken(alias);
            if (trimmed.isEmpty() || !isSingleArtistCredit(trimmed)) continue;
            String token = MatchText.normalizeArtistToken(trimmed);
            if (!token.isEmpty() && !token.equals(canonicalToken)) return trimmed;
        }
        return "";
    }

    private static String cleanAliasToken(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) || trimmed.length() > 128) return "";
        return trimmed;
    }

    private static boolean addQueryStep(List<QueryStep> steps, Set<String> seen, QueryStage stage, String candidate) {
        if (candidate == null) return false;
        String compact = candidate.trim().replaceAll("\\s+", " ");
        if (compact.isEmpty()) return false;
        String key = compact.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) return false;
        steps.add(new QueryStep(stage, compact));
        return true;
    }

    private static String appendDetail(String base, String extra) {
        if (extra == null || extra.isEmpty()) return base == null ? "" : base;
        return base == null || base.isEmpty() ? extra : base + ";" + extra;
    }

    private static void emitTrace(MatchTrace trace, Track target, MatchText.TitleInfo targetTitle,
                                  String round, List<Eval> evals, List<RankedHit> ranked,
                                  boolean ambiguous, String ambiguousConflictId) {
        Map<String, Integer> rankPos = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            rankPos.put(ranked.get(i).candidate.id, i);
        }
        for (Eval eval : evals) {
            Candidate c = eval.candidate;
            boolean accepted = (eval.ruleAAccepted || eval.ruleBAccepted) && !ambiguous;
            MatchTrace.Rule traceRule = eval.ruleAAccepted ? MatchTrace.Rule.A
                    : eval.ruleBAccepted ? MatchTrace.Rule.B : MatchTrace.Rule.NONE;
            MatchTrace.Outcome outcome = accepted ? MatchTrace.Outcome.ACCEPTED : MatchTrace.Outcome.REJECTED;
            MatchTrace.Gate gate = eval.failedGate;
            String conflict = eval.conflictCandidateId;
            String detail = eval.detail;
            int pos = accepted ? rankPos.getOrDefault(c.id, -1) : -1;
            if ((eval.ruleAAccepted || eval.ruleBAccepted) && ambiguous) {
                gate = MatchTrace.Gate.AMBIGUOUS;
                conflict = c.id.equals(ambiguousConflictId) ? ranked.get(0).candidate.id : ambiguousConflictId;
                detail = appendDetail(detail, "write_tie_distinct_identity");
            }
            trace.addCandidate(new MatchTrace.CandidateRecord(
                    c.id, c.mediaMid, c.listIndex, c.grpIndex, c.parentMid, round,
                    c.title, c.artist, c.album, c.durationMs, eval.durationGap,
                    targetTitle.mainTitle, eval.titleInfo.mainTitle,
                    targetTitle.versionKey, eval.titleInfo.versionKey,
                    eval.sameAlbum, eval.compilationAlbum, eval.matchedMembers,
                    outcome, traceRule, gate, null, pos, conflict, detail));
        }
    }
}
