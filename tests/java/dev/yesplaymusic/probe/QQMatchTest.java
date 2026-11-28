package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

final class QQMatchTest {
    private QQMatchTest() {}

    static void run() {
        titleAndVersionMatching();
        keywords();
        selection();
    }

    private static void titleAndVersionMatching() {
        that(QQMatch.sameRecordingTitle("晴天", "晴天"), "exact title matches");
        that(QQMatch.sameRecordingTitle("夜 曲", "夜曲"), "whitespace is ignored");
        that(QQMatch.sameRecordingTitle("千本桜", "千本樱"), "shinjitai folds to simplified");
        that(QQMatch.sameRecordingTitle("帰ろう", "帰ろう (乘风归去)"), "CJK translation subtitle matches");
        that(QQMatch.sameRecordingTitle("FML", "FML (Explicit)"), "Explicit marker is benign");
        that(QQMatch.sameRecordingTitle("Song", "Song (Remastered Version)"), "Remastered Version is benign");
        that(QQMatch.sameRecordingTitle("Song", "Song (Remastered 2011)"), "Remastered year is benign");
        that(QQMatch.sameRecordingTitle("Bound 2", "Bound 2 (Album Version|Explicit)"),
                "pipe-separated benign edition markers match");

        that(!QQMatch.sameRecordingTitle("FML", "Flashing Lights (Explicit)"), "different song title is rejected");
        that(!QQMatch.sameRecordingTitle("花", "花火"), "short CJK title is not a substring");
        that(!QQMatch.sameRecordingTitle("FML", "My FML Life"), "short Latin title is not a substring");
        that(QQMatch.sameRecordingTitle("Alive", "Alive"), "live inside a word is not a version tag");
        that(!QQMatch.sameRecordingTitle("Alive", "Alive (Live)"), "live version tag is rejected");

        for (String tag : new String[]{
                "Live", "Remix", "Instrumental", "Acoustic", "Cover", "Flip", "Mix", "Edit", "Demo", "DJ",
                "Slowed", "Nightcore", "Bootleg", "Mashup", "Karaoke", "Unplugged", "Acapella",
                "HOOLIGANS VERSION", "现场", "現場", "翻唱", "伴奏", "純音樂", "演唱會", "清唱", "小樣", "混音", "加速", "降調"
        }) {
            that(!QQMatch.sameRecordingTitle("CARNIVAL", "CARNIVAL (" + tag + ")"),
                    "version variant rejected: " + tag);
        }

        for (String variant : new String[]{
                "女版", "男版", "白话版", "国语版", "普通话版", "粤语版", "片段版", "民谣版", "DJ Solstice晦朔版", "白话", "國語", "粵語"
        }) {
            that(!QQMatch.sameRecordingTitle("最后派对", "最后派对 (" + variant + ")"),
                    "language/vocal variant rejected: " + variant);
        }
        that(QQMatch.sameRecordingTitle("粤语残片", "粤语残片"), "title containing 粤语 matches itself");
        that(!QQMatch.sameRecordingTitle("粤语残片", "粤语残片 (白话版)"), "粤语残片 rejects 白话版");
        that(!QQMatch.sameRecordingTitle("粤语残片", "粤语残片 (国语版)"), "粤语残片 rejects 国语版");
        that(!QQMatch.sameRecordingTitle("富士山下", "富士山下 (国语)"), "language tag mismatch is rejected");

        // QQ joins subtitles into one bracket: "(Pt. 2|Explicit)", "(中文译名|Explicit)".
        that(QQMatch.sameRecordingTitle("Psycho, Pt. 2", "Psycho,Pt. 2 (Pt. 2|Explicit)"),
                "a bracket repeating the title's own ending plus Explicit is ignored");
        that(QQMatch.sameRecordingTitle("Psycho, Pt. 2", "Psycho (Pt. 2|Explicit)"),
                "a subtitle segment that completes the title is kept and matches");
        that(QQMatch.sameRecordingTitle("帰ろう", "帰ろう (乘风归去|Explicit)"),
                "translation and Explicit segments are both ignored");
        that(!QQMatch.sameRecordingTitle("Song", "Song (Pt. 2)"), "an extra part number is a different song");
        that(!QQMatch.sameRecordingTitle("Song", "Song (Part 2)"), "an extra part name is a different song");
        that(!QQMatch.sameRecordingTitle("Psycho, Pt. 2", "Psycho, Pt. 2 (片段版)"), "片段版 is still rejected");
        that(!QQMatch.sameRecordingTitle("Psycho, Pt. 2", "Psycho (Pt. 2) (Live)"), "Live is still rejected");
        that(!QQMatch.sameRecordingTitle("Song", "Song (Live|Explicit)"), "a version tag inside a pipe group is kept");

        // CJK adjacent to Latin version tags must still be recognized (including under Unicode/ICU \b semantics).
        that(!QQMatch.sameRecordingTitle("K歌之王", "K歌之王 (陈君豪Remix)"),
                "CJK glued to Remix is rejected");
        that(!QQMatch.sameRecordingTitle("Song", "Song (周杰伦live)"),
                "CJK glued to live is rejected");
        that(!QQMatch.sameRecordingTitle("Song", "Song (DJ阿若mix)"),
                "CJK glued to DJ/mix is rejected");
        that(Pattern.compile(QQMatch.LATIN_TAG.pattern(), Pattern.UNICODE_CHARACTER_CLASS)
                        .matcher("陈君豪remix").find(),
                "LATIN_TAG matches remix next to Han chars even under UNICODE_CHARACTER_CLASS");

        // Attribution and soundtrack annotations are ignored unless they carry a version tag.
        that(QQMatch.sameRecordingTitle("A Rusty Dream (from Cyberpunk: Edgerunners 2)", "A Rusty Dream"),
                "(from ...) attribution is ignored");
        that(QQMatch.sameRecordingTitle("Song (feat. B)", "Song"),
                "(feat. ...) collaboration annotation is ignored");
        that(QQMatch.sameRecordingTitle("Song (prod. Metro)", "Song"),
                "(prod. ...) annotation is ignored");
        that(QQMatch.sameRecordingTitle("Song (Original Soundtrack)", "Song")
                        && QQMatch.sameRecordingTitle("Song (OST)", "Song"),
                "soundtrack/OST annotations are ignored");
        that(!QQMatch.sameRecordingTitle("Song (feat. B) (Remix)", "Song"),
                "(feat. B) (Remix) is still rejected");
        that(!QQMatch.sameRecordingTitle("Song (from X Live)", "Song"),
                "(from X Live) is still rejected");
        that(!QQMatch.sameRecordingTitle("Song (feat. B Remix)", "Song"),
                "(feat. B Remix) is still rejected");
    }

