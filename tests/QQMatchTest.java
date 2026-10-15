import dev.yesplaymusic.probe.QQMatch;
public class QQMatchTest {
    public static void main(String[] args) {
        ok(QQMatch.score("晴天","周杰伦",269000,"晴天","周杰伦",269000)>140,"exact version");
        ok(QQMatch.score("晴天","周杰伦",269000,"晴天 (Live)","周杰伦",269000)<0,"reject live version");
        ok(QQMatch.score("晴天","周杰伦",269000,"晴天","其他歌手",269000)<0,"reject cover artist");
        ok(QQMatch.score("晴天","周杰伦",269000,"晴天","周杰伦",190000)<0,"reject duration mismatch");
        ok(QQMatch.score("夜 曲","周杰伦",226000,"夜曲","周杰伦",225000)>140,"normalize whitespace");
        ok(QQMatch.score("Song","Artist A / Artist B",210000,"Song","Artist B / Artist A",211000)>140,"collaboration order");
        ok(QQMatch.score("Song","Artist",210000,"Song (Instrumental)","Artist",210000)<0,"reject instrumental");
        ok(QQMatch.score("\u82B1", "\u85E4\u4E95\u98A8", 246500, "\u82B1", "\u85E4\u4E95\u98CE", 246000)>140, "match flower across traditional/simplified artist name");
        ok(QQMatch.score("\u5E30\u308D\u3046", "\u85E4\u4E95\u98A8", 284459, "\u5E30\u308D\u3046 (\u4E58\u98CE\u5F52\u53BB)", "\u85E4\u4E95\u98CE", 284000)>140, "match Japanese title with QQ translation subtitle");
        ok(QQMatch.normalize("\u98A8").equals(QQMatch.normalize("\u98CE")), "normalize traditional/simplified character");
        ok(QQMatch.score("\u82B1", "\u85E4\u4E95\u98A8", 246500, "\u82B1", "Cover Singer", 246000)<0, "reject cover with cross-script artist match");
        ok(QQMatch.score("\u82B1", "\u85E4\u4E95\u98A8", 246500, "\u82B1 (Live)", "\u85E4\u4E95\u98CE", 246000)<0, "reject live with cross-script artist match");
        ok(QQMatch.score("\u82B1", "\u85E4\u4E95\u98A8", 246500, "\u82B1", "\u85E4\u4E95\u98CE", 260000)<0, "reject duration mismatch with cross-script artist match");
        // Japanese kanji forms and QQ's simplified artist credits.
        ok(QQMatch.score("Lemon", "米津玄師", 256000, "Lemon", "米津玄师", 256000)>140, "fold 師 to 师 in artist");
        ok(QQMatch.score("千本桜", "初音ミク", 244000, "千本樱", "初音ミク", 244000)>140, "fold 桜 to 樱 in title");
        ok(QQMatch.score("Lemon", "米津玄師", 256000, "Lemon (现场)", "米津玄師", 256000)<0, "treat 现场 as a live version");
        ok(QQMatch.score("Alive", "Artist", 200000, "Alive", "Artist", 200000)>140, "live inside a word is not a version tag");
        ok(QQMatch.score("Alive", "Artist", 200000, "Alive (Live)", "Artist", 200000)<0, "reject live of a title containing live");
        ok(QQMatch.FOLD_FROM.length()==QQMatch.FOLD_TO.length(), "fold table aligned");
        // UNM select.js port: duration decides, artist credit ignored.
        String utada = "宇多田ヒカル", utadaQQ = "宇多田光";
        ok(QQMatch.score("First Love", utada, 257000, "First Love", utadaQQ, 257000)<0, "strict match still needs the artist");
        ok(QQMatch.unmAccepts("First Love", 257000, "First Love", 257400, false), "UNM accepts QQ's Chinese artist credit by duration");
        ok(QQMatch.unmAccepts("夜に駆ける", 261000, "夜に駆ける (向夜晚奔去)", 261000, true), "UNM accepts QQ translation subtitle");
        ok(!QQMatch.unmAccepts("First Love", 257000, "First Love", 263000, false), "UNM rejects 5 s duration gap");
        ok(!QQMatch.unmAccepts("First Love", 257000, "First Love (Live)", 257000, false), "UNM keeps the version guard");
        ok(!QQMatch.unmAccepts("Song", 200000, "Song (Instrumental)", 200000, false), "UNM rejects same-length instrumental");
        ok(QQMatch.unmAccepts("Song", 200000, "Other Name", 201000, false), "UNM artist query trusts duration alone");
        ok(!QQMatch.unmAccepts("Song", 200000, "Other Name", 201000, true), "title-only query also needs a related title");
        ok(!QQMatch.unmAccepts("Song", 0, "Other Name", 201000, false), "unknown duration needs a related title");
        ok(QQMatch.keyword("Song", "A / B").equals("Song A B"), "UNM keyword joins every artist");
        ok(QQMatch.keyword("Song", "").equals("Song"), "keyword without artist");
        System.out.println("PASS 30 QQ source matching cases");
    }
    private static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
