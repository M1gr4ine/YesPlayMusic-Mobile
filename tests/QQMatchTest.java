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
        System.out.println("PASS 13 QQ source matching cases");
    }
    private static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
