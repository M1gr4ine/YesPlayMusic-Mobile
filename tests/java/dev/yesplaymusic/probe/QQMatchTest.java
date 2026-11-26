package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

final class QQMatchTest {
    private QQMatchTest() {}

    static void run() {
        that(QQMatch.score("晴天", "周杰伦", 269000, "晴天", "周杰伦", 269000) > 140, "exact version matches");
        that(QQMatch.score("晴天", "周杰伦", 269000, "晴天 (Live)", "周杰伦", 269000) < 0, "live version is rejected");
        that(QQMatch.score("Song", "Artist", 210000, "Song (Instrumental)", "Artist", 210000) < 0, "instrumental is rejected");
        that(QQMatch.score("晴天", "周杰伦", 269000, "晴天", "其他歌手", 269000) < 0, "cover artist is rejected");
        that(QQMatch.score("晴天", "周杰伦", 269000, "晴天", "周杰伦", 190000) < 0, "duration mismatch is rejected");
        that(QQMatch.score("夜 曲", "周杰伦", 226000, "夜曲", "周杰伦", 225000) > 140, "whitespace is ignored");
        that(QQMatch.score("Song", "Artist A / Artist B", 210000, "Song", "Artist B / Artist A", 211000) > 140,
                "collaboration order is ignored");

        // NetEase keeps traditional / shinjitai forms that QQ lists in simplified Chinese.
        that(QQMatch.score("花", "藤井風", 246500, "花", "藤井风", 246000) > 140, "artist 風 folds to 风");
        that(QQMatch.score("Lemon", "米津玄師", 256000, "Lemon", "米津玄师", 256000) > 140, "artist 師 folds to 师");
        that(QQMatch.score("千本桜", "初音ミク", 244000, "千本樱", "初音ミク", 244000) > 140, "title 桜 folds to 樱");
        that(QQMatch.score("帰ろう", "藤井風", 284459, "帰ろう (乘风归去)", "藤井风", 284000) > 140,
                "QQ translation subtitle still matches");
        that(QQMatch.score("Lemon", "米津玄師", 256000, "Lemon (现场)", "米津玄師", 256000) < 0, "现场 counts as live");
        that(QQMatch.score("Alive", "Artist", 200000, "Alive", "Artist", 200000) > 140, "live inside a word is not a tag");
        that(QQMatch.score("Alive", "Artist", 200000, "Alive (Live)", "Artist", 200000) < 0, "live tag after such a word");

        that(QQMatch.score("花", "Artist", 200000, "花火", "Artist", 200000) < 0, "short CJK title is not a substring");
        that(QQMatch.score("FML", "Artist", 200000, "My FML Life", "Artist", 200000) < 0, "short Latin title is not a substring");
        that(QQMatch.score("FML", "Artist", 200000, "FML (Explicit)", "Artist", 200000) >= 140, "explicit metadata preserves identity");
        that(QQMatch.score("Song", "Ye", 200000, "Song", "Kanye", 200000) < 0, "artist substrings do not match");
        that(QQMatch.score("FML", "Ye", 236000, "FML (Explicit)", "Ye (侃爷)", 236120) >= 140, "bracketed aliases identify one artist");
        that(QQMatch.score("Song", "Artist", 200000, "Song (現場)", "Artist", 200000) < 0, "traditional live tag folds first");
        for (String tag : new String[]{"Cover", "Flip", "Mix", "Edit", "Demo", "DJ", "Slowed", "Nightcore", "Bootleg", "Mashup", "Karaoke", "Unplugged", "Acapella", "翻唱", "伴奏", "純音樂", "演唱會", "清唱", "小樣", "混音", "加速", "降調"}) {
            that(QQMatch.score("Song", "Artist", 200000, "Song (" + tag + ")", "Artist", 200000) < 0, "version rejected: " + tag);
        }
        that(QQMatch.score("Song", "Artist", "Album", 200000, "Song", "Artist", "Album", 200000) == 200, "matching album improves confidence");

        that(QQMatch.score("Song", "", 200000, "Song", "Artist", 200000) < 0, "missing artist cannot identify a recording");
        that(QQMatch.score("Song", "Unknown Artist", 200000, "Song", "Unknown Artist", 200000) < 0, "unknown placeholder cannot identify a recording");
        that(QQMatch.score("Song", "A / The Weeknd", 200000, "Song", "B / The Weeknd", 200000) < 0, "a shared guest cannot hide different leads");
        that(QQMatch.score("Song", "AB / C", 200000, "Song", "A / BC", 200000) < 0, "joined credit strings cannot collide");
        that(QQMatch.score("Song", "Artist", "Album One", 200000, "Song", "Artist", "Album One", 200000)
                > QQMatch.score("Song", "Artist", "Album One", 200000, "Song", "Artist", "Album Two", 200000), "album evidence distinguishes candidates");
        that(QQMatch.score("Song", "Artist", 200000, "Song (Remastered Version)", "Artist", 200000) >= 140, "remastered version is benign");
        that(QQMatch.score("Song", "Artist", 200000, "Song (Remastered 2011)", "Artist", 200000) >= 140, "remastered year is benign");
        that(QQMatch.score("Bound 2", "Ye", 229000, "Bound 2 (Album Version|Explicit)", "Ye (侃爷)", 229000) >= 140, "pipe-separated benign edition markers match");
        that(QQMatch.score("Kill Bill", "SZA", 153000, "Kill Bill (Live)", "SZA", 153000) < 0, "live recording cannot replace the studio song");

        // Language and vocal-variant guards (e.g. 陈奕迅《最后派对》/《粤语残片》 Cantonese vs dialect/Mandarin/female/DJ variants).
        that(QQMatch.score("最后派对", "陈奕迅", "Stranger Under My Skin", 311600, "最后派对", "陈奕迅", "Stranger Under My Skin", 311000) > 140, "exact Cantonese studio version matches");
        for (String variant : new String[]{"女版", "男版", "白话版", "国语版", "普通话版", "粤语版", "片段版", "民谣版", "DJ Solstice晦朔版", "x", "白话", "國語", "粵語"}) {
            that(QQMatch.score("最后派对", "陈奕迅", 311600, "最后派对 (" + variant + ")", "陈奕迅", 312000) < 0,
                    "variant rejected for 最后派对: " + variant);
        }
        that(QQMatch.score("粤语残片", "陈奕迅", 277000, "粤语残片", "陈奕迅", 277000) > 140, "title containing 粤语 still matches exact song");
        that(QQMatch.score("粤语残片", "陈奕迅", 277000, "粤语残片 (白话版)", "陈奕迅", 277000) < 0, "粤语残片 rejects 白话版");
        that(QQMatch.score("粤语残片", "陈奕迅", 277000, "粤语残片 (国语版)", "陈奕迅", 277000) < 0, "粤语残片 rejects 国语版");
        that(QQMatch.score("富士山下", "陈奕迅", 258900, "富士山下 (国语)", "陈奕迅", 259000) < 0, "language tag mismatch is rejected");

        that(QQMatch.keyword("Song", "A / B").equals("Song A B"), "keyword joins every artist");
        that(QQMatch.keyword("Song", "").equals("Song"), "keyword without artist");

        // Symbol-only artist names (e.g. ¥$ on CARNIVAL) remain part of the credit and never collapse.
        that(QQMatch.knownArtist("¥$"), "symbol-only artist credit is recognized");
        that(!QQMatch.knownArtist("---") && !QQMatch.knownArtist("..."), "pure punctuation is not a known artist");
        that(QQMatch.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264000,
                "CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264000) > 140, "exact symbol-only member matches");
        that(QQMatch.score("CARNIVAL", "￥$ / Kanye West / Ty Dolla $ign", 264000,
                "CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264000) > 140, "NFKC folds fullwidth symbol artist");
        that(QQMatch.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264000,
                "CARNIVAL", "Kanye West / Ty Dolla $ign", 264000) < 0, "dropping symbol-only member is rejected");
        that(QQMatch.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264000,
                "CARNIVAL", "$ / Kanye West / Ty Dolla $ign", 264000) < 0, "different symbol artist is rejected");
        that(QQMatch.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264000,
                "CARNIVAL (Acapella)", "¥$ / Kanye West / Ty Dolla $ign", 264000) < 0, "acapella version is rejected");
        that(QQMatch.score("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264000,
                "CARNIVAL (HOOLIGANS VERSION)", "¥$ / Kanye West / Ty Dolla $ign", 264000) < 0,
                "HOOLIGANS VERSION is not treated as benign");
        that(QQMatch.score("Famous", "Ye.", 196000, "Famous (Explicit)", "Ye (侃爷)", 196000) >= 140,
                "trailing period in Ye. normalizes cleanly");

        // Recall query cleanup strips only benign edition annotations and bracketed artist aliases.
        that("Famous".equals(QQMatch.cleanSearchTitle("Famous (Explicit)")), "cleanSearchTitle strips Explicit");
        that("Bound 2".equals(QQMatch.cleanSearchTitle("Bound 2 (Album Version|Explicit)")),
                "cleanSearchTitle strips pipe-separated benign markers");
        that("Famous (Live)".equals(QQMatch.cleanSearchTitle("Famous (Live)")),
                "cleanSearchTitle preserves Live tag");
        that("CARNIVAL (HOOLIGANS VERSION)".equals(QQMatch.cleanSearchTitle("CARNIVAL (HOOLIGANS VERSION)")),
                "cleanSearchTitle preserves non-benign version tags");
        that("Ye / Rihanna".equals(QQMatch.cleanSearchArtist("Ye (侃爷) / Rihanna")),
                "cleanSearchArtist strips bracketed alias for recall");
    }
}
