package dev.yesplaymusic.probe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text normalization and token extraction for cross-source track matching.
 * Uses NFKC + lowercase + OpenCC-derived CJK folding (minSdk 26 compatible, no ICU Transliterator).
 */
final class MatchText {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern SYMBOL_CHAR = Pattern.compile("\\p{S}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");
    private static final String SYMBOL_PREFIX = "\u0000";

    private static final String[][] FOLDED_CJK_VERSION_TAGS = foldCjkTags(MatchRules.CJK_VERSION_TAGS);
    private static final String[] FOLDED_COMPILATION_KEYWORDS = foldKeywords(MatchRules.COMPILATION_KEYWORDS);
    private static final Set<String> FOLDED_UNKNOWN_ARTIST_TOKENS = foldTokenSet(MatchRules.UNKNOWN_ARTIST_TOKENS);
    private static final String FOLDED_VARIANT_SUFFIX = fold(MatchRules.VARIANT_SUFFIX);

    private MatchText() {}

    private static String[][] foldCjkTags(String[][] source) {
        String[][] folded = new String[source.length][2];
        for (int i = 0; i < source.length; i++) {
            folded[i][0] = fold(source[i][0]);
            folded[i][1] = source[i][1];
        }
        return folded;
    }

    private static String[] foldKeywords(String[] source) {
        String[] folded = new String[source.length];
        for (int i = 0; i < source.length; i++) folded[i] = normalize(source[i]);
        return folded;
    }

    private static Set<String> foldTokenSet(Set<String> source) {
        Set<String> folded = new LinkedHashSet<>();
        for (String item : source) folded.add(normalize(item));
        return java.util.Collections.unmodifiableSet(folded);
    }

    static String fold(String text) {
        if (text == null || text.isEmpty()) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return OpenCCFoldTable.fold(normalized);
    }

    static String foldCompact(String text) {
        return WHITESPACE.matcher(fold(text)).replaceAll(" ").trim();
    }

    static String normalize(String text) {
        return NON_WORD.matcher(fold(text)).replaceAll("");
    }

    static String normalizeArtistToken(String text) {
        String normalized = normalize(text);
        if (!normalized.isEmpty()) return normalized;
        String collapsed = WHITESPACE.matcher(fold(text)).replaceAll("");
        if (!collapsed.isEmpty() && SYMBOL_CHAR.matcher(collapsed).find()) {
            return SYMBOL_PREFIX + collapsed;
        }
        return "";
    }

    /** Strips only recognized benign edition markers (e.g. Explicit) for recall queries, keeping version tags intact. */
    static String cleanSearchTitle(String title) {
        String raw = title == null ? "" : title.trim();
        if (raw.isEmpty()) return "";
        Matcher brackets = MatchRules.BRACKET.matcher(raw);
        StringBuffer result = new StringBuffer();
        while (brackets.find()) {
            String annotation = fold(brackets.group(1)).trim();
            brackets.appendReplacement(result,
                    MatchRules.isBenignAnnotation(annotation) ? "" : Matcher.quoteReplacement(brackets.group()));
        }
        brackets.appendTail(result);
        String cleaned = WHITESPACE.matcher(result.toString()).replaceAll(" ").trim();
        return cleaned.isEmpty() ? raw : cleaned;
    }

