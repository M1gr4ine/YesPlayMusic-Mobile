package dev.yesplaymusic.probe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Structured, read-only diagnostic trace for a single cross-source track match attempt.
 * Records only catalog metadata, normalized match fields, gate decisions, and sanitized error codes;
 * never logs cookies, credentials, raw payloads, or signed stream URLs.
 */
final class MatchTrace {
    private static final String LOG_TAG = "YPMMatch";
    private static final String SETTINGS_PREFS = "mobile_settings";
    private static final String SETTINGS_DIAGNOSTICS_KEY = "matchDiagnostics";
    private static final AtomicLong SEQUENCE = new AtomicLong(0L);

    private static final Pattern SAFE_CATALOG_ID = Pattern.compile(
            "^(?:[1-9][0-9]{0,23}|qq:[A-Za-z0-9]{1,128}|[A-Za-z0-9_-]{1,128})$");
    private static final Pattern URL_LIKE = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)(?:^|[\\s;,&?])(?:cookie|music_u|qqmusic_key|qm_keyst|psrf_[a-z0-9_]+|token|vkey|purl|sig|password|secret)\\s*=\\s*[^\\s;,&]+");

    private static volatile boolean diagnosticLoggingEnabled;

    enum Direction {
        NETEASE_TO_QQ,
        QQ_TO_NETEASE
    }

    enum Policy {
        PLAYBACK,
        WRITE
    }

    enum Mode {
        LEGACY,
        SHADOW,
        TRACK_MATCHER
    }

    enum Outcome {
        ACCEPTED,
        REJECTED,
        FILTERED
    }

    enum Rule {
        NONE,
        A,
        B,
        LEGACY_STRICT,
        LEGACY_ALBUM_BRIDGE,
        LEGACY_CATALOG_BRIDGE
    }

    enum Gate {
        NONE,
        INVALID_ID,
        MISSING_MEDIA_MID,
        UNKNOWN_ARTIST,
        TITLE,
        VERSION,
        DURATION,
        ARTIST,
        COMPETING_CREDIT,
        COMPILATION,
        NOT_UNIQUE,
        COMPETING_A,
        AMBIGUOUS,
        WRITE_POLICY,
        VETO
    }

    interface Sink {
        void emit(MatchTrace trace);
    }

    static final Sink NOOP = new Sink() {
        @Override public void emit(MatchTrace trace) {}
    };

    static final class TargetInfo {
        final String catalogId;
        final String title;
        final String artist;
        final String album;
        final long durationMs;
        final int memberCount;
        final int aliasCount;
        final String mainTitle;
        final String versionTags;

        TargetInfo(String catalogId, String title, String artist, String album, long durationMs,
                   int memberCount, int aliasCount, String mainTitle, String versionTags) {
            this.catalogId = sanitizeId(catalogId);
            this.title = safe(title);
            this.artist = safe(artist);
            this.album = safe(album);
            this.durationMs = Math.max(0L, durationMs);
            this.memberCount = Math.max(0, memberCount);
            this.aliasCount = Math.max(0, aliasCount);
            this.mainTitle = safe(mainTitle);
            this.versionTags = safe(versionTags);
        }
    }

    static final class QueryRecord {
        final String stage;
        final int index;
        final String query;
        final int rawCount;
        final int filteredCount;
        final int uniqueAddedCount;

        QueryRecord(String stage, int index, String query, int rawCount, int filteredCount, int uniqueAddedCount) {
            this.stage = safe(stage);
            this.index = index;
            this.query = safe(query);
            this.rawCount = rawCount;
            this.filteredCount = filteredCount;
            this.uniqueAddedCount = uniqueAddedCount;
        }
    }

    static final class CandidateRecord {
        final String candidateId;
        final String mediaMid;
        final int listIndex;
        final int grpIndex;
        final String parentMid;
        final String round;
        final String title;
        final String artist;
        final String album;
        final long durationMs;
        final Long durationGapMs;
        final String targetMainTitle;
        final String candidateMainTitle;
        final String targetVersionTags;
        final String candidateVersionTags;
        final Boolean sameAlbum;
        final Boolean compilationAlbum;
        final String matchedMembers;
        final Outcome outcome;
        final Rule rule;
        final Gate failedGate;
        final Integer score;
        final int rankPosition;
        final String conflictCandidateId;
        final String detail;

        CandidateRecord(String candidateId, String mediaMid, int listIndex, int grpIndex, String parentMid,
                        String round, String title, String artist, String album, long durationMs, Long durationGapMs,
                        String targetMainTitle, String candidateMainTitle, String targetVersionTags,
                        String candidateVersionTags, Boolean sameAlbum, Boolean compilationAlbum,
                        String matchedMembers, Outcome outcome, Rule rule, Gate failedGate, Integer score,
                        int rankPosition, String conflictCandidateId, String detail) {
            this.candidateId = sanitizeId(candidateId);
            this.mediaMid = sanitizeId(mediaMid);
            this.listIndex = listIndex;
            this.grpIndex = grpIndex;
            this.parentMid = sanitizeId(parentMid);
            this.round = safe(round);
            this.title = safe(title);
            this.artist = safe(artist);
            this.album = safe(album);
            this.durationMs = Math.max(0L, durationMs);
            this.durationGapMs = durationGapMs;
            this.targetMainTitle = safe(targetMainTitle);
            this.candidateMainTitle = safe(candidateMainTitle);
            this.targetVersionTags = safe(targetVersionTags);
            this.candidateVersionTags = safe(candidateVersionTags);
            this.sameAlbum = sameAlbum;
            this.compilationAlbum = compilationAlbum;
            this.matchedMembers = safe(matchedMembers);
            this.outcome = outcome == null ? Outcome.REJECTED : outcome;
            this.rule = rule == null ? Rule.NONE : rule;
            this.failedGate = failedGate == null ? Gate.NONE : failedGate;
            this.score = score;
            this.rankPosition = rankPosition;
            this.conflictCandidateId = sanitizeId(conflictCandidateId);
            this.detail = safe(detail);
        }
    }

    static final class VkeyRecord {
        final String mid;
        final String mediaMid;
        final String outcomeCode;

        VkeyRecord(String mid, String mediaMid, String outcomeCode) {
            this.mid = sanitizeId(mid);
            this.mediaMid = sanitizeId(mediaMid);
            this.outcomeCode = safe(outcomeCode);
        }
    }

    final String traceId;
    final Direction direction;
    final String entryPoint;
    final Policy policy;
    final Mode mode;
    final TargetInfo target;

    private final List<QueryRecord> queries = new ArrayList<>();
    private final List<CandidateRecord> candidates = new ArrayList<>();
    private final List<VkeyRecord> vkeyAttempts = new ArrayList<>();
    private final List<String> topCandidateIds = new ArrayList<>();
    private String cachePath = "";
    private String shortCircuitStage = "";
    private String finalOutcome = "";
    private String finalErrorCode = "";

    MatchTrace(Direction direction, String entryPoint, Policy policy, Mode mode, TargetInfo target) {
        this.traceId = "m-" + SEQUENCE.incrementAndGet();
        this.direction = direction;
        this.entryPoint = safe(entryPoint);
        this.policy = policy;
        this.mode = mode;
        this.target = target;
    }

    static void setDiagnosticLoggingEnabled(boolean enabled) {
        diagnosticLoggingEnabled = enabled;
    }

    static boolean isDiagnosticLoggingEnabled() {
        return diagnosticLoggingEnabled;
    }

    static boolean isActive(Sink sink) {
        return sink != null && sink != NOOP;
    }

    static boolean isLogcatEnabled(Context context) {
        if (diagnosticLoggingEnabled) return true;
        if (context == null) return false;
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info != null && (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return true;
        } catch (Throwable ignored) {
            // Safe fallback on JVM stubs or restricted contexts.
        }
        try {
            return context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(SETTINGS_DIAGNOSTICS_KEY, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns an active logcat sink only when tracing is enabled, or NOOP to avoid trace collection overhead. */
    static Sink sinkIfEnabled(Context context) {
        return isLogcatEnabled(context) ? androidLogSink(context) : NOOP;
    }

    static Sink androidLogSink(final Context context) {
        return new Sink() {
            @Override public void emit(MatchTrace trace) {
                if (trace == null || !isLogcatEnabled(context)) return;
                for (String line : trace.toLogLines()) {
                    try {
                        Log.d(LOG_TAG, line);
                    } catch (Throwable ignored) {
                        // Never allow diagnostic logging or JVM android.jar stubs to break matching.
                        return;
                    }
                }
            }
        };
    }

    void addQuery(String stage, int index, String query, int rawCount, int filteredCount, int uniqueAddedCount) {
        queries.add(new QueryRecord(stage, index, query, rawCount, filteredCount, uniqueAddedCount));
    }

    void addCandidate(CandidateRecord record) {
        if (record != null) candidates.add(record);
    }

    void addVkeyAttempt(String mid, String mediaMid, String outcomeCode) {
        vkeyAttempts.add(new VkeyRecord(mid, mediaMid, outcomeCode));
    }

    void setCachePath(String path) {
        this.cachePath = safe(path);
    }

    void setShortCircuitStage(String stage) {
        this.shortCircuitStage = safe(stage);
    }

    void setTopCandidateIds(List<String> ids) {
        topCandidateIds.clear();
        if (ids == null) return;
        for (int i = 0; i < ids.size() && i < 3; i++) {
            String id = sanitizeId(ids.get(i));
            if (!id.isEmpty()) topCandidateIds.add(id);
        }
    }

    void setFinalOutcome(String outcome, String errorCode) {
        this.finalOutcome = safe(outcome);
        this.finalErrorCode = safe(errorCode);
    }

    List<QueryRecord> queries() {
        return Collections.unmodifiableList(queries);
    }

    List<CandidateRecord> candidates() {
        return Collections.unmodifiableList(candidates);
    }

    List<VkeyRecord> vkeyAttempts() {
        return Collections.unmodifiableList(vkeyAttempts);
    }

    List<String> topCandidateIds() {
        return Collections.unmodifiableList(topCandidateIds);
    }

    String cachePath() { return cachePath; }
    String shortCircuitStage() { return shortCircuitStage; }
    String finalOutcome() { return finalOutcome; }
    String finalErrorCode() { return finalErrorCode; }

    List<String> toLogLines() {
        List<String> lines = new ArrayList<>();
        lines.add("[" + traceId + "] start dir=" + direction + " entry=" + entryPoint
                + " policy=" + policy + " mode=" + mode
                + " targetId=" + target.catalogId
                + " title=" + quote(target.title)
                + " main=" + quote(target.mainTitle)
                + " ver=" + quote(target.versionTags)
                + " artist=" + quote(target.artist)
                + " album=" + quote(target.album)
                + " dur=" + target.durationMs
                + " members=" + target.memberCount
                + " aliases=" + target.aliasCount);
        for (QueryRecord q : queries) {
            lines.add("[" + traceId + "] query#" + q.index + " stage=" + q.stage
                    + " q=" + quote(q.query)
                    + " raw=" + q.rawCount + " filtered=" + q.filteredCount + " added=" + q.uniqueAddedCount);
        }
        for (CandidateRecord c : candidates) {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(traceId).append("] cand round=").append(c.round)
                    .append(" idx=").append(c.listIndex);
            if (c.grpIndex >= 0) sb.append(" grp=").append(c.grpIndex).append(" parent=").append(c.parentMid);
            sb.append(" id=").append(c.candidateId);
            if (!c.mediaMid.isEmpty()) sb.append(" media=").append(c.mediaMid);
            sb.append(" outcome=").append(c.outcome)
                    .append(" rule=").append(c.rule)
                    .append(" gate=").append(c.failedGate);
            if (c.score != null) sb.append(" score=").append(c.score);
            if (c.rankPosition >= 0) sb.append(" rank=").append(c.rankPosition);
            sb.append(" gap=").append(c.durationGapMs == null ? "null" : c.durationGapMs)
                    .append(" dur=").append(c.durationMs);
            if (c.sameAlbum != null) sb.append(" sameAlbum=").append(c.sameAlbum);
            if (c.compilationAlbum != null) sb.append(" comp=").append(c.compilationAlbum);
            sb.append(" title=").append(quote(c.title))
                    .append(" main=").append(quote(c.candidateMainTitle))
                    .append(" ver=").append(quote(c.candidateVersionTags))
                    .append(" artist=").append(quote(c.artist))
                    .append(" album=").append(quote(c.album));
            if (!c.matchedMembers.isEmpty()) sb.append(" members=").append(quote(c.matchedMembers));
            if (!c.conflictCandidateId.isEmpty()) sb.append(" conflict=").append(c.conflictCandidateId);
            if (!c.detail.isEmpty()) sb.append(" detail=").append(quote(c.detail));
            lines.add(sb.toString());
        }
        for (VkeyRecord v : vkeyAttempts) {
            lines.add("[" + traceId + "] vkey mid=" + v.mid + " media=" + v.mediaMid + " result=" + quote(v.outcomeCode));
        }
        lines.add("[" + traceId + "] end cache=" + cachePath
                + " shortCircuit=" + shortCircuitStage
                + " top3=" + topCandidateIds
                + " outcome=" + finalOutcome
                + (finalErrorCode.isEmpty() ? "" : " error=" + quote(finalErrorCode)));
        return lines;
    }

    String formatSummary() {
        StringBuilder sb = new StringBuilder();
        for (String line : toLogLines()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    static Long durationGapOrNull(long leftMs, long rightMs) {
        return leftMs > 0L && rightMs > 0L ? Math.abs(leftMs - rightMs) : null;
    }

    static String sanitizeId(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        return SAFE_CATALOG_ID.matcher(trimmed).matches() ? trimmed : "<invalid>";
    }

    static String sanitizeText(String value) {
        if (value == null || value.isEmpty()) return "";
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('"', '\'');
        cleaned = URL_LIKE.matcher(cleaned).replaceAll("[redacted-url]");
        cleaned = SECRET_KV.matcher(cleaned).replaceAll(" [redacted]");
        cleaned = cleaned.trim();
        if (cleaned.length() > 160) cleaned = cleaned.substring(0, 160) + "...";
        return cleaned;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String quote(String value) {
        String sanitized = sanitizeText(value);
        return "\"" + sanitized + "\"";
    }
}
