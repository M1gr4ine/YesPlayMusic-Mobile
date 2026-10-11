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
        System.out.println("PASS 7 QQ source matching cases");
    }
    private static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