    /** Strips bracketed alternate names inside artist credits for cleaner recall queries only. */
    static String cleanSearchArtist(String artist) {
        String raw = artist == null ? "" : artist.trim();
        if (raw.isEmpty()) return "";
        String[] segments = MatchRules.SLASH.split(raw);
        StringBuilder joined = new StringBuilder();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;
            String base = WHITESPACE.matcher(MatchRules.BRACKET.matcher(trimmed).replaceAll(" "))
                    .replaceAll(" ").trim();
            String token = base.isEmpty() ? trimmed : base;
            if (joined.length() > 0) joined.append(" / ");
            joined.append(token);
        }
        return joined.length() > 0 ? joined.toString() : raw;
    }

    static Set<String> versionTags(String value) {
        String text = fold(value);
        TreeSet<String> tags = new TreeSet<>();
        Matcher latin = MatchRules.LATIN_VERSION_TAG.matcher(text);
        while (latin.find()) tags.add(latin.group(1).replace(' ', '-'));
        for (String[] pair : FOLDED_CJK_VERSION_TAGS) {
            if (text.contains(pair[0])) tags.add(pair[1]);
        }
        Matcher brackets = MatchRules.BRACKET.matcher(text);
        while (brackets.find()) {
            String annotation = brackets.group(1).trim();
            if (annotation.endsWith(FOLDED_VARIANT_SUFFIX) && !MatchRules.isBenignAnnotation(annotation)) {
                String normalized = normalize(annotation);
                if (!normalized.isEmpty()) tags.add("ver:" + normalized);
            }
        }
        return tags;
    }

    static String versionKey(String value) {
        Set<String> tags = versionTags(value);
        if (tags.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String tag : tags) result.append(tag).append('|');
        return result.toString();
    }

    static final class TitleInfo {
        static final String LIVE_YEAR_CONFLICT = "<conflict>";

        final String mainTitle;
        final String baseTitleNoBrackets;
        final Set<String> versionTags;
        final String versionKey;
        final String liveYear;
        final boolean hasLiveTag;

        TitleInfo(String mainTitle, String baseTitleNoBrackets, Set<String> versionTags,
                  String versionKey, String liveYear) {
            this.mainTitle = mainTitle;
            this.baseTitleNoBrackets = baseTitleNoBrackets;
            this.versionTags = versionTags;
            this.versionKey = versionKey;
            this.liveYear = liveYear;
            this.hasLiveTag = versionTags.contains("live");
        }
    }

    static TitleInfo analyzeTitle(String title) {
        return analyzeInternal(title, true);
    }

    /**
     * Album normalization strips ONLY confirmed interchangeable release markers (benign annotations and
     * pure Live tags) and NEVER strips arbitrary CJK brackets such as volume/part qualifiers (e.g. 上/下).
     */
    static TitleInfo analyzeAlbum(String album) {
        return analyzeInternal(album, false);
    }

    private static TitleInfo analyzeInternal(String value, boolean allowCjkTranslationStrip) {
        String text = fold(value);
        Set<String> tags = java.util.Collections.unmodifiableSet(versionTags(value));
        StringBuilder keyBuilder = new StringBuilder();
        for (String tag : tags) keyBuilder.append(tag).append('|');
        String vKey = keyBuilder.toString();

        Matcher brackets = MatchRules.BRACKET.matcher(text);
        StringBuffer result = new StringBuffer();
        String liveYear = "";
        while (brackets.find()) {
            String annotation = brackets.group(1).trim();
            boolean benign = MatchRules.isBenignAnnotation(annotation);
            boolean variant = annotation.endsWith(FOLDED_VARIANT_SUFFIX) && !benign;
            boolean translation = allowCjkTranslationStrip && !variant
                    && CJK.matcher(annotation).find()
                    && versionTags(annotation).isEmpty()
                    && !MatchRules.NON_TRANSLATION_BRACKET.matcher(annotation).find();
            if (benign || translation) {
                brackets.appendReplacement(result, "");
                continue;
            }
            Matcher pureLive = MatchRules.PURE_LIVE_BRACKET.matcher(annotation);
            if (pureLive.matches()) {
                String year = pureLive.group(1) != null ? pureLive.group(1)
                        : (pureLive.group(2) != null ? pureLive.group(2) : "");
                if (!year.isEmpty()) {
                    if (liveYear.isEmpty()) liveYear = year;
                    else if (!liveYear.equals(year)) liveYear = TitleInfo.LIVE_YEAR_CONFLICT;
                }
                brackets.appendReplacement(result, "");
                continue;
            }
            brackets.appendReplacement(result, Matcher.quoteReplacement(brackets.group()));
        }
        brackets.appendTail(result);
        String noBrackets = normalize(MatchRules.BRACKET.matcher(text).replaceAll(" "));
        return new TitleInfo(normalize(result.toString()), noBrackets, tags, vKey, liveYear);
    }

    static boolean isLiveAlbum(String album) {
        if (album == null || album.trim().isEmpty() || isCompilationAlbum(album)) return false;
        return MatchRules.LIVE_ALBUM_MARKER.matcher(fold(album)).find();
    }

    static boolean knownAlbum(String album) {
        String value = normalize(album);
        return !value.isEmpty() && !FOLDED_UNKNOWN_ARTIST_TOKENS.contains(value);
    }

    static boolean sameAlbumTitle(String leftAlbum, String rightAlbum) {
        if (!knownAlbum(leftAlbum) || !knownAlbum(rightAlbum)) return false;
        TitleInfo left = analyzeAlbum(leftAlbum);
        TitleInfo right = analyzeAlbum(rightAlbum);
        if (left.mainTitle.isEmpty() || right.mainTitle.isEmpty()) return false;
        if (TitleInfo.LIVE_YEAR_CONFLICT.equals(left.liveYear) || TitleInfo.LIVE_YEAR_CONFLICT.equals(right.liveYear)) {
            return false;
        }
        return left.mainTitle.equals(right.mainTitle)
                && left.versionTags.equals(right.versionTags)
                && left.liveYear.equals(right.liveYear);
    }

    static boolean sameNonCompilationAlbum(String leftAlbum, String rightAlbum) {
        return !isCompilationAlbum(leftAlbum) && !isCompilationAlbum(rightAlbum)
                && sameAlbumTitle(leftAlbum, rightAlbum);
    }

    static String mainTitle(String title) {
        String text = fold(title);
        Matcher brackets = MatchRules.BRACKET.matcher(text);
        StringBuffer result = new StringBuffer();
        while (brackets.find()) {
            String annotation = brackets.group(1).trim();
            boolean benign = MatchRules.isBenignAnnotation(annotation);
            boolean variant = annotation.endsWith(FOLDED_VARIANT_SUFFIX) && !benign;
            boolean translation = !variant && CJK.matcher(annotation).find() && versionTags(annotation).isEmpty();
            brackets.appendReplacement(result,
                    (benign || translation) ? "" : Matcher.quoteReplacement(brackets.group()));
        }
        brackets.appendTail(result);
        return normalize(result.toString());
    }

    static boolean sameRecordingTitle(String left, String right) {
        String a = mainTitle(left), b = mainTitle(right);
        return !a.isEmpty() && a.equals(b) && versionTags(left).equals(versionTags(right));
    }

    static boolean matchesDisplayCredit(String displayArtist, List<String> memberNames) {
        String foldedDisplay = foldCompact(displayArtist);
        if (foldedDisplay.isEmpty() || memberNames == null || memberNames.isEmpty()) return false;
        int pos = 0;
        for (int i = 0; i < memberNames.size(); i++) {
            String member = foldCompact(memberNames.get(i));
            if (member.isEmpty()) return false;
            if (i > 0) {
                Matcher sep = MatchRules.ARTIST_SEPARATOR.matcher(foldedDisplay);
                sep.region(pos, foldedDisplay.length());
                if (!sep.lookingAt() || sep.end() == pos) return false;
                pos = sep.end();
            }
            if (!foldedDisplay.startsWith(member, pos)) return false;
            pos += member.length();
        }
        return pos == foldedDisplay.length();
    }

    static Set<String> memberAtomNames(String memberName) {
        Set<String> names = new LinkedHashSet<>();
        if (memberName == null) return names;
        String folded = fold(memberName);
        Matcher brackets = MatchRules.BRACKET.matcher(folded);
        StringBuffer base = new StringBuffer();
        while (brackets.find()) {
            String alias = normalizeArtistToken(brackets.group(1));
            if (!alias.isEmpty()) names.add(alias);
            brackets.appendReplacement(base, "");
        }
        brackets.appendTail(base);
        String name = normalizeArtistToken(base.toString());
        if (!name.isEmpty()) names.add(name);
        return names;
    }

    static Set<String> artistNames(String credit) {
        Set<String> names = new LinkedHashSet<>();
        if (credit == null) return names;
        for (String part : MatchRules.ARTIST_SEPARATOR.split(fold(credit))) {
            names.addAll(memberAtomNames(part));
        }
        return names;
    }

    static List<Set<String>> artistParts(String credit) {
        List<Set<String>> parts = new ArrayList<>();
        if (credit != null) {
            for (String part : MatchRules.ARTIST_SEPARATOR.split(fold(credit))) {
                Set<String> names = artistNames(part);
                if (!names.isEmpty()) parts.add(names);
            }
        }
        return parts;
    }

    static List<String> artistRawParts(String credit) {
        List<String> parts = new ArrayList<>();
        if (credit != null) {
            for (String part : MatchRules.ARTIST_SEPARATOR.split(credit)) {
                String trimmed = part.trim();
                if (!artistNames(trimmed).isEmpty()) parts.add(trimmed);
            }
        }
        return parts;
    }

    static boolean knownArtist(String credit) {
        String value = normalize(credit);
        if (!value.isEmpty() && FOLDED_UNKNOWN_ARTIST_TOKENS.contains(value)) {
            return false;
        }
        return !artistParts(credit).isEmpty();
    }

    static boolean isCompilationAlbum(String album) {
        String normalized = normalize(album);
        if (normalized.isEmpty()) return false;
        for (String keyword : FOLDED_COMPILATION_KEYWORDS) {
            if (normalized.contains(keyword)) return true;
        }
        return false;
    }

    static String keyword(String title, String artist) {
        String names = MatchRules.SLASH.matcher(artist == null ? "" : artist.trim()).replaceAll(" ");
        String name = title == null ? "" : title.trim();
        return names.isEmpty() ? name : name + " " + names;
    }
}
