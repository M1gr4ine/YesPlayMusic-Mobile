package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.Set;

final class MatchTextTest {
    private MatchTextTest() {}

    static void run() {
        testOpenCCFoldTableIntegrity();
        testTraditionalAndShinjitaiFolding();
        testNormalizationAndSymbols();
        testRuleTables();
    }

    private static void testOpenCCFoldTableIntegrity() {
        int size = OpenCCFoldTable.size();
        that(size > 3000, "OpenCC fold table has full dictionary coverage: " + size);
        for (int i = 0; i < size; i++) {
            char key = OpenCCFoldTable.keyAt(i);
            char value = OpenCCFoldTable.valueAt(i);
            if (key == value) throw new AssertionError("redundant self-mapping at " + i);
            if (i > 0 && OpenCCFoldTable.keyAt(i - 1) >= key) {
                throw new AssertionError("table not strictly sorted at " + i);
            }
            if (OpenCCFoldTable.foldChar(key) != value) {
                throw new AssertionError("binary search mismatch for " + key);
            }
            if (OpenCCFoldTable.foldChar(value) != value) {
                throw new AssertionError("non-idempotent mapping for " + key + " -> " + value);
            }
        }
        that(true, "all " + size + " OpenCC entries are strictly sorted, non-self, searchable, and idempotent");
    }

    private static void testTraditionalAndShinjitaiFolding() {
        // Traditional Chinese -> Simplified Chinese (including characters beyond the old hand-picked table).
        that("粤语残片".equals(MatchText.normalize("粵語殘片")), "Traditional Cantonese title folds to Simplified");
        that("台湾民谣".equals(MatchText.normalize("臺灣民謠")), "Traditional characters beyond legacy list fold cleanly");
        that("郁闷".equals(MatchText.normalize("鬱悶")), "OpenCC covers general Traditional characters");

        // Japanese Shinjitai -> Simplified Chinese (via Kyujitai -> TSCharacters composition).
        that("樱".equals(MatchText.normalize("桜")) && "樱".equals(MatchText.normalize("櫻")),
                "Japanese shinjitai and Traditional converge to Simplified");
        that("乐".equals(MatchText.normalize("楽")) && "乐".equals(MatchText.normalize("樂")),
                "shinjitai 楽 and Traditional 樂 converge");
        that("举".equals(MatchText.normalize("挙")) && "举".equals(MatchText.normalize("舉")),
                "multi-candidate shinjitai 挙 converges with 舉");
        that("斗".equals(MatchText.normalize("闘")) && "斗".equals(MatchText.normalize("鬥")),
                "multi-candidate shinjitai 闘 converges with 鬥");
        that("乡".equals(MatchText.normalize("郷")) && "乡".equals(MatchText.normalize("鄉")),
                "multi-candidate shinjitai 郷 converges with 鄉");
        that("涩".equals(MatchText.normalize("渋")) && "涩".equals(MatchText.normalize("澀")),
                "multi-candidate shinjitai 渋 converges with 澀");
        that("系".equals(MatchText.normalize("繋")) && "系".equals(MatchText.normalize("繫")),
                "multi-candidate shinjitai 繋 converges with 繫");
        that("邻".equals(MatchText.normalize("隣")) && "邻".equals(MatchText.normalize("鄰")),
                "multi-candidate shinjitai 隣 converges with 鄰");
        that("黑".equals(MatchText.normalize("黒")), "shinjitai 黒 folds to 黑");
        that("步".equals(MatchText.normalize("歩")), "shinjitai 歩 folds to 步");
        that("默".equals(MatchText.normalize("黙")), "shinjitai 黙 folds to 默");
        that("翻唱".equals(MatchText.normalize("翻唱")) && "翻唱".equals(MatchText.normalize("飜唱")),
                "standard simplified Chinese 翻 stays 翻 while kyujitai 飜 folds to 翻");
        that("研".equals(MatchText.normalize("硏")) && "萌".equals(MatchText.normalize("萠")),
                "kyujitai variants outside TSCharacters fold toward standard GB2312 forms");
        that("沪".equals(MatchText.normalize("滬")) && "沪".equals(MatchText.normalize("沪"))
                && "滤".equals(MatchText.normalize("濾")) && "滤".equals(MatchText.normalize("滤")),
                "TS canonical 滬->沪 stays distinct from 濾->滤");
        that("芸".equals(MatchText.normalize("蕓")) && "芸".equals(MatchText.normalize("芸"))
                && "艺".equals(MatchText.normalize("藝")) && "艺".equals(MatchText.normalize("艺")),
                "TS canonical 蕓->芸 stays distinct from 藝->艺");
        that(!"予".equals(MatchText.normalize("豫")) && "予".equals(MatchText.normalize("予"))
                && "豫".equals(MatchText.normalize("豫")),
                "distinct Chinese characters 予 and 豫 never merge");
        that(!"欠".equals(MatchText.normalize("缺")) && "欠".equals(MatchText.normalize("欠"))
                && "缺".equals(MatchText.normalize("缺")),
                "distinct Chinese characters 欠 and 缺 never merge");
        that(!"缶".equals(MatchText.normalize("罐")) && "缶".equals(MatchText.normalize("缶"))
                && "罐".equals(MatchText.normalize("罐")),
                "distinct Chinese characters 缶 and 罐 never merge");
        that(QQMatch.score("沪", "歌手", 200000, "滤", "歌手", 200000) < 0
                && QQMatch.score("欠", "歌手", 200000, "缺", "歌手", 200000) < 0
                && QQMatch.score("予", "歌手", 200000, "豫", "歌手", 200000) < 0
                && QQMatch.score("缶", "歌手", 200000, "罐", "歌手", 200000) < 0
                && QQMatch.score("曲目", "阿芸", 200000, "曲目", "阿艺", 200000) < 0,
                "ambiguous JP edges never cause false title or artist identity collisions");
    }

