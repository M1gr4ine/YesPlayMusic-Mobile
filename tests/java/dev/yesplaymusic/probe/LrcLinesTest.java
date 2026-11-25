package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

final class LrcLinesTest {
    private LrcLinesTest() {}

    static void run() {
        LrcLines lines = LrcLines.parse("[ti:Song]\r\n[00:00.00] 作词 : A\n[00:12.5]First line\n"
                + "[00:15.25][01:02.123]Chorus\n[00:20.00]\n[00:30.000]Last");
        that("作词 : A".equals(lines.text(lines.indexAt(0))), "credit line at start");
        that(lines.indexAt(12_499) == 0 && "First line".equals(lines.text(lines.indexAt(12_500))), "one digit is tenths");
        that("Chorus".equals(lines.text(lines.indexAt(15_250))), "two digits are hundredths");
        that("Chorus".equals(lines.text(lines.indexAt(62_123))), "repeated stamps share a line");
        that("Chorus".equals(lines.text(lines.indexAt(29_999))), "empty timed lines are skipped");
        that(lines.nextTime(lines.indexAt(15_250)) == 30_000L, "next line start");
        that(lines.nextTime(lines.indexAt(70_000)) == -1L, "last line has no next");

        LrcLines sections = LrcLines.parse("[ti:Like Him]\n[ar:Artist]\n[00:00.00][ti:Like Him]\n"
                + "[00:01.00][Intro: spoken]\n[00:02.00][Verse 1]\n[00:03.00][Chorus]\n"
                + "[00:04.00][note: keep this]\n[00:05.00][AR:Artist][al:Album]Real lyric\n"
                + "[00:06.00][length:3:45][au:Writer][offset:5][re:App][ve:1][by:Editor]\n[00:07.00][tool:editor]\n"
                + "[00:08.00][00:09.00][Intro: repeat]");
        that(sections.indexAt(999) == -1, "timed metadata is skipped");
        that("[Intro: spoken]".equals(sections.text(sections.indexAt(1_000))), "intro with colon survives");
        that("[Verse 1]".equals(sections.text(sections.indexAt(2_000))), "verse survives");
        that("[Chorus]".equals(sections.text(sections.indexAt(3_000))), "chorus survives");
        that("[note: keep this]".equals(sections.text(sections.indexAt(4_000))), "unknown bracketed text survives");
        that("Real lyric".equals(sections.text(sections.indexAt(5_000))), "text after metadata survives");
        that(sections.nextTime(sections.indexAt(5_000)) == 8_000, "metadata-only lines are skipped");
        that("[Intro: repeat]".equals(sections.text(sections.indexAt(9_000))), "multiple timestamps retain sections");

        LrcLines late = LrcLines.parse("[offset:500]\n[00:01.00]Late");
        that(late.indexAt(1_499) == -1 && late.indexAt(1_500) == 0, "offset shifts lines");
        that(LrcLines.parse("plain text only").isEmpty() && LrcLines.parse(null).isEmpty(), "untimed text is empty");
        that(LrcLines.EMPTY.text(LrcLines.EMPTY.indexAt(1000)) == null, "empty has no text");
    }
}
