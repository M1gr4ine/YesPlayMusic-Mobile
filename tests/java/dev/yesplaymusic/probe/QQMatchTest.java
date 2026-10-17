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

        // UNM select.js port: duration decides, the artist credit is ignored.
        that(QQMatch.score("First Love", "宇多田ヒカル", 257000, "First Love", "宇多田光", 257000) < 0,
                "strict match needs the artist");
        that(QQMatch.unmAccepts("First Love", 257000, "First Love", 257400, false), "UNM accepts by duration");
        that(QQMatch.unmAccepts("夜に駆ける", 261000, "夜に駆ける (向夜晚奔去)", 261000, true), "UNM accepts a subtitle");
        that(!QQMatch.unmAccepts("First Love", 257000, "First Love", 263000, false), "UNM rejects a 5 s gap");
        that(!QQMatch.unmAccepts("First Love", 257000, "First Love (Live)", 257000, false), "UNM keeps the version guard");
        that(QQMatch.unmAccepts("Song", 200000, "Other Name", 201000, false), "artist query trusts duration alone");
        that(!QQMatch.unmAccepts("Song", 200000, "Other Name", 201000, true), "title-only query needs a related title");
        that(!QQMatch.unmAccepts("Song", 0, "Other Name", 201000, false), "unknown duration needs a related title");
        that(QQMatch.keyword("Song", "A / B").equals("Song A B"), "keyword joins every artist");
        that(QQMatch.keyword("Song", "").equals("Song"), "keyword without artist");
    }
}
