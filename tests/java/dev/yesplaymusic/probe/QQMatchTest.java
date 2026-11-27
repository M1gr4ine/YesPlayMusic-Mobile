package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        that(QQMatch.select(song("Song", "Artist", 240_000),
                        Collections.singletonList(song("Song", "Artist", 245_001)), s -> s, false).isEmpty(),
                "beyond 5-second boundary is rejected");

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

        QQMatch.Song sakanaction = new QQMatch.Song("新宝岛", "サカナクション",
                Arrays.asList("Sakanaction", "鱼韵"), Collections.emptyList(), 305_000);
        QQMatch.Song otherCover = song("新宝岛", "Cover", 305_000);
        QQMatch.Song aliasHit = song("新宝岛", "sakanaction", 305_000);
        that(QQMatch.select(sakanaction, Arrays.asList(otherCover, aliasHit), s -> s, false).get(0) == aliasHit,
                "track-carried artistAliases prioritize the matching candidate");

        // Reverse matching (requireArtistOverlap=true) rejects non-overlapping artists and unknown placeholders.
        that(QQMatch.select(fmlTarget, Collections.singletonList(fmlQq), s -> s, true).isEmpty(),
                "reverse selection rejects non-overlapping artist credit");
        QQMatch.Song withMemberAlias = new QQMatch.Song("FML", "Kanye West", Collections.emptyList(),
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
        return new QQMatch.Song(title, artist, Collections.emptyList(), Collections.emptyList(), duration);
    }
}
