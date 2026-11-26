package dev.yesplaymusic.probe;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Legacy match entry points delegating text normalization and rules to {@link MatchText} and {@link MatchRules}. */
public final class QQMatch {
    private QQMatch() {}

    /** Maximum duration gap allowed by playback matching. */
    public static final long UNM_DURATION_MS = MatchRules.PLAYBACK_MAX_DURATION_GAP_MS;

    static String foldCompact(String text) {
        return MatchText.foldCompact(text);
    }

    static boolean matchesDisplayCredit(String displayArtist, List<String> memberNames) {
        return MatchText.matchesDisplayCredit(displayArtist, memberNames);
    }

    public static String normalize(String text) {
        return MatchText.normalize(text);
    }

    static String normalizeArtistToken(String text) {
        return MatchText.normalizeArtistToken(text);
    }

    static String cleanSearchTitle(String title) {
        return MatchText.cleanSearchTitle(title);
    }

    static String cleanSearchArtist(String artist) {
        return MatchText.cleanSearchArtist(artist);
    }

    static String version(String value) {
        return MatchText.versionKey(value);
    }

    static String mainTitle(String title) {
        return MatchText.mainTitle(title);
    }

    static boolean sameRecordingTitle(String left, String right) {
        return MatchText.sameRecordingTitle(left, right);
    }

    static Set<String> memberAtomNames(String memberName) {
        return MatchText.memberAtomNames(memberName);
    }

    static Set<String> artistNames(String credit) {
        return MatchText.artistNames(credit);
    }

    static List<Set<String>> artistParts(String credit) {
        return MatchText.artistParts(credit);
    }

    static List<String> artistRawParts(String credit) {
        return MatchText.artistRawParts(credit);
    }

    static boolean knownArtist(String credit) {
        return MatchText.knownArtist(credit);
    }

    static boolean sameArtistParts(List<Set<String>> a, List<Set<String>> b) {
        if (a == null || b == null || a.isEmpty() || a.size() != b.size()) return false;
        boolean[] used = new boolean[b.size()];
        for (Set<String> names : a) {
            int match = -1;
            for (int i = 0; i < b.size(); i++) {
                if (!used[i] && !Collections.disjoint(names, b.get(i))) {
                    if (match >= 0) return false; // ambiguous alias attribution
                    match = i;
                }
            }
            if (match < 0) return false;
            used[match] = true;
        }
        return true;
    }

    public static int score(String title, String artist, long duration,
                            String candidateTitle, String candidateArtist, long candidateDuration) {
        return score(title, artist, "", duration, candidateTitle, candidateArtist, "", candidateDuration);
    }

    public static int score(String title, String artist, String album, long duration,
                            String candidateTitle, String candidateArtist, String candidateAlbum, long candidateDuration) {
        if (!knownArtist(artist) || !knownArtist(candidateArtist)) return -1;
        return scoreParts(title, artistParts(artist), artist, album, duration,
                candidateTitle, artistParts(candidateArtist), candidateArtist, candidateAlbum, candidateDuration);
    }

    static int scoreParts(String title, List<Set<String>> leftParts, String leftCredit, String album, long duration,
                          String candidateTitle, List<Set<String>> rightParts, String rightCredit,
                          String candidateAlbum, long candidateDuration) {
        String a = normalize(title), b = normalize(candidateTitle);
        if (a.isEmpty() || b.isEmpty() || !version(title).equals(version(candidateTitle))) return -1;
        int score = sameRecordingTitle(title, candidateTitle) ? 100 : -1;
        if (score < 0) return -1;
        if (!sameArtistParts(leftParts, rightParts)) return -1;
        String leftNorm = normalize(leftCredit), rightNorm = normalize(rightCredit);
        boolean exactCredit = !leftNorm.isEmpty() && leftNorm.equals(rightNorm);
        score += leftParts.size() == 1 || exactCredit ? 60 : 40;
        if (knownArtist(album) && knownArtist(candidateAlbum) && sameRecordingTitle(album, candidateAlbum)) score += 10;
        if (duration > 0 && candidateDuration > 0) {
            long difference = Math.abs(duration - candidateDuration);
            if (difference > 8000) return -1;
            score += difference <= 3000 ? 30 : 10;
        }
        return score >= 140 ? score : -1;
    }

    /** Search keyword: the song name followed by every credited artist. */
    public static String keyword(String title, String artist) {
        return MatchText.keyword(title, artist);
    }
}