    private static void keywords() {
        that("Song - Artist A / Artist B".equals(QQMatch.keyword("Song", "Artist A / Artist B")),
                "UNM keyword joins title and artists with hyphen");
        that("Song".equals(QQMatch.keyword("Song", "")), "keyword without artist keeps title");
        that("FML - Kanye West".equals(QQMatch.fallbackKeyword("FML (Explicit)", "Kanye West / The Weeknd")),
                "fallback strips benign Explicit and keeps first artist");
        that("Famous - Ye".equals(QQMatch.fallbackKeyword("Famous (Explicit)", "Ye (侃爷)")),
                "fallback strips bracketed artist alias");
        that("Bound 2 - Kanye West".equals(
                        QQMatch.fallbackKeyword("Bound 2 (Album Version|Explicit)", "Kanye West / Charlie Wilson")),
                "fallback strips pipe-separated benign markers");
        that("Famous (Live) - Ye".equals(QQMatch.fallbackKeyword("Famous (Live)", "Ye (侃爷)")),
                "fallback preserves non-benign Live marker");
        that("CARNIVAL (HOOLIGANS VERSION) - ¥$".equals(
                        QQMatch.fallbackKeyword("CARNIVAL (HOOLIGANS VERSION)", "¥$ / Kanye West / Ty Dolla $ign")),
                "fallback preserves non-benign version tag");
        that("Back In Black - AC/DC".equals(QQMatch.fallbackKeyword("Back In Black", "AC/DC")),
                "fallback keeps single artist containing an unspaced slash");
    }

