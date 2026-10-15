import dev.yesplaymusic.probe.LrcLines;
public class LrcLinesTest {
    public static void main(String[] args) {
        LrcLines lines = LrcLines.parse("[ti:Song]\r\n[00:00.00] 作词 : A\n[00:12.5]First line\n[00:15.25][01:02.123]Chorus\n[00:20.00]\n[00:30.000]Last");
        ok(lines.text(lines.indexAt(0)).equals("作词 : A"), "credit line at start");
        ok(lines.indexAt(12_499) == 0 && lines.text(lines.indexAt(12_500)).equals("First line"), "one-digit fraction is tenths");
        ok(lines.text(lines.indexAt(15_250)).equals("Chorus"), "two-digit fraction is hundredths");
        ok(lines.text(lines.indexAt(62_123)).equals("Chorus"), "repeated stamps share a line");
        ok(lines.text(lines.indexAt(29_999)).equals("Chorus"), "empty timed line is skipped");
        ok(lines.nextTime(lines.indexAt(15_250)) == 30_000L, "next line start");
        ok(lines.nextTime(lines.indexAt(70_000)) == -1L, "last line has no next");
        LrcLines late = LrcLines.parse("[offset:500]\n[00:01.00]Late");
        ok(late.indexAt(1_499) == -1 && late.indexAt(1_500) == 0, "offset shifts lines");
        ok(LrcLines.parse("plain text only").isEmpty() && LrcLines.parse(null).isEmpty(), "untimed text is empty");
        ok(LrcLines.EMPTY.text(LrcLines.EMPTY.indexAt(1000)) == null, "empty has no text");
        System.out.println("PASS 10 LRC session lyric cases");
    }
    private static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