    private static void testNormalizationAndSymbols() {
        that("ab12".equals(MatchText.normalize("ＡＢ　１２!!")), "NFKC and lowercase strip punctuation and fullwidth forms");
        that(MatchText.normalizeArtistToken("￥$").equals(MatchText.normalizeArtistToken("¥$")),
                "fullwidth symbol-only artist credit folds via NFKC");
        that(!MatchText.normalizeArtistToken("¥$").equals(MatchText.normalizeArtistToken("$")),
                "distinct symbol-only artist credits stay distinct");
        that(MatchText.normalizeArtistToken("---").isEmpty(), "pure punctuation is rejected as an artist token");
        that(MatchText.matchesDisplayCredit("Alpha & Beta / Gamma", Arrays.asList("Alpha & Beta", "Gamma")),
                "display credit matcher preserves internal punctuation on structured members");
        that(!MatchText.matchesDisplayCredit("Alpha / Beta", Arrays.asList("Alpha & Beta")),
                "display credit matcher rejects contradictory grouping");
    }

    private static void testRuleTables() {
        that(MatchRules.isBenignAnnotation("explicit") && MatchRules.isBenignAnnotation("album version|explicit")
                && MatchRules.isBenignAnnotation("remastered 2019") && MatchRules.isBenignAnnotation("完整版"),
                "benign edition annotations are recognized");
        that(!MatchRules.isBenignAnnotation("live") && !MatchRules.isBenignAnnotation("acoustic")
                && !MatchRules.isBenignAnnotation("白话版"),
                "distinct recording versions are never benign");
        that(MatchText.isCompilationAlbum("Greatest Hits") && MatchText.isCompilationAlbum("精選合輯"),
                "compilation detection folds Traditional Chinese first");
        that(!MatchText.isCompilationAlbum("Studio Album"), "normal studio album is not a compilation");
        that(!MatchText.knownArtist("Unknown Artist") && !MatchText.knownArtist("群星")
                && !MatchText.knownArtist("未知歌手") && MatchText.knownArtist("Artist A"),
                "unknown placeholder artist rules work");
        Set<String> tags = MatchText.versionTags("Song (現場) (白話版)");
        that(tags.contains("live") && tags.contains("白话") && tags.contains("ver:白话版"),
                "versionTags folds Traditional Chinese before matching CJK tags and suffix variants");
    }
}
