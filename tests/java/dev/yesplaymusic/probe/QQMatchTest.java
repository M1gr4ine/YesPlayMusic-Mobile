package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

/** Synthetic rule-named unit tests for text normalization, version tags, and legacy QQMatch scoring. */
final class QQMatchTest {
    private QQMatchTest() {}

    static void run() {
        that(QQMatch.score("晨光", "歌手甲", 269000, "晨光", "歌手甲", 269000) > 140, "exact recording matches");
        that(QQMatch.score("晨光", "歌手甲", 269000, "晨光 (Live)", "歌手甲", 269000) < 0, "live version is rejected");
        that(QQMatch.score("Track One", "Artist One", 210000, "Track One (Instrumental)", "Artist One", 210000) < 0,
                "instrumental is rejected");
        that(QQMatch.score("晨光", "歌手甲", 269000, "晨光", "翻唱歌手", 269000) < 0, "cover artist is rejected");
        that(QQMatch.score("晨光", "歌手甲", 269000, "晨光", "歌手甲", 190000) < 0, "duration mismatch is rejected");
        that(QQMatch.score("夜 曲", "歌手甲", 226000, "夜曲", "歌手甲", 225000) > 140, "whitespace is ignored");
        that(QQMatch.score("Track One", "Artist A / Artist B", 210000, "Track One", "Artist B / Artist A", 211000) > 140,
                "collaboration order is ignored");

        // Traditional Chinese and Japanese shinjitai forms fold to Simplified Chinese via OpenCC.
        that(QQMatch.score("花", "歌手風", 246500, "花", "歌手风", 246000) > 140, "artist 風 folds to 风");
        that(QQMatch.score("Track", "作者師", 256000, "Track", "作者师", 256000) > 140, "artist 師 folds to 师");
        that(QQMatch.score("千本桜", "カタカナ歌手", 244000, "千本樱", "カタカナ歌手", 244000) > 140,
                "title shinjitai 桜 folds to 樱");
        that(QQMatch.score("帰ろう", "歌手風", 284459, "帰ろう (中文译名)", "歌手风", 284000) > 140,
                "CJK translation subtitle still matches");
        that(QQMatch.score("Track", "作者師", 256000, "Track (现场)", "作者師", 256000) < 0, "现场 counts as live");
        that(QQMatch.score("Alive", "Artist One", 200000, "Alive", "Artist One", 200000) > 140,
                "live inside a word is not a version tag");
        that(QQMatch.score("Alive", "Artist One", 200000, "Alive (Live)", "Artist One", 200000) < 0,
                "live tag after such a word is still detected");

        that(QQMatch.score("花", "Artist One", 200000, "花火", "Artist One", 200000) < 0,
                "short CJK title is not matched by substring");
        that(QQMatch.score("ABC", "Artist One", 200000, "My ABC Life", "Artist One", 200000) < 0,
                "short Latin title is not matched by substring");
        that(QQMatch.score("ABC", "Artist One", 200000, "ABC (Explicit)", "Artist One", 200000) >= 140,
                "explicit annotation preserves title identity");
        that(QQMatch.score("Track One", "Short", 200000, "Track One", "ShortLong", 200000) < 0,
                "artist substrings do not match");
        that(QQMatch.score("ABC", "Short", 236000, "ABC (Explicit)", "Short (别名)", 236120) >= 140,
                "bracketed alternate name identifies one artist");
        that(QQMatch.score("Track One", "Artist One", 200000, "Track One (現場)", "Artist One", 200000) < 0,
                "traditional live tag folds before matching");
        for (String tag : new String[]{"Cover", "Flip", "Mix", "Edit", "Demo", "DJ", "Slowed", "Nightcore",
                "Bootleg", "Mashup", "Karaoke", "Unplugged", "Acapella", "翻唱", "伴奏", "純音樂",
                "演唱會", "清唱", "小樣", "混音", "加速", "降調"}) {
            that(QQMatch.score("Track One", "Artist One", 200000, "Track One (" + tag + ")", "Artist One", 200000) < 0,
                    "version rejected: " + tag);
        }
        that(QQMatch.score("Track One", "Artist One", "Studio Album", 200000,
                "Track One", "Artist One", "Studio Album", 200000) == 200,
                "matching album improves confidence");

        that(QQMatch.score("Track One", "", 200000, "Track One", "Artist One", 200000) < 0,
                "missing artist cannot identify a recording");
        that(QQMatch.score("Track One", "Unknown Artist", 200000, "Track One", "Unknown Artist", 200000) < 0,
                "unknown placeholder cannot identify a recording");
        that(QQMatch.score("Track One", "Lead A / Guest", 200000, "Track One", "Lead B / Guest", 200000) < 0,
                "shared guest cannot hide different leads");
        that(QQMatch.score("Track One", "AB / C", 200000, "Track One", "A / BC", 200000) < 0,
                "joined credit strings cannot collide across boundaries");
        that(QQMatch.score("Track One", "Artist One", "Album One", 200000, "Track One", "Artist One", "Album One", 200000)
                > QQMatch.score("Track One", "Artist One", "Album One", 200000, "Track One", "Artist One", "Album Two", 200000),
                "album evidence distinguishes candidates");
        that(QQMatch.score("Track One", "Artist One", 200000, "Track One (Remastered Version)", "Artist One", 200000) >= 140,
                "remastered version marker is benign");
        that(QQMatch.score("Track One", "Artist One", 200000, "Track One (Remastered 2011)", "Artist One", 200000) >= 140,
                "remastered year marker is benign");
        that(QQMatch.score("Track Two", "Short", 229000, "Track Two (Album Version|Explicit)", "Short (别名)", 229000) >= 140,
                "pipe-separated benign edition markers match");
        that(QQMatch.score("Track Three", "Artist One", 153000, "Track Three (Live)", "Artist One", 153000) < 0,
                "live recording cannot replace studio track");

        // Language and vocal-variant guards; title body containing a language word still matches its exact studio track.
        that(QQMatch.score("午夜场", "歌手甲", "Studio Album", 311600, "午夜场", "歌手甲", "Studio Album", 311000) > 140,
                "exact studio version matches");
        for (String variant : new String[]{"女版", "男版", "白话版", "国语版", "普通话版", "粤语版", "片段版",
                "民谣版", "DJ 混音版", "x", "白话", "國語", "粵語"}) {
            that(QQMatch.score("午夜场", "歌手甲", 311600, "午夜场 (" + variant + ")", "歌手甲", 312000) < 0,
                    "variant rejected: " + variant);
        }
        that(QQMatch.score("粤语故事", "歌手甲", 277000, "粤语故事", "歌手甲", 277000) > 140,
                "title body containing 粤语 still matches exact song");
        that(QQMatch.score("粤语故事", "歌手甲", 277000, "粤语故事 (白话版)", "歌手甲", 277000) < 0,
                "title containing 粤语 still rejects 白话版");
        that(QQMatch.score("粤语故事", "歌手甲", 277000, "粤语故事 (国语版)", "歌手甲", 277000) < 0,
                "title containing 粤语 still rejects 国语版");
        that(QQMatch.score("冬日歌", "歌手甲", 258900, "冬日歌 (国语)", "歌手甲", 259000) < 0,
                "language tag mismatch is rejected");

        that(QQMatch.keyword("Track One", "A / B").equals("Track One A B"), "keyword joins every artist");
        that(QQMatch.keyword("Track One", "").equals("Track One"), "keyword without artist");

        // Symbol-only artist names remain part of the credit and never collapse.
        that(QQMatch.knownArtist("¥$"), "symbol-only artist credit is recognized");
        that(!QQMatch.knownArtist("---") && !QQMatch.knownArtist("..."), "pure punctuation is not a known artist");
        that(QQMatch.score("Symbol Track", "¥$ / Artist Lead / Artist Guest", 264000,
                "Symbol Track", "¥$ / Artist Lead / Artist Guest", 264000) > 140,
                "exact symbol-only member matches");
        that(QQMatch.score("Symbol Track", "￥$ / Artist Lead / Artist Guest", 264000,
                "Symbol Track", "¥$ / Artist Lead / Artist Guest", 264000) > 140,
                "NFKC folds fullwidth symbol artist");
        that(QQMatch.score("Symbol Track", "¥$ / Artist Lead / Artist Guest", 264000,
                "Symbol Track", "Artist Lead / Artist Guest", 264000) < 0,
                "dropping symbol-only member is rejected");
        that(QQMatch.score("Symbol Track", "¥$ / Artist Lead / Artist Guest", 264000,
                "Symbol Track", "$ / Artist Lead / Artist Guest", 264000) < 0,
                "different symbol artist is rejected");
        that(QQMatch.score("Symbol Track", "¥$ / Artist Lead / Artist Guest", 264000,
                "Symbol Track (Acapella)", "¥$ / Artist Lead / Artist Guest", 264000) < 0,
                "acapella version is rejected");
        that(QQMatch.score("Symbol Track", "¥$ / Artist Lead / Artist Guest", 264000,
                "Symbol Track (CUSTOM VERSION)", "¥$ / Artist Lead / Artist Guest", 264000) < 0,
                "unknown custom version bracket is not treated as benign");
        that(QQMatch.score("Track Four", "Short.", 196000, "Track Four (Explicit)", "Short (别名)", 196000) >= 140,
                "trailing punctuation in artist name normalizes cleanly");

        // Recall query cleanup strips only benign edition annotations and bracketed artist aliases.
        that("Track Four".equals(QQMatch.cleanSearchTitle("Track Four (Explicit)")),
                "cleanSearchTitle strips Explicit");
        that("Track Two".equals(QQMatch.cleanSearchTitle("Track Two (Album Version|Explicit)")),
                "cleanSearchTitle strips pipe-separated benign markers");
        that("Track Four (Live)".equals(QQMatch.cleanSearchTitle("Track Four (Live)")),
                "cleanSearchTitle preserves Live tag");
        that("Symbol Track (CUSTOM VERSION)".equals(QQMatch.cleanSearchTitle("Symbol Track (CUSTOM VERSION)")),
                "cleanSearchTitle preserves non-benign version tags");
        that("Short / Guest".equals(QQMatch.cleanSearchArtist("Short (别名) / Guest")),
                "cleanSearchArtist strips bracketed alias for recall");
    }
}
