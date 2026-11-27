package dev.yesplaymusic.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
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
 *   <li>Normalized main title equality (allowing benign annotations, CJK subtitle translations excluding
 *       years/tour stops/part markers, and single-sided live year markers only on the same non-compilation
 *       live album when no second distinct live catalog ID of the same song competes on that album).</li>
 *   <li>Version tag set equality (Live, Instrumental, Remix, language variants, etc. are never interchangeable).</li>
 *   <li>Duration gap &lt;= 5s when both sides report duration; missing duration requires Rule A.</li>
 * </ol>
 *
 * <p>Artist rules:
 * <ul>
 *   <li>Rule A: valid member name intersection (canonical + official aliases + bracket aliases) without
 *       same-script parenthetical conflict (evaluated across all Unicode scripts), requiring either a
 *       globally unique 1-to-1 bijection or lead-to-lead overlap. Guest-only overlap when leads differ is
 *       rejected bidirectionally before any Rule B fallback (A4). Under {@link Policy#WRITE}, partial coverage
 *       further requires one-side unique subset coverage, verified lead↔lead official alias or provider-ID
 *       canonical evidence on the actual intersecting token, and same non-compilation album (A7).</li>
 *   <li>Rule B: when artists are disjoint (or Rule A was vetoed solely by same-script parenthetical conflict, A5),
 *       accept only on the same non-compilation album, when globally unique among all distinct-ID candidates
 *       passing hard gates (1)(2)(3) across the completed query ladder (A2), with no Rule-A candidate in the
 *       batch, both durations present (&lt;= 5s for playback, &lt;= 2s for write), and no order-independent
 *       structural artist-grouping conflict (A6).</li>
 *   <li>{@link Policy#WRITE} tie semantics: candidates tying on the top {@code (rule, fullMemberCoverage, sameAlbum)}
 *       tier with distinct artist identities are rejected as ambiguous regardless of small duration-gap differences;
 *       identical artist identities (e.g. reissues with the same provider artist IDs) break ties by duration gap.</li>
 * </ul>
 */
final class TrackMatcher {
    private static final int MAX_QUERY_TITLE_LENGTH = 512;
    private static final long LIVE_COMPETITION_MAX_GAP_MS = 30_000L;

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

        Candidate withArtistMembers(List<ArtistAliases.Member> members) {
            return new Candidate(id, mediaMid, title, artist, members, artistAliases, album, durationMs,
                    listIndex, grpIndex, parentMid, track);
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

        /**
         * Merges compatible metadata from a later observation of the same catalog ID without erasing
         * previously observed hard-gate fields (known duration, title, artist, album, mediaMid).
         */
        Candidate mergeWith(Candidate incoming) {
            if (incoming == null || !this.id.equals(incoming.id)) return this;
            String mergedMedia = !this.mediaMid.isEmpty() ? this.mediaMid : incoming.mediaMid;
            String mergedTitle = !this.title.isEmpty() ? this.title : incoming.title;
            String mergedArtist = !this.artist.isEmpty() ? this.artist : incoming.artist;
            String mergedAlbum = !this.album.isEmpty() ? this.album : incoming.album;
            long mergedDuration = this.durationMs > 0L ? this.durationMs : incoming.durationMs;

            List<ArtistAliases.Member> mergedMembers = mergeMembers(
                    this.artist, this.artistMembers, incoming.artist, incoming.artistMembers);
            List<String> mergedAliases;
            if (this.artist.isEmpty() && this.artistMembers.isEmpty() && this.artistAliases.isEmpty()) {
                mergedAliases = incoming.artistAliases;
            } else if (canMergeFlatAliases(this, incoming, mergedMembers)) {
                LinkedHashSet<String> aliasSet = new LinkedHashSet<>(this.artistAliases);
                aliasSet.addAll(incoming.artistAliases);
                mergedAliases = new ArrayList<>(aliasSet);
            } else {
                mergedAliases = mergedMembers.size() > 1 ? Collections.emptyList() : this.artistAliases;
            }

            Track mergedTrack = this.track != null ? this.track : incoming.track;
            if (mergedMedia.equals(this.mediaMid) && mergedTitle.equals(this.title)
                    && mergedArtist.equals(this.artist) && mergedAlbum.equals(this.album)
                    && mergedDuration == this.durationMs && mergedMembers.equals(this.artistMembers)
                    && mergedAliases.equals(this.artistAliases) && mergedTrack == this.track) {
                return this;
            }
            return new Candidate(this.id, mergedMedia, mergedTitle, mergedArtist,
                    mergedMembers, mergedAliases, mergedAlbum, mergedDuration,
                    this.listIndex, this.grpIndex, this.parentMid, mergedTrack);
        }

        private static boolean canMergeFlatAliases(Candidate base, Candidate incoming,
                                                   List<ArtistAliases.Member> mergedMembers) {
            if (incoming.artistAliases.isEmpty() || mergedMembers.size() > 1) return false;
            if (base.artistMembers.size() > 1 || incoming.artistMembers.size() > 1) return false;
            if (!isSingleArtistCredit(base.artist) || !isSingleArtistCredit(incoming.artist)) return false;
            if (MatchText.artistParts(base.artist).size() != 1
                    || MatchText.artistParts(incoming.artist).size() != 1) {
                return false;
            }
            String baseName = base.artistMembers.size() == 1 ? base.artistMembers.get(0).name : base.artist;
            String incName = incoming.artistMembers.size() == 1 ? incoming.artistMembers.get(0).name : incoming.artist;
            String normBase = MatchText.normalizeArtistToken(baseName);
            String normInc = MatchText.normalizeArtistToken(incName);
            if (normBase.isEmpty() || !normBase.equals(normInc)) return false;
            if (base.artistMembers.size() == 1 && incoming.artistMembers.size() == 1) {
                String aId = base.artistMembers.get(0).providerId;
                String bId = incoming.artistMembers.get(0).providerId;
                if (!aId.isEmpty() && !bId.isEmpty() && !aId.equals(bId)) return false;
            }
            return true;
        }

        private static List<ArtistAliases.Member> mergeMembers(
                String baseArtist, List<ArtistAliases.Member> base,
                String incomingArtist, List<ArtistAliases.Member> incoming) {
            if (incoming == null || incoming.isEmpty()) return base;
            if (base == null || base.isEmpty()) {
                return (baseArtist == null || baseArtist.isEmpty()
                        || isCompleteConsistentMembers(baseArtist, incoming))
                        ? incoming : base;
            }
            if (base.size() != incoming.size()) return base;
            List<ArtistAliases.Member> merged = new ArrayList<>(base.size());
            boolean changed = false;
            for (int i = 0; i < base.size(); i++) {
                ArtistAliases.Member a = base.get(i);
                ArtistAliases.Member b = incoming.get(i);
                String normA = MatchText.normalizeArtistToken(a.name);
                String normB = MatchText.normalizeArtistToken(b.name);
                boolean compatibleName = !normA.isEmpty() && normA.equals(normB);
                boolean compatibleId = a.providerId.isEmpty() || b.providerId.isEmpty()
                        || a.providerId.equals(b.providerId);
                if (compatibleName && compatibleId) {
                    String pid = !a.providerId.isEmpty() ? a.providerId : b.providerId;
                    ArtistAliases.Member updated = pid.equals(a.providerId)
                            ? a.withAddedAliases(b.aliases)
                            : new ArtistAliases.Member(a.name, pid, a.aliases).withAddedAliases(b.aliases);
                    if (updated != a && (!updated.providerId.equals(a.providerId) || !updated.aliases.equals(a.aliases))) {
                        changed = true;
                        merged.add(updated);
                    } else {
                        merged.add(a);
                    }
                } else {
                    merged.add(a);
                }
            }
            return changed ? Collections.unmodifiableList(merged) : base;
        }
    }

    static final class RankedHit {
        final Candidate candidate;
        final Rule rule;
        final boolean sameAlbum;
        final boolean fullMemberCoverage;
        final boolean oneSideSubset;
        final boolean leadMatched;
        final boolean usedLiveYearFallback;
        final long durationGapMs;
        final String matchedMembersSummary;
        final List<String> artistIdentity;

        RankedHit(Candidate candidate, Rule rule, boolean sameAlbum, boolean fullMemberCoverage,
                  boolean oneSideSubset, boolean leadMatched, boolean usedLiveYearFallback,
                  long durationGapMs, String matchedMembersSummary, List<String> artistIdentity) {
            this.candidate = candidate;
            this.rule = rule;
            this.sameAlbum = sameAlbum;
            this.fullMemberCoverage = fullMemberCoverage;
            this.oneSideSubset = oneSideSubset;
            this.leadMatched = leadMatched;
            this.usedLiveYearFallback = usedLiveYearFallback;
            this.durationGapMs = durationGapMs;
            this.matchedMembersSummary = matchedMembersSummary == null ? "" : matchedMembersSummary;
            this.artistIdentity = artistIdentity;
        }
    }

    static final class RankResult {
        final List<RankedHit> hits;
        final List<RankedHit> ambiguousTopTier;
        final boolean ambiguous;
        final String conflictCandidateId;

        RankResult(List<RankedHit> hits, boolean ambiguous, String conflictCandidateId) {
            this(hits, Collections.emptyList(), ambiguous, conflictCandidateId);
        }

        RankResult(List<RankedHit> hits, List<RankedHit> ambiguousTopTier,
                   boolean ambiguous, String conflictCandidateId) {
            this.hits = Collections.unmodifiableList(hits);
            this.ambiguousTopTier = Collections.unmodifiableList(ambiguousTopTier);
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
        final List<Set<String>> baseParts;
        final List<Set<String>> visibleParts;
        final List<Set<String>> officialAliasParts;
        final List<Set<String>> expandedParts;
        final boolean[] hasProviderId;
        final boolean[] isPlaceholder;
        final List<String> identityKey;

        ResolvedCredit(List<String> rawMemberNames, List<Set<String>> baseParts,
                       List<Set<String>> visibleParts, List<Set<String>> officialAliasParts,
                       List<Set<String>> expandedParts, boolean[] hasProviderId,
                       boolean[] isPlaceholder, List<String> identityKey) {
            this.rawMemberNames = rawMemberNames;
            this.baseParts = baseParts;
            this.visibleParts = visibleParts;
            this.officialAliasParts = officialAliasParts;
            this.expandedParts = expandedParts;
            this.hasProviderId = hasProviderId;
            this.isPlaceholder = isPlaceholder;
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

        boolean passedHardGates123;
        boolean usedLiveYearFallback;
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
             boolean sameAlbum, boolean compilationAlbum) {
            this.candidate = candidate;
            this.titleInfo = titleInfo;
            this.durationGap = durationGap;
            this.sameAlbum = sameAlbum;
            this.compilationAlbum = compilationAlbum;
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

        List<Candidate> deduped = deduplicate(hits, trace, target, targetTitle, round);
        if (deduped.isEmpty()) {
            return new RankResult(Collections.emptyList(), false, "");
        }

        List<MatchText.TitleInfo> candTitles = new ArrayList<>(deduped.size());
        List<ResolvedCredit> candCredits = new ArrayList<>(deduped.size());
        for (Candidate c : deduped) {
            candTitles.add(MatchText.analyzeTitle(c.title));
            candCredits.add(MatchText.knownArtist(c.artist)
                    ? resolveCredit(c.artist, c.artistMembers, c.artistAliases) : null);
        }

        List<Eval> evals = new ArrayList<>(deduped.size());
        int hardGatePassers = 0;
        String firstHardGateId = "";
        String secondHardGateId = "";

        // Pass 1: Hard gates (1: main title + A3 live year, 2: version tags, 3: duration <= 5s).
        // Count every distinct-ID candidate passing (1)(2)(3) — including unknown-artist hits — so an
        // unattributed same-title recording prevents another disjoint artist from falsely claiming Rule B uniqueness.
        for (int i = 0; i < deduped.size(); i++) {
            Candidate cand = deduped.get(i);
            MatchText.TitleInfo candTitle = candTitles.get(i);
            ResolvedCredit candCredit = candCredits.get(i);
            Long gap = MatchTrace.durationGapOrNull(target.duration, cand.durationMs);
            boolean compAlbum = MatchText.isCompilationAlbum(target.album)
                    || MatchText.isCompilationAlbum(cand.album);
            boolean sameAlbum = MatchText.sameAlbumTitle(target.album, cand.album);
            boolean sameNonCompAlbum = sameAlbum && !compAlbum;

            Eval eval = new Eval(cand, candTitle, gap, sameAlbum, compAlbum);
            eval.credit = candCredit;
            evals.add(eval);

            if (!targetKnownArtist || targetCredit == null) {
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

            boolean liveYearFallback = false;
            if (!targetTitle.liveYear.equals(candTitle.liveYear)) {
                if (!targetTitle.liveYear.isEmpty() && !candTitle.liveYear.isEmpty()) {
                    eval.failedGate = MatchTrace.Gate.VERSION;
                    eval.detail = "live_year_mismatch";
                    continue;
                }
                if (!(targetTitle.hasLiveTag && candTitle.hasLiveTag)
                        || !sameNonCompAlbum
                        || !(MatchText.isLiveAlbum(target.album) || MatchText.isLiveAlbum(cand.album))) {
                    eval.failedGate = MatchTrace.Gate.TITLE;
                    eval.detail = "live_year_requires_same_live_album";
                    continue;
                }
                String liveConflictId = findLiveYearCompetition(
                        target, targetTitle, targetCredit, cand, candCredit, deduped, candTitles, candCredits);
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

            eval.passedHardGates123 = true;
            eval.usedLiveYearFallback = liveYearFallback;
            if (liveYearFallback) eval.detail = "live_year_same_album";
            hardGatePassers++;
            if (firstHardGateId.isEmpty()) firstHardGateId = cand.id;
            else if (secondHardGateId.isEmpty()) secondHardGateId = cand.id;
        }

        // Pass 2: Validate candidate artist credit, structural grouping conflicts, and Rule A.
        boolean anyRuleAInBatch = false;
        String firstRuleAId = "";
        for (Eval eval : evals) {
            if (!eval.passedHardGates123) continue;
            if (eval.credit == null) {
                eval.failedGate = MatchTrace.Gate.UNKNOWN_ARTIST;
                continue;
            }
            if (hasGroupingConflict(targetCredit, eval.credit)) {
                eval.groupingConflict = true;
                eval.failedGate = MatchTrace.Gate.VETO;
                eval.detail = appendDetail(eval.detail, "grouping_conflict");
                continue;
            }
            evaluateRuleA(targetCredit, eval.credit, eval, activePolicy);
            if (eval.ruleAAccepted || eval.ruleAEligibleForBatchBlock) {
                if (!anyRuleAInBatch) {
                    anyRuleAInBatch = true;
                    firstRuleAId = eval.candidate.id;
                }
            }
        }

        // Pass 3: Evaluate Rule B for disjoint (or A5 same-script vetoed) hard-gate passers when allowRuleB is true.
        for (Eval eval : evals) {
            if (!eval.passedHardGates123 || eval.credit == null
                    || eval.failedGate == MatchTrace.Gate.UNKNOWN_ARTIST
                    || eval.groupingConflict || eval.ruleAAccepted) {
                continue;
            }
            if (eval.ruleAGuestOnly) {
                eval.failedGate = MatchTrace.Gate.ARTIST;
                eval.detail = appendDetail(eval.detail, "guest_only_overlap");
                continue;
            }
            if (eval.failedGate == MatchTrace.Gate.WRITE_POLICY) {
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
        for (Eval eval : evals) {
            if (eval.credit != null && (eval.ruleAAccepted || eval.ruleBAccepted)) {
                Rule rule = eval.ruleAAccepted ? Rule.A : Rule.B;
                long sortGap = eval.durationGap == null ? Long.MAX_VALUE : eval.durationGap;
                ranked.add(new RankedHit(
                        eval.candidate, rule, eval.sameAlbum, eval.ruleAFullCoverage,
                        eval.ruleAOneSideSubset, eval.ruleALeadMatched, eval.usedLiveYearFallback,
                        sortGap, eval.matchedMembers, eval.credit.identityKey));
            }
        }
        ranked.sort(comparatorFor(activePolicy));

        // Under Policy.WRITE, reject if top-tier candidates tie with distinct artist identities.
        boolean ambiguous = false;
        String ambiguousConflictId = "";
        List<RankedHit> topTier = Collections.emptyList();
        if (activePolicy == Policy.WRITE && ranked.size() > 1) {
            RankedHit top = ranked.get(0);
            List<RankedHit> tier = new ArrayList<>();
            tier.add(top);
            for (int i = 1; i < ranked.size(); i++) {
                RankedHit other = ranked.get(i);
                if (!sameWriteTier(top, other)) break;
                tier.add(other);
                if (!ambiguous && !top.artistIdentity.equals(other.artistIdentity)) {
                    ambiguous = true;
                    ambiguousConflictId = other.candidate.id;
                }
            }
            if (ambiguous) topTier = tier;
        }

        if (trace != null) {
            emitTrace(trace, targetTitle, round, evals, ranked, ambiguous, ambiguousConflictId);
        }

        if (ambiguous) {
            return new RankResult(Collections.emptyList(), topTier, true, ambiguousConflictId);
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
        if (rawTitle.isEmpty()) return Collections.emptyList();
        if (rawTitle.length() > MAX_QUERY_TITLE_LENGTH) {
            rawTitle = rawTitle.substring(0, MAX_QUERY_TITLE_LENGTH).trim();
        }
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

        // Stage 1: title + all artists (preserve structured member names like "AC/DC" intact).
        if (completeStructured) {
            StringBuilder allMembers = new StringBuilder();
            for (ArtistAliases.Member m : members) {
                String part = cleanStructuredMemberName(m.name);
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

        // Stage 2: title + lead artist (only for multi-artist credits).
        if (memberCount >= 2) {
            String leadRaw = completeStructured ? members.get(0).name : firstRawPart(rawArtist);
            String cleanLead = completeStructured
                    ? cleanStructuredMemberName(leadRaw)
                    : MatchText.cleanSearchArtist(leadRaw);
            if (!cleanLead.isEmpty()) {
                addQueryStep(steps, seen, QueryStage.LEAD_ARTIST, cleanTitle + " " + cleanLead);
            }
        }

        // Stage 3: title + member aliases (strictly capped at MatchRules.MAX_ALIAS_SEARCH_QUERIES = 2).
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

            String canonicalName = completeStructured ? cleanStructuredMemberName(members.get(0).name) : cleanArtist;
            String canonicalToken = MatchText.normalizeArtistToken(canonicalName);
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
                    canonicalParts[i] = cleanStructuredMemberName(members.get(i).name);
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

        // Stage 4: bare title.
        addQueryStep(steps, seen, QueryStage.BARE_TITLE, cleanTitle);
        return Collections.unmodifiableList(steps);
    }

    private static String cleanStructuredMemberName(String memberName) {
        if (memberName == null) return "";
        String base = stripBrackets(memberName);
        return base.isEmpty() ? memberName.trim() : base;
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
            byId.put(c.id, existing == null ? c : existing.mergeWith(c));
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Shared A3 same-album live-session competitor predicate (30s window, same base song & version tags on the
     * same non-compilation live album) used by both ranking ({@link #findLiveYearCompetition}) and enrichment.
     */
    private static boolean isSameAlbumLiveSessionCompetitor(Track target, MatchText.TitleInfo targetTitle,
                                                            Candidate other, MatchText.TitleInfo otherTitle) {
        if (!otherTitle.hasLiveTag) return false;
        boolean otherSameAlbum = MatchText.sameNonCompilationAlbum(target.album, other.album);
        boolean sameBaseSong = otherTitle.mainTitle.equals(targetTitle.mainTitle)
                || (!targetTitle.mainTitle.isEmpty()
                && otherTitle.baseTitleNoBrackets.equals(targetTitle.mainTitle));
        if (!otherSameAlbum || !sameBaseSong || !otherTitle.versionTags.equals(targetTitle.versionTags)) {
            return false;
        }
        Long gapToTarget = MatchTrace.durationGapOrNull(target.duration, other.durationMs);
        return gapToTarget == null || gapToTarget <= LIVE_COMPETITION_MAX_GAP_MS;
    }

    /**
     * Shared A3 explicit same-year live competitor predicate (5s window, exact same explicit live year & title)
     * used by both ranking ({@link #findLiveYearCompetition}) and enrichment.
     */
    private static boolean isExplicitSameYearLiveCompetitor(Track target, MatchText.TitleInfo targetTitle,
                                                            Candidate other, MatchText.TitleInfo otherTitle) {
        if (!otherTitle.hasLiveTag || targetTitle.liveYear.isEmpty()) return false;
        if (!targetTitle.liveYear.equals(otherTitle.liveYear)
                || !otherTitle.mainTitle.equals(targetTitle.mainTitle)
                || !otherTitle.versionTags.equals(targetTitle.versionTags)) {
            return false;
        }
        Long otherGap = MatchTrace.durationGapOrNull(target.duration, other.durationMs);
        return otherGap == null || otherGap <= MatchRules.PLAYBACK_MAX_DURATION_GAP_MS;
    }

    private static String findLiveYearCompetition(Track target, MatchText.TitleInfo targetTitle,
                                                  ResolvedCredit targetCredit, Candidate cand,
                                                  ResolvedCredit candCredit, List<Candidate> deduped,
                                                  List<MatchText.TitleInfo> candTitles,
                                                  List<ResolvedCredit> candCredits) {
        for (int i = 0; i < deduped.size(); i++) {
            Candidate other = deduped.get(i);
            if (other.id.equals(cand.id)) continue;
            MatchText.TitleInfo otherTitle = candTitles.get(i);
            if (!otherTitle.hasLiveTag) continue;
            ResolvedCredit otherCredit = candCredits.get(i);

            // 1. Another live recording of the SAME song (never prefix-matching unrelated titles) on the
            //    same live album with plausible duration (<=30s) and artist related to target or candidate.
            if (isSameAlbumLiveSessionCompetitor(target, targetTitle, other, otherTitle)) {
                boolean relatedArtist = creditsHaveLeadOrFullOverlap(targetCredit, otherCredit)
                        || (candCredit != null && creditsHaveLeadOrFullOverlap(candCredit, otherCredit));
                if (relatedArtist) {
                    return other.id;
                }
            }

            // 2. An explicit same-year live match by a matching artist passing hard gates (<=5s) already exists in batch.
            if (isExplicitSameYearLiveCompetitor(target, targetTitle, other, otherTitle)
                    && creditsHaveLeadOrFullOverlap(targetCredit, otherCredit)) {
                return other.id;
            }
        }
        return "";
    }

    private static boolean creditsHaveLeadOrFullOverlap(ResolvedCredit left, ResolvedCredit right) {
        if (left == null || right == null || hasGroupingConflict(left, right)) return false;
        int m = left.size();
        int n = right.size();
        boolean[][] valid = new boolean[m][n];
        for (int i = 0; i < m; i++) {
            if (left.isPlaceholder[i]) continue;
            for (int j = 0; j < n; j++) {
                if (right.isPlaceholder[j]) continue;
                if (Collections.disjoint(left.expandedParts.get(i), right.expandedParts.get(j))) continue;
                if (hasSameScriptConflict(right.visibleParts.get(j), left.expandedParts.get(i))
                        || hasSameScriptConflict(left.visibleParts.get(i), right.expandedParts.get(j))) {
                    continue;
                }
                valid[i][j] = true;
            }
        }
        return valid[0][0] || (m == n && countBijections(valid, m, n, 2) == 1);
    }

    private static void evaluateRuleA(ResolvedCredit target, ResolvedCredit cand, Eval eval, Policy policy) {
        int m = target.size();
        int n = cand.size();
        boolean[][] rawPair = new boolean[m][n];
        boolean[][] conflictPair = new boolean[m][n];
        boolean[][] validPair = new boolean[m][n];
        boolean anyRawPair = false;
        boolean anyValidPair = false;
        boolean anySameScriptConflict = false;
        StringBuilder matchedSummary = new StringBuilder();

        for (int i = 0; i < m; i++) {
            if (target.isPlaceholder[i]) continue;
            Set<String> tVis = target.visibleParts.get(i);
            Set<String> tExp = target.expandedParts.get(i);
            for (int j = 0; j < n; j++) {
                if (cand.isPlaceholder[j]) continue;
                Set<String> cVis = cand.visibleParts.get(j);
                Set<String> cExp = cand.expandedParts.get(j);
                if (Collections.disjoint(tExp, cExp)) continue;
                rawPair[i][j] = true;
                anyRawPair = true;
                if (hasSameScriptConflict(cVis, tExp) || hasSameScriptConflict(tVis, cExp)) {
                    conflictPair[i][j] = true;
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
        if (!anyRawPair) {
            return;
        }

        boolean fullCoverage = (m == n) && countBijections(validPair, m, n, 2) == 1;

        // A4 BEFORE A5 fallback: if raw member overlap exists ONLY on non-lead/guest members (and is not a
        // full 1-to-1 permutation), reject immediately without allowing Rule B fallback — even if that guest
        // overlap also carries a same-script parenthetical conflict (e.g. North / Guest -> Guest (Stranger)).
        if (!fullCoverage && !rawPair[0][0]) {
            eval.ruleAGuestOnly = true;
            return;
        }

        // A5 member-level conflict check: a same-script parenthetical conflict on any overlapping member
        // (e.g. lead Ye (Taylor Swift) or guest Guest (Stranger) in North / Guest (Stranger)) cannot be hidden
        // by another member's overlap; it vetoes Rule A while allowing Rule B fallback under A5's gates.
        if (anySameScriptConflict && hasUnresolvedSameScriptConflict(conflictPair, validPair, m, n)) {
            eval.ruleASameScriptVeto = true;
            eval.detail = appendDetail(eval.detail, "same_script_conflict");
            return;
        }

        if (!anyValidPair) {
            return;
        }

        boolean leadMatched = validPair[0][0];
        if (!fullCoverage && !leadMatched) {
            eval.ruleAGuestOnly = true;
            return;
        }

        boolean oneSideSubset = fullCoverage || hasUniqueSubsetInjection(validPair, m, n);

        eval.ruleAFullCoverage = fullCoverage;
        eval.ruleAOneSideSubset = oneSideSubset;
        eval.ruleALeadMatched = leadMatched;

        if (policy == Policy.PLAYBACK) {
            eval.ruleAAccepted = true;
            eval.ruleAEligibleForBatchBlock = true;
            return;
        }

        // Policy.WRITE: full member coverage may cross albums; partial coverage requires unique one-side subset
        // coverage, official alias or provider-ID canonical evidence on the intersecting lead token, and same
        // non-compilation album (A7).
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
        if (!hasLeadOfficialEvidence(target, cand)) {
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

    private static boolean hasUnresolvedSameScriptConflict(boolean[][] conflictPair, boolean[][] validPair,
                                                           int m, int n) {
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (!conflictPair[i][j]) continue;
                boolean targetHasValid = false;
                for (int jj = 0; jj < n; jj++) {
                    if (validPair[i][jj]) {
                        targetHasValid = true;
                        break;
                    }
                }
                boolean candHasValid = false;
                for (int ii = 0; ii < m; ii++) {
                    if (validPair[ii][j]) {
                        candHasValid = true;
                        break;
                    }
                }
                if (!targetHasValid || !candHasValid) return true;
            }
        }
        return false;
    }

    /**
     * Checks that the actual intersecting token between target lead and candidate lead is backed by either:
     * (1) an official provider alias on either side matching the other side's resolved lead tokens, or
     * (2) a direct canonical base-name match (excluding free bracket text) backed by a verified provider ID.
     */
    private static boolean hasLeadOfficialEvidence(ResolvedCredit target, ResolvedCredit cand) {
        Set<String> tBase = target.baseParts.get(0);
        Set<String> tOff = target.officialAliasParts.get(0);
        Set<String> tExp = target.expandedParts.get(0);
        Set<String> cBase = cand.baseParts.get(0);
        Set<String> cOff = cand.officialAliasParts.get(0);
        Set<String> cExp = cand.expandedParts.get(0);

        for (String tok : tExp) {
            if (!cExp.contains(tok)) continue;
            if (tOff.contains(tok) || cOff.contains(tok)) return true;
            if (tBase.contains(tok) && cBase.contains(tok)
                    && (target.hasProviderId[0] || cand.hasProviderId[0])) {
                return true;
            }
        }
        return false;
    }

    /** Exact order-independent count of 1-to-1 bijections in an m x n boolean matrix, capped at {@code limit}. */
    private static int countBijections(boolean[][] matrix, int m, int n, int limit) {
        if (m == 0 || m != n) return 0;
        return countInjectionsRows(matrix, m, n, 0, new boolean[n], limit);
    }

    /** Exact order-independent check for a unique injection from the smaller credit into the larger credit. */
    private static boolean hasUniqueSubsetInjection(boolean[][] matrix, int m, int n) {
        if (m == 0 || n == 0) return false;
        if (m <= n) {
            return countInjectionsRows(matrix, m, n, 0, new boolean[n], 2) == 1;
        }
        boolean[][] transposed = new boolean[n][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) transposed[j][i] = matrix[i][j];
        }
        return countInjectionsRows(transposed, n, m, 0, new boolean[m], 2) == 1;
    }

    private static int countInjectionsRows(boolean[][] matrix, int rows, int cols,
                                           int row, boolean[] usedCol, int limit) {
        if (row == rows) return 1;
        int found = 0;
        for (int c = 0; c < cols; c++) {
            if (usedCol[c] || !matrix[row][c]) continue;
            usedCol[c] = true;
            found += countInjectionsRows(matrix, rows, cols, row + 1, usedCol, limit - found);
            usedCol[c] = false;
            if (found >= limit) return found;
        }
        return found;
    }

    private static Comparator<RankedHit> comparatorFor(Policy policy) {
        return (a, b) -> {
            int byRule = Integer.compare(ruleRank(a.rule), ruleRank(b.rule));
            if (byRule != 0) return byRule;
            if (policy == Policy.WRITE) {
                int byFull = Boolean.compare(b.fullMemberCoverage, a.fullMemberCoverage);
                if (byFull != 0) return byFull;
            }
            int byAlbum = Boolean.compare(b.sameAlbum, a.sameAlbum);
            if (byAlbum != 0) return byAlbum;
            int byGap = Long.compare(a.durationGapMs, b.durationGapMs);
            if (byGap != 0) return byGap;
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
     * Artist-independent hard-gate pre-check shared with metadata enrichment so candidates already ruled out by
     * title, version tags, explicit/non-album live year mismatch, or duration never consume artist-lookup budget.
     */
    static boolean passesArtistIndependentHardGates(Track target, MatchText.TitleInfo targetTitle,
                                                    Candidate cand, MatchText.TitleInfo candTitle) {
        if (target == null || targetTitle == null || cand == null || candTitle == null) return false;
        if (targetTitle.mainTitle.isEmpty() || candTitle.mainTitle.isEmpty()) return false;
        if (!targetTitle.versionTags.equals(candTitle.versionTags)) return false;
        if (!targetTitle.mainTitle.equals(candTitle.mainTitle)) return false;
        if (MatchText.TitleInfo.LIVE_YEAR_CONFLICT.equals(targetTitle.liveYear)
                || MatchText.TitleInfo.LIVE_YEAR_CONFLICT.equals(candTitle.liveYear)) {
            return false;
        }
        if (!targetTitle.liveYear.equals(candTitle.liveYear)) {
            if (!targetTitle.liveYear.isEmpty() && !candTitle.liveYear.isEmpty()) return false;
            boolean sameNonCompAlbum = MatchText.sameNonCompilationAlbum(target.album, cand.album);
            if (!(targetTitle.hasLiveTag && candTitle.hasLiveTag)
                    || !sameNonCompAlbum
                    || !(MatchText.isLiveAlbum(target.album) || MatchText.isLiveAlbum(cand.album))) {
                return false;
            }
        }
        Long gap = MatchTrace.durationGapOrNull(target.duration, cand.durationMs);
        return gap == null || gap <= MatchRules.PLAYBACK_MAX_DURATION_GAP_MS;
    }

    /**
     * Shares {@link TrackMatcher}'s exact Rule A identity, A5 same-script conflict, A6 grouping conflict,
     * and A7 lead-evidence rules with metadata enrichment. Returns ordered {@code cand.artistMembers} indices
     * whose official artist-detail aliases can mathematically unlock Rule A at the requested tier, or an empty
     * list if the candidate already satisfies Rule A, is structurally vetoed, or cannot reach the required tier
     * even if every {@code fetchable} member acquired arbitrary official aliases.
     */
    static List<Integer> enrichmentCandidateMemberIndices(
            Track target, Candidate cand, Policy policy, boolean requireFullCoverage, boolean[] fetchable) {
        if (target == null || cand == null || cand.artistMembers.isEmpty()
                || fetchable == null || fetchable.length != cand.artistMembers.size()) {
            return Collections.emptyList();
        }
        if (!MatchText.knownArtist(target.artist) || !MatchText.knownArtist(cand.artist)) {
            return Collections.emptyList();
        }
        ResolvedCredit targetCredit = resolveCredit(target.artist, target.artistMembers, target.artistAliases);
        ResolvedCredit candCredit = resolveCredit(cand.artist, cand.artistMembers, cand.artistAliases);
        return enrichmentIndicesForCredits(targetCredit, target.album, cand, candCredit,
                policy, requireFullCoverage, fetchable);
    }

    private static List<Integer> enrichmentIndicesForCredits(
            ResolvedCredit targetCredit, String targetAlbum, Candidate cand, ResolvedCredit candCredit,
            Policy policy, boolean requireFullCoverage, boolean[] fetchable) {
        if (targetCredit == null || candCredit == null || cand == null || cand.artistMembers.isEmpty()
                || fetchable == null || fetchable.length != cand.artistMembers.size()
                || hasGroupingConflict(targetCredit, candCredit)) {
            return Collections.emptyList();
        }
        Policy activePolicy = policy == null ? Policy.PLAYBACK : policy;

        int m = targetCredit.size();
        int n = candCredit.size();
        int[] creditToMember = mapCreditSlotsToMembers(cand.artist, cand.artistMembers, n);
        if (creditToMember == null) return Collections.emptyList();

        boolean[] slotFetchable = new boolean[n];
        for (int j = 0; j < n; j++) {
            int memberIdx = creditToMember[j];
            slotFetchable[j] = memberIdx >= 0 && memberIdx < fetchable.length
                    && fetchable[memberIdx] && !candCredit.isPlaceholder[j];
        }

        boolean[][] conflictPair = new boolean[m][n];
        boolean[][] validPair = new boolean[m][n];
        // A valid edge (i, j) is guaranteed permanent under future candidate alias additions iff either j is
        // not fetchable OR target's visible tokens outside cand's current expanded set have no same-script
        // pair (otherwise a newly fetched alias on j can trigger A5 on target's visible bracket and remove (i, j)).
        boolean[][] permanentValid = new boolean[m][n];
        boolean anySameScriptConflict = false;
        for (int i = 0; i < m; i++) {
            if (targetCredit.isPlaceholder[i]) continue;
            Set<String> tVis = targetCredit.visibleParts.get(i);
            Set<String> tExp = targetCredit.expandedParts.get(i);
            for (int j = 0; j < n; j++) {
                if (candCredit.isPlaceholder[j]) continue;
                Set<String> cVis = candCredit.visibleParts.get(j);
                Set<String> cExp = candCredit.expandedParts.get(j);
                if (Collections.disjoint(tExp, cExp)) continue;
                if (hasSameScriptConflict(cVis, tExp) || hasSameScriptConflict(tVis, cExp)) {
                    conflictPair[i][j] = true;
                    anySameScriptConflict = true;
                    continue;
                }
                validPair[i][j] = true;
                permanentValid[i][j] = !slotFetchable[j] || !hasUncoveredSameScriptPair(tVis, cExp);
            }
        }

        boolean sameNonCompAlbum = MatchText.sameNonCompilationAlbum(targetAlbum, cand.album);
        int currentBijections = (m == n) ? countBijections(validPair, m, n, 2) : 0;
        int permanentBijections = (m == n) ? countBijections(permanentValid, m, n, 2) : 0;
        int currentSubsetInjections = (m != n) ? countSubsetInjections(validPair, m, n, 2) : 0;
        int permanentSubsetInjections = (m != n) ? countSubsetInjections(permanentValid, m, n, 2) : 0;
        boolean fullCov = (m == n) && currentBijections == 1;
        boolean unresolvedConflict = anySameScriptConflict
                && hasUnresolvedSameScriptConflict(conflictPair, validPair, m, n);
        boolean leadEvidence = hasLeadOfficialEvidence(targetCredit, candCredit);

        boolean alreadyFull = fullCov && !unresolvedConflict;
        boolean alreadyPartial = false;
        if (!requireFullCoverage && !unresolvedConflict && validPair[0][0]) {
            if (activePolicy == Policy.PLAYBACK) {
                alreadyPartial = true;
            } else {
                alreadyPartial = m != n && sameNonCompAlbum && leadEvidence
                        && currentSubsetInjections == 1;
            }
        }
        if (alreadyFull || alreadyPartial) return Collections.emptyList();

        // Optimistic matrix assuming every fetchable non-placeholder candidate member could gain arbitrary
        // official aliases (which can add to cExp/cOff and clear target-visible A5 conflicts, though never
        // clear an existing candidate-visible same-script conflict against fixed target tokens).
        boolean[][] optimisticValid = new boolean[m][n];
        for (int i = 0; i < m; i++) {
            if (targetCredit.isPlaceholder[i]) continue;
            Set<String> tExp = targetCredit.expandedParts.get(i);
            for (int j = 0; j < n; j++) {
                if (candCredit.isPlaceholder[j]) continue;
                if (validPair[i][j]) {
                    optimisticValid[i][j] = true;
                } else if (slotFetchable[j] && !hasSameScriptConflict(candCredit.visibleParts.get(j), tExp)) {
                    optimisticValid[i][j] = true;
                }
            }
        }

        if (anySameScriptConflict && hasFixedUnresolvableConflict(conflictPair, optimisticValid, slotFetchable, m, n)) {
            return Collections.emptyList();
        }

        // Only permanent edges (those immune to future A5 conflict invalidation on target brackets) prove that
        // >= 2 bijections or subset injections can never collapse back to unique (== 1) after further enrichment.
        boolean canAchieveFull = (m == n) && permanentBijections <= 1
                && countBijections(optimisticValid, m, n, 1) >= 1;
        boolean canAchievePartial = false;
        if (!requireFullCoverage && optimisticValid[0][0]) {
            if (activePolicy == Policy.PLAYBACK) {
                canAchievePartial = true;
            } else if (m != n && sameNonCompAlbum && (leadEvidence || slotFetchable[0])
                    && permanentSubsetInjections <= 1) {
                canAchievePartial = countSubsetInjections(optimisticValid, m, n, 1) >= 1;
            }
        }
        if (!canAchieveFull && !canAchievePartial) return Collections.emptyList();

        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        // 1. Direct blockers: candidate slots whose official aliases can resolve a target-visible A5 conflict
        //    or supply missing A7 WRITE lead official evidence.
        for (int j = 0; j < n; j++) {
            if (!slotFetchable[j]) continue;
            for (int i = 0; i < m; i++) {
                if (conflictPair[i][j]
                        && !hasSameScriptConflict(candCredit.visibleParts.get(j), targetCredit.expandedParts.get(i))) {
                    ordered.add(creditToMember[j]);
                    break;
                }
            }
        }
        if (canAchievePartial && activePolicy == Policy.WRITE && !leadEvidence && slotFetchable[0]) {
            ordered.add(creditToMember[0]);
        }

        // 2. When current validPair already has >= 2 bijections/subset injections (while permanentValid <= 1),
        //    only fetching a candidate slot carrying an A5-fragile edge can break the extra correspondence.
        boolean fullOnlyNeedsFragileBreak = canAchieveFull && currentBijections > 1;
        boolean partialOnlyNeedsFragileBreak = !canAchieveFull && canAchievePartial
                && activePolicy == Policy.WRITE && currentSubsetInjections > 1;
        if (fullOnlyNeedsFragileBreak || partialOnlyNeedsFragileBreak) {
            for (int j = 0; j < n; j++) {
                if (slotFetchable[j] && columnHasFragileEdge(validPair, permanentValid, m, j)) {
                    ordered.add(creditToMember[j]);
                }
            }
            return ordered.isEmpty() ? Collections.emptyList() : new ArrayList<>(ordered);
        }

        // 3. Unmatched / contending candidate slots required for full coverage or partial lead/subset coverage.
        if (canAchieveFull) {
            for (int j = 0; j < n; j++) {
                if (slotFetchable[j] && !columnHasEdge(validPair, m, j)) ordered.add(creditToMember[j]);
            }
            for (int j = 0; j < n; j++) {
                if (slotFetchable[j] && columnContends(validPair, m, n, j)) ordered.add(creditToMember[j]);
            }
            for (int j = 0; j < n; j++) {
                if (slotFetchable[j]) ordered.add(creditToMember[j]);
            }
        } else {
            if (!validPair[0][0] && slotFetchable[0]) ordered.add(creditToMember[0]);
            if (activePolicy == Policy.WRITE && m >= 2 && n >= 2) {
                for (int j = 1; j < n; j++) {
                    if (slotFetchable[j] && !columnHasEdge(validPair, m, j)) ordered.add(creditToMember[j]);
                }
                for (int j = 1; j < n; j++) {
                    if (slotFetchable[j] && columnContends(validPair, m, n, j)) ordered.add(creditToMember[j]);
                }
                for (int j = 1; j < n; j++) {
                    if (slotFetchable[j]) ordered.add(creditToMember[j]);
                }
            }
        }
        return ordered.isEmpty() ? Collections.emptyList() : new ArrayList<>(ordered);
    }

    private static int[] mapCreditSlotsToMembers(String artist, List<ArtistAliases.Member> members, int creditSize) {
        if (members.size() == creditSize && isCompleteConsistentMembers(artist, members)) {
            int[] direct = new int[creditSize];
            for (int i = 0; i < creditSize; i++) direct[i] = i;
            return direct;
        }
        List<Set<String>> visibleParts = MatchText.artistParts(artist);
        if (visibleParts.size() != creditSize) return null;
        int[] memberToSlot = mapConsistentMembers(visibleParts, members);
        if (memberToSlot == null) return null;
        int[] slotToMember = new int[creditSize];
        Arrays.fill(slotToMember, -1);
        for (int i = 0; i < memberToSlot.length; i++) {
            int slot = memberToSlot[i];
            if (slot >= 0 && slot < creditSize) slotToMember[slot] = i;
        }
        return slotToMember;
    }

    private static boolean hasFixedUnresolvableConflict(boolean[][] conflictPair, boolean[][] optimisticValid,
                                                        boolean[] slotFetchable, int m, int n) {
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (!conflictPair[i][j] || slotFetchable[j]) continue;
                boolean targetCanMatch = false;
                for (int jj = 0; jj < n; jj++) {
                    if (optimisticValid[i][jj]) { targetCanMatch = true; break; }
                }
                boolean candCanMatch = false;
                for (int ii = 0; ii < m; ii++) {
                    if (optimisticValid[ii][j]) { candCanMatch = true; break; }
                }
                if (!targetCanMatch || !candCanMatch) return true;
            }
        }
        return false;
    }

    /**
     * When a pending winning hit relies on A3 one-sided live-year fallback ({@code usedLiveYearFallback}),
     * returns ordered {@code other.artistMembers} indices whose official artist-detail aliases could trigger
     * {@link #findLiveYearCompetition} using the exact same duration windows (30s same-album / 5s explicit same-year)
     * and artist-overlap condition ({@link #creditsHaveLeadOrFullOverlap} against target or, for same-album
     * session competition, winning-hit artist — unaffected by unrelated guest A5 conflicts).
     */
    static List<Integer> liveYearCompetitionEnrichmentIndices(
            Track target, MatchText.TitleInfo targetTitle, Candidate winningHit, Candidate other,
            boolean[] fetchable) {
        if (target == null || targetTitle == null || winningHit == null || other == null
                || other.id.equals(winningHit.id) || other.artistMembers.isEmpty()
                || fetchable == null || fetchable.length != other.artistMembers.size()) {
            return Collections.emptyList();
        }
        MatchText.TitleInfo otherTitle = MatchText.analyzeTitle(other.title);
        boolean sameAlbumCompetitor = isSameAlbumLiveSessionCompetitor(target, targetTitle, other, otherTitle);
        boolean explicitSameYearCompetitor = isExplicitSameYearLiveCompetitor(target, targetTitle, other, otherTitle);
        if (!sameAlbumCompetitor && !explicitSameYearCompetitor) {
            return Collections.emptyList();
        }
        if (!MatchText.knownArtist(other.artist)) return Collections.emptyList();
        ResolvedCredit otherCredit = resolveCredit(other.artist, other.artistMembers, other.artistAliases);
        if (otherCredit == null) return Collections.emptyList();

        LinkedHashSet<Integer> indices = new LinkedHashSet<>();
        if (MatchText.knownArtist(target.artist)) {
            ResolvedCredit targetCredit = resolveCredit(target.artist, target.artistMembers, target.artistAliases);
            indices.addAll(leadOrFullOverlapEnrichmentIndices(targetCredit, other, otherCredit, fetchable));
        }
        if (sameAlbumCompetitor && MatchText.knownArtist(winningHit.artist)) {
            ResolvedCredit winningCredit = resolveCredit(
                    winningHit.artist, winningHit.artistMembers, winningHit.artistAliases);
            indices.addAll(leadOrFullOverlapEnrichmentIndices(winningCredit, other, otherCredit, fetchable));
        }
        return indices.isEmpty() ? Collections.emptyList() : new ArrayList<>(indices);
    }

    /**
     * Exact enrichment counterpart to {@link #creditsHaveLeadOrFullOverlap}: asks whether fetching any
     * {@code fetchable} member of {@code rightCand} can establish {@code valid[0][0]} or a unique full bijection
     * against {@code left}, without letting unrelated guest A5 conflicts mask a lead-to-lead competition.
     */
    private static List<Integer> leadOrFullOverlapEnrichmentIndices(
            ResolvedCredit left, Candidate rightCand, ResolvedCredit right, boolean[] fetchable) {
        if (left == null || right == null || rightCand == null || rightCand.artistMembers.isEmpty()
                || hasGroupingConflict(left, right)) {
            return Collections.emptyList();
        }
        int m = left.size();
        int n = right.size();
        int[] creditToMember = mapCreditSlotsToMembers(rightCand.artist, rightCand.artistMembers, n);
        if (creditToMember == null) return Collections.emptyList();

        boolean[] slotFetchable = new boolean[n];
        for (int j = 0; j < n; j++) {
            int memberIdx = creditToMember[j];
            slotFetchable[j] = memberIdx >= 0 && memberIdx < fetchable.length
                    && fetchable[memberIdx] && !right.isPlaceholder[j];
        }

        boolean[][] validPair = new boolean[m][n];
        boolean[][] permanentValid = new boolean[m][n];
        boolean[][] optimisticValid = new boolean[m][n];
        for (int i = 0; i < m; i++) {
            if (left.isPlaceholder[i]) continue;
            Set<String> tVis = left.visibleParts.get(i);
            Set<String> tExp = left.expandedParts.get(i);
            for (int j = 0; j < n; j++) {
                if (right.isPlaceholder[j]) continue;
                Set<String> cVis = right.visibleParts.get(j);
                Set<String> cExp = right.expandedParts.get(j);
                boolean hasValid = !Collections.disjoint(tExp, cExp)
                        && !hasSameScriptConflict(cVis, tExp)
                        && !hasSameScriptConflict(tVis, cExp);
                if (hasValid) {
                    validPair[i][j] = true;
                    permanentValid[i][j] = !slotFetchable[j] || !hasUncoveredSameScriptPair(tVis, cExp);
                    optimisticValid[i][j] = true;
                } else if (slotFetchable[j] && !hasSameScriptConflict(cVis, tExp)) {
                    optimisticValid[i][j] = true;
                }
            }
        }

        int currentBijections = (m == n) ? countBijections(validPair, m, n, 2) : 0;
        if (validPair[0][0] || (m == n && currentBijections == 1)) {
            return Collections.emptyList();
        }
        int permanentBijections = (m == n) ? countBijections(permanentValid, m, n, 2) : 0;
        boolean canLead = optimisticValid[0][0] && slotFetchable[0];
        boolean canFull = (m == n) && permanentBijections <= 1
                && countBijections(optimisticValid, m, n, 1) >= 1;
        if (!canLead && !canFull) return Collections.emptyList();

        LinkedHashSet<Integer> ordered = new LinkedHashSet<>();
        if (canLead) {
            ordered.add(creditToMember[0]);
            return new ArrayList<>(ordered);
        }
        if (currentBijections > 1) {
            for (int j = 0; j < n; j++) {
                if (slotFetchable[j] && columnHasFragileEdge(validPair, permanentValid, m, j)) {
                    ordered.add(creditToMember[j]);
                }
            }
            return ordered.isEmpty() ? Collections.emptyList() : new ArrayList<>(ordered);
        }
        for (int j = 0; j < n; j++) {
            if (slotFetchable[j] && !columnHasEdge(validPair, m, j)) ordered.add(creditToMember[j]);
        }
        for (int j = 0; j < n; j++) {
            if (slotFetchable[j] && columnContends(validPair, m, n, j)) ordered.add(creditToMember[j]);
        }
        for (int j = 0; j < n; j++) {
            if (slotFetchable[j]) ordered.add(creditToMember[j]);
        }
        return ordered.isEmpty() ? Collections.emptyList() : new ArrayList<>(ordered);
    }

    /**
     * Returns ordered {@code cand.artistMembers} indices whose current Rule A correspondence against
     * {@code target} depends on an A5-fragile edge ({@code validPair[i][j]} where {@code target}'s visible
     * tokens outside {@code cand}'s expanded set still contain a same-script pair), so enrichment can verify
     * whether official detail aliases trigger A5 and invalidate a pending match before short-circuiting.
     */
    static List<Integer> fragileAcceptedMemberIndices(Track target, Candidate cand, boolean[] fetchable) {
        if (target == null || cand == null || cand.artistMembers.isEmpty()
                || fetchable == null || fetchable.length != cand.artistMembers.size()) {
            return Collections.emptyList();
        }
        if (!MatchText.knownArtist(target.artist) || !MatchText.knownArtist(cand.artist)) {
            return Collections.emptyList();
        }
        ResolvedCredit targetCredit = resolveCredit(target.artist, target.artistMembers, target.artistAliases);
        ResolvedCredit candCredit = resolveCredit(cand.artist, cand.artistMembers, cand.artistAliases);
        if (targetCredit == null || candCredit == null) return Collections.emptyList();

        int m = targetCredit.size();
        int n = candCredit.size();
        int[] creditToMember = mapCreditSlotsToMembers(cand.artist, cand.artistMembers, n);
        if (creditToMember == null) return Collections.emptyList();

        LinkedHashSet<Integer> fragile = new LinkedHashSet<>();
        for (int i = 0; i < m; i++) {
            if (targetCredit.isPlaceholder[i]) continue;
            Set<String> tVis = targetCredit.visibleParts.get(i);
            if (tVis.size() < 2) continue;
            Set<String> tExp = targetCredit.expandedParts.get(i);
            for (int j = 0; j < n; j++) {
                int memberIdx = creditToMember[j];
                if (memberIdx < 0 || memberIdx >= fetchable.length
                        || !fetchable[memberIdx] || candCredit.isPlaceholder[j]) {
                    continue;
                }
                Set<String> cVis = candCredit.visibleParts.get(j);
                Set<String> cExp = candCredit.expandedParts.get(j);
                if (Collections.disjoint(tExp, cExp)) continue;
                if (hasSameScriptConflict(cVis, tExp) || hasSameScriptConflict(tVis, cExp)) continue;
                if (hasUncoveredSameScriptPair(tVis, cExp)) {
                    fragile.add(memberIdx);
                }
            }
        }
        return fragile.isEmpty() ? Collections.emptyList() : new ArrayList<>(fragile);
    }

    /**
     * Returns true iff {@code targetVisible \ candExpanded} contains two distinct tokens sharing a Unicode script,
     * meaning a future alias added to {@code candExpanded} could trigger A5 on {@code targetVisible} and remove the edge.
     */
    private static boolean hasUncoveredSameScriptPair(Set<String> targetVisible, Set<String> candExpanded) {
        if (targetVisible.size() < 2) return false;
        List<Set<Character.UnicodeScript>> uncoveredScripts = new ArrayList<>();
        for (String tok : targetVisible) {
            if (candExpanded.contains(tok)) continue;
            Set<Character.UnicodeScript> sc = scriptsOf(tok);
            if (sc.isEmpty()) continue;
            for (Set<Character.UnicodeScript> prev : uncoveredScripts) {
                if (!Collections.disjoint(prev, sc)) return true;
            }
            uncoveredScripts.add(sc);
        }
        return false;
    }

    private static boolean columnHasFragileEdge(boolean[][] validPair, boolean[][] permanentValid,
                                                int rows, int col) {
        for (int i = 0; i < rows; i++) {
            if (validPair[i][col] && !permanentValid[i][col]) return true;
        }
        return false;
    }

    private static int countSubsetInjections(boolean[][] matrix, int m, int n, int limit) {
        if (m == 0 || n == 0) return 0;
        if (m <= n) return countInjectionsRows(matrix, m, n, 0, new boolean[n], limit);
        boolean[][] transposed = new boolean[n][m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) transposed[j][i] = matrix[i][j];
        }
        return countInjectionsRows(transposed, n, m, 0, new boolean[m], limit);
    }

    private static boolean columnHasEdge(boolean[][] matrix, int rows, int col) {
        for (int i = 0; i < rows; i++) if (matrix[i][col]) return true;
        return false;
    }

    private static boolean columnContends(boolean[][] matrix, int rows, int cols, int col) {
        for (int i = 0; i < rows; i++) {
            if (!matrix[i][col]) continue;
            for (int other = 0; other < cols; other++) {
                if (other != col && matrix[i][other]) return true;
            }
        }
        return false;
    }

    /**
     * Order-independent structural artist-grouping conflict detector — e.g. single structured band
     * {@code "A & B"} vs split {@code "A / B"}, {@code "AC/DC"} vs {@code "AC / DC"},
     * {@code "AB / C"} vs {@code "BC / A"}, {@code "AB (甲) / C (乙)"} vs {@code "A (甲) / BC (乙)"} —
     * vetoing both Rule A and Rule B, while never rejecting identical base groupings such as
     * {@code "A & B / A / B"} vs {@code "A & B / A / B"} (paired off 1-to-1 in step 1).
     */
    private static boolean hasGroupingConflict(ResolvedCredit left, ResolvedCredit right) {
        // 1. Pair off identical normalized base members 1-to-1 so identical groupings (including
        //    "A & B / A / B" vs "A & B / A / B" or shared members in "A & B / Guest" vs "A / B / Guest")
        //    match cleanly without allowing free bracket text to mask a provable base-name repartition.
        List<String> leftBases = baseNormalizedList(left);
        List<String> rightBases = baseNormalizedList(right);
        boolean[] usedLeft = new boolean[leftBases.size()];
        boolean[] usedRight = new boolean[rightBases.size()];
        for (int i = 0; i < leftBases.size(); i++) {
            String lb = leftBases.get(i);
            if (lb.isEmpty()) continue;
            for (int j = 0; j < rightBases.size(); j++) {
                if (!usedRight[j] && lb.equals(rightBases.get(j))) {
                    usedLeft[i] = true;
                    usedRight[j] = true;
                    break;
                }
            }
        }

        List<String> leftRem = new ArrayList<>();
        List<Integer> leftRemIdx = new ArrayList<>();
        for (int i = 0; i < leftBases.size(); i++) {
            if (!usedLeft[i] && !leftBases.get(i).isEmpty()) {
                leftRem.add(leftBases.get(i));
                leftRemIdx.add(i);
            }
        }
        List<String> rightRem = new ArrayList<>();
        List<Integer> rightRemIdx = new ArrayList<>();
        for (int j = 0; j < rightBases.size(); j++) {
            if (!usedRight[j] && !rightBases.get(j).isEmpty()) {
                rightRem.add(rightBases.get(j));
                rightRemIdx.add(j);
            }
        }
        if (leftRem.isEmpty() || rightRem.isEmpty()) return false;

        // 2. Order-independent exact string re-partition check (catches AB/C vs BC/A, AC/DC vs DC/AC, A&B vs B/A).
        if ((leftRem.size() > 1 || rightRem.size() > 1) && canRepartitionSameString(leftRem, rightRem)) {
            return true;
        }

        // 3. Single remaining member splitting via internal separator into >=2 distinct remaining members on the other side.
        return splitsIntoRemainingMembers(left, leftRemIdx, right, rightRemIdx)
                || splitsIntoRemainingMembers(right, rightRemIdx, left, leftRemIdx);
    }

    private static List<String> baseNormalizedList(ResolvedCredit credit) {
        List<String> list = new ArrayList<>(credit.rawMemberNames.size());
        for (String raw : credit.rawMemberNames) {
            String base = stripBrackets(raw);
            list.add(MatchText.normalizeArtistToken(base.isEmpty() ? raw : base));
        }
        return list;
    }

    private static boolean canRepartitionSameString(List<String> left, List<String> right) {
        if (left.size() > 6 || right.size() > 6) return false;
        int leftLen = 0, rightLen = 0;
        StringBuilder lAll = new StringBuilder();
        StringBuilder rAll = new StringBuilder();
        for (String s : left) { leftLen += s.length(); lAll.append(s); }
        for (String s : right) { rightLen += s.length(); rAll.append(s); }
        if (leftLen == 0 || leftLen != rightLen) return false;
        char[] lChars = lAll.toString().toCharArray();
        char[] rChars = rAll.toString().toCharArray();
        Arrays.sort(lChars);
        Arrays.sort(rChars);
        if (!Arrays.equals(lChars, rChars)) return false;
        return repartitionDfs(left, right, 0, 0, "", "");
    }

    private static boolean repartitionDfs(List<String> left, List<String> right,
                                          int usedLeft, int usedRight, String remLeft, String remRight) {
        int fullLeft = (1 << left.size()) - 1;
        int fullRight = (1 << right.size()) - 1;
        if (usedLeft == fullLeft && usedRight == fullRight && remLeft.isEmpty() && remRight.isEmpty()) {
            return true;
        }
        if (remLeft.isEmpty()) {
            for (int i = 0; i < left.size(); i++) {
                if ((usedLeft & (1 << i)) != 0) continue;
                String s = left.get(i);
                if (remRight.isEmpty()) {
                    if (repartitionDfs(left, right, usedLeft | (1 << i), usedRight, s, "")) return true;
                } else if (s.startsWith(remRight)) {
                    if (repartitionDfs(left, right, usedLeft | (1 << i), usedRight,
                            s.substring(remRight.length()), "")) return true;
                } else if (remRight.startsWith(s)) {
                    if (repartitionDfs(left, right, usedLeft | (1 << i), usedRight,
                            "", remRight.substring(s.length()))) return true;
                }
            }
            return false;
        }
        for (int j = 0; j < right.size(); j++) {
            if ((usedRight & (1 << j)) != 0) continue;
            String s = right.get(j);
            if (remLeft.startsWith(s)) {
                if (repartitionDfs(left, right, usedLeft, usedRight | (1 << j),
                        remLeft.substring(s.length()), "")) return true;
            } else if (s.startsWith(remLeft)) {
                if (repartitionDfs(left, right, usedLeft, usedRight | (1 << j),
                        "", s.substring(remLeft.length()))) return true;
            }
        }
        return false;
    }

    private static boolean splitsIntoRemainingMembers(ResolvedCredit source, List<Integer> sourceIndices,
                                                      ResolvedCredit other, List<Integer> otherIndices) {
        if (otherIndices.size() < 2) return false;
        for (int srcIdx : sourceIndices) {
            String raw = source.rawMemberNames.get(srcIdx);
            String base = stripBrackets(raw);
            String targetStr = MatchText.fold(base.isEmpty() ? raw : base);
            List<Set<String>> subParts = new ArrayList<>();
            for (String piece : MatchRules.GROUP_SUB_SEPARATOR.split(targetStr)) {
                Set<String> atoms = MatchText.memberAtomNames(piece);
                if (!atoms.isEmpty()) subParts.add(atoms);
            }
            if (subParts.size() < 2) continue;
            boolean[] matched = new boolean[otherIndices.size()];
            int distinctMatched = 0;
            for (Set<String> sub : subParts) {
                for (int k = 0; k < otherIndices.size(); k++) {
                    int otherIdx = otherIndices.get(k);
                    if (!matched[k] && !Collections.disjoint(sub, other.visibleParts.get(otherIdx))) {
                        matched[k] = true;
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
            int count = members.size();
            List<String> rawNames = new ArrayList<>(count);
            List<Set<String>> atomBase = new ArrayList<>(count);
            List<Set<String>> atomVisible = new ArrayList<>(count);
            List<Set<String>> atomOfficial = new ArrayList<>(count);
            List<Set<String>> atomExpanded = new ArrayList<>(count);
            boolean[] hasProviderId = new boolean[count];
            boolean[] isPlaceholder = new boolean[count];
            boolean anyNonPlaceholder = false;
            List<String> identity = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                ArtistAliases.Member member = members.get(i);
                rawNames.add(member.name);
                Set<String> rawBase = baseAtomSet(member.name);
                Set<String> rawVisible = MatchText.memberAtomNames(member.name);
                isPlaceholder[i] = isPlaceholderMember(rawBase, rawVisible);
                Set<String> base = isPlaceholder[i] ? Collections.emptySet() : filterNonPlaceholderTokens(rawBase);
                Set<String> visible = isPlaceholder[i] ? Collections.emptySet() : filterNonPlaceholderTokens(rawVisible);
                Set<String> official = new LinkedHashSet<>();
                Set<String> expanded = new LinkedHashSet<>(visible);
                if (!isPlaceholder[i]) {
                    for (String alias : member.aliases) {
                        for (String token : MatchText.memberAtomNames(alias)) {
                            if (MatchText.isUnknownArtistToken(token)) continue;
                            official.add(token);
                            expanded.add(token);
                        }
                    }
                    anyNonPlaceholder = true;
                }
                atomBase.add(base);
                atomVisible.add(visible);
                atomOfficial.add(official);
                atomExpanded.add(expanded);
                hasProviderId[i] = !member.providerId.isEmpty();
                identity.add(member.providerId.isEmpty()
                        ? "name:" + MatchText.foldCompact(member.name)
                        : "id:" + member.providerId);
            }
            if (!anyNonPlaceholder) return null;
            if (count == 1 && !isPlaceholder[0] && flatAliases != null) {
                Set<String> off0 = atomOfficial.get(0);
                Set<String> exp0 = atomExpanded.get(0);
                for (String alias : flatAliases) {
                    String cleanAlias = cleanAliasToken(alias);
                    if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
                    for (String token : MatchText.memberAtomNames(cleanAlias)) {
                        if (MatchText.isUnknownArtistToken(token)) continue;
                        off0.add(token);
                        exp0.add(token);
                    }
                }
            }
            Collections.sort(identity);
            return new ResolvedCredit(rawNames, atomBase, atomVisible, atomOfficial, atomExpanded,
                    hasProviderId, isPlaceholder, Collections.unmodifiableList(identity));
        }

        List<String> rawParts = MatchText.artistRawParts(artist);
        int count = visibleParts.size();
        List<Set<String>> baseList = new ArrayList<>(count);
        List<Set<String>> filteredVisibleList = new ArrayList<>(count);
        List<Set<String>> officialList = new ArrayList<>(count);
        List<Set<String>> expanded = new ArrayList<>(count);
        boolean[] isPlaceholder = new boolean[count];
        boolean anyNonPlaceholder = false;
        for (int i = 0; i < count; i++) {
            String raw = i < rawParts.size() ? rawParts.get(i) : "";
            Set<String> rawBase = baseAtomSet(raw);
            Set<String> rawVisible = visibleParts.get(i);
            isPlaceholder[i] = isPlaceholderMember(rawBase, rawVisible);
            Set<String> base = isPlaceholder[i] ? Collections.emptySet() : filterNonPlaceholderTokens(rawBase);
            Set<String> visible = isPlaceholder[i] ? Collections.emptySet() : filterNonPlaceholderTokens(rawVisible);
            baseList.add(base);
            filteredVisibleList.add(visible);
            officialList.add(new LinkedHashSet<>());
            expanded.add(new LinkedHashSet<>(visible));
            if (!isPlaceholder[i]) anyNonPlaceholder = true;
        }
        if (!anyNonPlaceholder) return null;
        boolean[] hasProviderId = new boolean[count];
        String[] mappedProviderIds = new String[count];

        if (members != null && !members.isEmpty()) {
            int[] mapped = mapConsistentMembers(visibleParts, members);
            if (mapped != null) {
                for (int i = 0; i < members.size(); i++) {
                    int visibleIndex = mapped[i];
                    if (visibleIndex < 0) continue;
                    ArtistAliases.Member member = members.get(i);
                    if (!member.providerId.isEmpty()) {
                        mappedProviderIds[visibleIndex] = member.providerId;
                        hasProviderId[visibleIndex] = true;
                    }
                    if (isPlaceholder[visibleIndex]) continue;
                    Set<String> offSet = officialList.get(visibleIndex);
                    Set<String> expSet = expanded.get(visibleIndex);
                    for (String alias : member.aliases) {
                        for (String token : MatchText.memberAtomNames(alias)) {
                            if (MatchText.isUnknownArtistToken(token)) continue;
                            offSet.add(token);
                            expSet.add(token);
                        }
                    }
                }
            }
        }

        if (count == 1 && !isPlaceholder[0] && isSingleArtistCredit(artist) && flatAliases != null) {
            Set<String> off0 = officialList.get(0);
            Set<String> exp0 = expanded.get(0);
            for (String alias : flatAliases) {
                String cleanAlias = cleanAliasToken(alias);
                if (cleanAlias.isEmpty() || !isSingleArtistCredit(cleanAlias)) continue;
                for (String token : MatchText.memberAtomNames(cleanAlias)) {
                    if (MatchText.isUnknownArtistToken(token)) continue;
                    off0.add(token);
                    exp0.add(token);
                }
            }
        }

        List<String> rawNames = new ArrayList<>(count);
        List<String> identity = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String raw = i < rawParts.size() ? rawParts.get(i) : "";
            rawNames.add(raw);
            String pid = mappedProviderIds[i];
            identity.add(pid != null && !pid.isEmpty()
                    ? "id:" + pid
                    : "name:" + MatchText.foldCompact(raw));
        }
        Collections.sort(identity);
        return new ResolvedCredit(rawNames, baseList, filteredVisibleList, officialList, expanded,
                hasProviderId, isPlaceholder, Collections.unmodifiableList(identity));
    }

    private static Set<String> filterNonPlaceholderTokens(Set<String> rawTokens) {
        if (rawTokens == null || rawTokens.isEmpty()) return Collections.emptySet();
        Set<String> filtered = new LinkedHashSet<>(rawTokens.size());
        for (String tok : rawTokens) {
            if (!MatchText.isUnknownArtistToken(tok)) filtered.add(tok);
        }
        return filtered;
    }

    private static boolean isPlaceholderMember(Set<String> base, Set<String> visible) {
        Set<String> tokens = !base.isEmpty() ? base : visible;
        if (tokens.isEmpty()) return true;
        for (String tok : tokens) {
            if (!MatchText.isUnknownArtistToken(tok)) return false;
        }
        return true;
    }

    private static Set<String> baseAtomSet(String memberName) {
        Set<String> out = new LinkedHashSet<>();
        if (memberName == null) return out;
        String base = stripBrackets(MatchText.fold(memberName));
        String token = MatchText.normalizeArtistToken(base);
        if (!token.isEmpty()) out.add(token);
        return out;
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
            Set<Character.UnicodeScript> knownScripts = scriptsOf(known);
            if (knownScripts.isEmpty()) continue;
            for (String other : visibleNames) {
                if (!expectedFromOther.contains(other)
                        && !Collections.disjoint(knownScripts, scriptsOf(other))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns all non-common/non-inherited Unicode scripts present in {@code text} (Kana masks Han). */
    private static Set<Character.UnicodeScript> scriptsOf(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) == '\u0000') {
            return Collections.emptySet();
        }
        EnumSet<Character.UnicodeScript> scripts = EnumSet.noneOf(Character.UnicodeScript.class);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.COMMON
                    && script != Character.UnicodeScript.INHERITED
                    && script != Character.UnicodeScript.UNKNOWN) {
                scripts.add(script);
            }
        }
        if (scripts.contains(Character.UnicodeScript.HIRAGANA)
                || scripts.contains(Character.UnicodeScript.KATAKANA)) {
            scripts.remove(Character.UnicodeScript.HAN);
        }
        return scripts;
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

    private static void emitTrace(MatchTrace trace, MatchText.TitleInfo targetTitle,
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