    private static void selection() {
        // Duration tier: <= 5s beats unknown duration; known mismatched duration is never selected.
        QQMatch.Song far = song("Song", "Artist", 120_000);
        QQMatch.Song unknown = song("Song", "Artist", 0);
        QQMatch.Song near = song("Song", "Artist", 242_000);
        List<QQMatch.Song> pool = Arrays.asList(far, unknown, near);

        List<QQMatch.Song> matched = QQMatch.select(song("Song", "Artist", 240_000), pool, s -> s, false);
        that(matched.equals(Arrays.asList(near, unknown)),
                "duration match beats unknown duration and excludes known mismatch");
        that(QQMatch.select(song("Song", "Artist", 400_000), pool, s -> s, false)
                        .equals(Collections.singletonList(unknown)),
                "unknown duration acts as fallback when all known durations mismatch");
        that(QQMatch.select(song("Song", "Artist", 0), pool, s -> s, false).equals(pool),
                "unknown expected duration accepts title-matching candidates in order");
        that(QQMatch.select(song("Song", "Artist", 240_000), Collections.singletonList(far), s -> s, false).isEmpty(),
                "known mismatched duration alone is never selected");
        that(QQMatch.select(song("Song", "Artist", 240_000),
                        Collections.singletonList(song("Song", "Artist", 245_000)), s -> s, false).size() == 1,
                "5-second boundary is accepted");

        // Same-master drift: both at least 60s, gap at most 15s and at most 10% of the original.
        QQMatch.Song drift = song("Song", "Artist", 250_000);
        that(QQMatch.select(song("Song", "Artist", 240_000), Arrays.asList(drift, unknown, near), s -> s, false)
                        .equals(Arrays.asList(near, drift, unknown)),
                "within-5s beats same-master drift, which beats unknown duration");
        that(QQMatch.select(song("Song", "Artist", 240_000),
                        Collections.singletonList(song("Song", "Artist", 255_000)), s -> s, false).size() == 1,
                "15-second drift boundary is accepted");
        that(QQMatch.select(song("Song", "Artist", 240_000),
                        Collections.singletonList(song("Song", "Artist", 255_001)), s -> s, false).isEmpty(),
                "beyond 15 seconds is rejected");
        that(QQMatch.select(song("Song", "Artist", 100_000),
                        Collections.singletonList(song("Song", "Artist", 110_000)), s -> s, false).size() == 1,
                "10% drift boundary is accepted");
        that(QQMatch.select(song("Song", "Artist", 100_000),
                        Collections.singletonList(song("Song", "Artist", 110_001)), s -> s, false).isEmpty(),
                "beyond 10% of the original is rejected");
        that(QQMatch.select(song("Song", "Artist", 65_000),
                        Collections.singletonList(song("Song", "Artist", 59_500)), s -> s, false).isEmpty(),
                "a candidate under 60 seconds never gets drift tolerance");
        that(QQMatch.select(song("粤语残片", "陈奕迅", 280_264),
                        Collections.singletonList(song("粤语残片", "陈奕迅", 272_000)), s -> s, false).size() == 1,
                "粤语残片 2019 remaster accepts the QQ master 8.3 seconds shorter");

        // FML must never be replaced by same-artist, similar-duration Flashing Lights; Ye (侃爷) still wins for playback.
        QQMatch.Song fmlTarget = song("FML", "Kanye West / The Weeknd", 236_120);
        QQMatch.Song flashing = song("Flashing Lights (Explicit)", "Ye", 237_000);
        QQMatch.Song fmlQq = song("FML (Explicit)", "Ye (侃爷)", 236_000);
        that(QQMatch.select(fmlTarget, Collections.singletonList(flashing), s -> s, false).isEmpty(),
                "Flashing Lights cannot match FML");
        that(QQMatch.select(fmlTarget, Arrays.asList(flashing, fmlQq), s -> s, false)
                        .equals(Collections.singletonList(fmlQq)),
                "playback accepts renamed Ye (侃爷) for Kanye West in QQ order");

        // Famous excludes 30s preview; CARNIVAL rejects Acapella and HOOLIGANS VERSION.
        QQMatch.Song famousPreview = song("Famous", "Kanye West / Rihanna", 30_000);
        QQMatch.Song famousFull = song("Famous", "Ye (侃爷) / Rihanna", 195_000);
        that(QQMatch.select(song("Famous", "Kanye West / Rihanna", 196_040),
                        Arrays.asList(famousPreview, famousFull), s -> s, false)
                        .equals(Collections.singletonList(famousFull)),
                "30-second preview is excluded");
        that(QQMatch.select(song("CARNIVAL", "¥$ / Kanye West / Ty Dolla $ign", 264_000),
                        Arrays.asList(
                                song("CARNIVAL (Acapella)", "¥$ / Ye (侃爷) / Ty Dolla $ign", 264_000),
                                song("CARNIVAL (HOOLIGANS VERSION)", "¥$ / Ye (侃爷) / Ty Dolla $ign", 264_000)),
                        s -> s, false).isEmpty(),
                "Acapella and HOOLIGANS VERSION are rejected");

        // Playback prefers overlapping artists (including folded CJK and carried aliases/members) over non-overlapping.
        QQMatch.Song cover = song("晴天", "某翻唱", 269_000);
        QQMatch.Song original = song("晴天", "周杰伦", 269_000);
        that(QQMatch.select(song("晴天", "周杰伦", 269_000), Arrays.asList(cover, original), s -> s, false)
                        .equals(Arrays.asList(original, cover)),
                "overlapping artist moves ahead of non-overlapping candidate in the same duration tier");

        QQMatch.Song sakanaction = new QQMatch.Song("新宝岛", "サカナクション", "",
                Arrays.asList("Sakanaction", "鱼韵"), Collections.emptyList(), 305_000);
        QQMatch.Song otherCover = song("新宝岛", "Cover", 305_000);
        QQMatch.Song aliasHit = song("新宝岛", "sakanaction", 305_000);
        that(QQMatch.select(sakanaction, Arrays.asList(otherCover, aliasHit), s -> s, false).get(0) == aliasHit,
                "track-carried artistAliases prioritize the matching candidate");

        // Album-backed asymmetric language annotation and intra-tier ranking (sameAlbum > exactTitle > artistOverlap).
        QQMatch.Song kTarget = song("K歌之王", "陈奕迅", "打得火热", 222_400);
        QQMatch.Song kPiano = song("K歌之王", "陈奕迅", "英皇钢琴热恋系列 陈奕迅", 221_000);
        QQMatch.Song kCantonese = song("K歌之王 (粤语)", "陈奕迅", "打得火热", 222_000);
        QQMatch.Song kMandarin = song("K歌之王 (国语)", "陈奕迅", "反正是我", 218_000);
        QQMatch.Song kRemix = song("K歌之王 (陈君豪Remix)", "陈奕迅", "打得火热", 212_000);
        that(QQMatch.select(kTarget, Arrays.asList(kPiano, kMandarin, kRemix, kCantonese), s -> s, false)
                        .equals(Arrays.asList(kCantonese, kPiano)),
                "same-album Cantonese original beats exact-title piano compilation while rejecting Mandarin and Remix");
        that(QQMatch.select(kTarget, Collections.singletonList(kPiano), s -> s, false)
                        .equals(Collections.singletonList(kPiano)),
                "without a same-album match, exact-title candidate remains available as fallback");
        that(QQMatch.select(kTarget, Collections.singletonList(kMandarin), s -> s, false).isEmpty(),
                "asymmetric language tag on a different album is rejected");
        that(QQMatch.select(song("富士山下", "陈奕迅", "What's Going On...?", 259_000),
                        Collections.singletonList(song("富士山下 (国语)", "陈奕迅", "认了吧", 259_000)),
                        s -> s, false).isEmpty(),
                "富士山下 (国语) on a different album is still rejected");
        that(QQMatch.select(song("K歌之王 (粤语)", "陈奕迅", "精选", 222_000),
                        Collections.singletonList(song("K歌之王 (国语)", "陈奕迅", "精选", 222_000)),
                        s -> s, false).isEmpty(),
                "conflicting language tags on both sides are rejected even on the same album");

        // Reverse matching (requireArtistOverlap=true) rejects non-overlapping artists and unknown placeholders.
        that(QQMatch.select(fmlTarget, Collections.singletonList(fmlQq), s -> s, true).isEmpty(),
                "reverse selection rejects non-overlapping artist credit");
        QQMatch.Song withMemberAlias = new QQMatch.Song("FML", "Kanye West", "", Collections.emptyList(),
                Collections.singletonList(new ArtistAliases.Member("Kanye West", "37520", Arrays.asList("ye"))),
                236_000);
        that(QQMatch.select(withMemberAlias, Collections.singletonList(fmlQq), s -> s, true)
                        .equals(Collections.singletonList(fmlQq)),
                "reverse selection accepts member alias overlap");
        that(QQMatch.select(song("Lemon", "米津玄師", 256_000),
                        Collections.singletonList(song("Lemon", "米津玄师", 256_000)), s -> s, true).size() == 1,
                "folded traditional/shinjitai artist names overlap");
        that(QQMatch.select(song("Song", "Unknown Artist", 200_000),
                        Collections.singletonList(song("Song", "Unknown Artist", 200_000)), s -> s, true).isEmpty(),
                "unknown artist placeholders never count as overlap");
    }

    private static QQMatch.Song song(String title, String artist, long duration) {
        return song(title, artist, "", duration);
    }

    private static QQMatch.Song song(String title, String artist, String album, long duration) {
        return new QQMatch.Song(title, artist, album, Collections.emptyList(), Collections.emptyList(), duration);
    }
}
