package dev.yesplaymusic.probe;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Data-driven rule tables and thresholds for cross-source track matching.
 * Contains only general recording/edition/metadata patterns — never specific artists, songs, or albums.
 */
final class MatchRules {
    private MatchRules() {}

    static final long PLAYBACK_MAX_DURATION_GAP_MS = 5000L;
    static final long WRITE_RULE_B_MAX_DURATION_GAP_MS = 2000L;
    static final int MAX_PLAYBACK_CANDIDATES = 3;
    static final int MAX_ALIAS_SEARCH_QUERIES = 2;

    static final String[] ALIAS_FIELDS = {
            "alias", "alia", "tns", "trans", "transNames"
    };

    static final Pattern LATIN_VERSION_TAG = Pattern.compile(
            "\\b(live|remix|instrumental|acoustic|sped[ -]up|cover|flip|mix|edit|demo|dj|"
                    + "slowed|nightcore|bootleg|mashup|karaoke|unplugged|acapella|a[ -]cappella|"
                    + "cantonese|mandarin|japanese|korean|english)\\b");

    /** Pairs of (folded CJK keyword, canonical version tag). */
    static final String[][] CJK_VERSION_TAGS = {
            {"伴奏", "伴奏"},
            {"翻唱", "翻唱"},
            {"纯音乐", "纯音乐"},
            {"现场", "live"},
            {"演唱会", "live"},
            {"清唱", "清唱"},
            {"小样", "小样"},
            {"混音", "混音"},
            {"加速", "加速"},
            {"降调", "降调"},
            {"电音", "电音"},
            {"粤语", "cantonese"},
            {"广东话", "cantonese"},
            {"白话", "白话"},
            {"国语", "mandarin"},
            {"普通话", "mandarin"},
            {"闽南语", "闽南语"},
            {"台语", "台语"},
            {"日语", "japanese"},
            {"日文", "japanese"},
            {"英语", "english"},
            {"英文", "english"},
            {"韩语", "korean"},
            {"韩文", "korean"},
            {"女版", "女版"},
            {"男版", "男版"},
            {"女声", "女声"},
            {"男声", "男声"},
            {"女生版", "女生版"},
            {"男生版", "男生版"},
            {"烟嗓", "烟嗓"},
            {"片段", "片段"},
            {"高潮版", "高潮版"},
            {"铃声", "铃声"},
            {"戏腔", "戏腔"},
    };

    static final String VARIANT_SUFFIX = "版";

    static final Pattern BENIGN_ANNOTATION = Pattern.compile(
            "(?i)^(?:explicit|clean|album version|single version|"
                    + "remaster(?:ed)?(?: (?:\\d{4}|version))?|\\d{4} remaster(?:ed)?|"
                    + "原版|完整版|专辑版|正式版)$");

    /** Brackets containing years, tour stops, or volume/part markers are never stripped as CJK song translations. */
    static final Pattern NON_TRANSLATION_BRACKET = Pattern.compile(
            "(?i)(?:\\d|\\b(?:part|pt|vol|disc|cd|side|track|no|ep|ver|version|edition|tour|concert|live|stop|day|night)\\b|"
                    + "站|巡演|场|篇|卷|辑|碟|集|^[上下中前后]$)");

    /** Pure live/concert bracket with optional 4-digit year, e.g. "Live", "2007 Live", "Live - 1991", "2007 现场". */
    static final Pattern PURE_LIVE_BRACKET = Pattern.compile(
            "^(?:((?:19|20)\\d{2})\\s*年?\\s*(?:live|现场|演唱会)|"
                    + "(?:live|现场|演唱会)(?:\\s*[-/]?\\s*((?:19|20)\\d{2})\\s*年?)?)$");

    /** General markers identifying a live/concert album title (evaluated on folded text). */
    static final Pattern LIVE_ALBUM_MARKER = Pattern.compile(
            "\\b(live|concert|tour|unplugged|stage)\\b|现场|演唱会");

    static final String[] COMPILATION_KEYWORDS = {
            "greatesthits", "bestof", "collection", "compilation", "精选", "合辑"
    };

    static final Set<String> UNKNOWN_ARTIST_TOKENS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "unknown", "unknownartist", "未知", "未知歌手", "未知艺术家", "variousartists", "群星", "null"
    )));

    static final Pattern BRACKET = Pattern.compile("[（(\\[【]([^）)\\]】]+)[）)\\]】]");
    /**
     * Separates multiple artists while preserving tight Latin slashes inside single band names.
     * Explicit spaced " / " and CJK-to-CJK "/" still separate artists.
     */
    static final Pattern ARTIST_SEPARATOR = Pattern.compile(
            "(?i)(?:\\s+/\\s+|(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}])/(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}])|"
                    + "\\s*(?:[、,&，;；]|\\b(?:feat\\.?|ft\\.?|featuring|with|vs\\.?)\\b|\\s+x\\s+)\\s*)");
    /** Used solely for detecting structural artist-grouping conflicts (e.g. single "A/B" vs split "A / B"). */
    static final Pattern GROUP_SUB_SEPARATOR = Pattern.compile(
            "(?i)\\s*(?:[/、,&，;；]|\\b(?:feat\\.?|ft\\.?|featuring|with|vs\\.?)\\b|\\s+x\\s+)\\s*");
    static final Pattern SLASH = Pattern.compile("\\s+/\\s+");
    static final Pattern COLLABORATION = Pattern.compile("\\b(feat\\.?|ft\\.?|featuring|with|vs\\.?)\\b|\\s+x\\s+");
    static final Pattern EXPLICIT_JOIN_SEPARATOR = Pattern.compile("\\s+/\\s+|[\\\\、;；\\r\\n]");

    static boolean isBenignAnnotation(String foldedAnnotation) {
        String trimmed = foldedAnnotation == null ? "" : foldedAnnotation.trim();
        if (trimmed.isEmpty()) return false;
        String[] parts = trimmed.split("\\s*\\|\\s*");
        for (String part : parts) {
            if (!BENIGN_ANNOTATION.matcher(part.trim()).matches()) return false;
        }
        return true;
    }
}
