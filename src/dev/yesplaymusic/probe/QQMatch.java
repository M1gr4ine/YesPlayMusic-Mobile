package dev.yesplaymusic.probe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QQMatch {
    private QQMatch() {}

    /** Maximum duration gap allowed when selecting a cross-source recording. */
    public static final long UNM_DURATION_MS = 5000;

    // Traditional Chinese / Japanese shinjitai forms that NetEase keeps but QQ often lists in simplified form
    // (米津玄師 vs 米津玄师, 宇多田 titles, 桜 vs 樱). Index-aligned: FOLD_FROM[i] becomes FOLD_TO[i].
    private static final String FOLD_FROM =
            "粵臺閩場風颯飄師戀愛夢東樂楽聲時間語見會來説說傳伝長開門話電國學體氣気歲歳鳥書紅綠緑藍黃雲戰戦櫻桜憶獨歡戲劇線經経結給終繪絵續続練編聽聴誰讀読變変淚涙輝達遠運過還進邊辺邉鐘銀鏡陽陰隣鄰際難願顔顏題飛館魚鳴麗黒實実對対歸帰廣広應応惡悪搖揺晩樣様歩滿満燒焼發発髪髮竜龍緣縁藥薬覺覚觀観譯訳讓譲贈輕軽轉転鐵鉄錄録黙島嶋橋濱浜澤沢齋斎髙優宮葉華慶賀謝從従鬥闘舊壽圓円團団圍爾彌禮顯顕頭頂順須領頼賴騎驗験驚鬱鷹鶴黨齒歯龜亀蟲螢蛍衛裝複覇親觸計記許詩試詞認誕調談請論諾謎講識議護讃讚豐豊貝負貨貴買賣売費資賞質趙跡車軌軍輪辭農這連週遊遙適選遺鄉郷酔釋釈針鈴鋼錯鍵鎖閃閉閑關関陣陸險険隱隠雖雙雞離靈霊靜響頁飯飲養餘馬駆驅騒騷驛駅鳳鴉鶯鷲齢齡罵羅義習聖聞聯職脳腦臉興舉挙艦莊荘萬葉蓮蔵處処號補製規視覽覧約紙級純細紡統絆絡縛織繋繫纏罰網綺総總緒緊綿縦縱績樹權権歷歴殘殺淨浄涼淺減測湯溫漢潔澀渋灣濃灑為爲無煙熱燈營営爭牽狀獄獅現環產産畫當疊畳療癒盜盡監盤眞砕確禍禪稱種穩穏積窓竊競筆節範築簡籠糧紀納紋紛組絃絕絶絲継繼縮後裡裏個們嗎麼與劍剣帶帯獸獣";
    private static final String FOLD_TO =
            "粤台闽场风飒飘师恋爱梦东乐乐声时间语见会来说说传传长开门话电国学体气气岁岁鸟书红绿绿蓝黄云战战樱樱忆独欢戏剧线经经结给终绘绘续续练编听听谁读读变变泪泪辉达远运过还进边边边钟银镜阳阴邻邻际难愿颜颜题飞馆鱼鸣丽黑实实对对归归广广应应恶恶摇摇晚样样步满满烧烧发发发发龙龙缘缘药药觉觉观观译译让让赠轻轻转转铁铁录录默岛岛桥滨滨泽泽斋斋高优宫叶华庆贺谢从从斗斗旧寿圆圆团团围尔弥礼显显头顶顺须领赖赖骑验验惊郁鹰鹤党齿齿龟龟虫萤萤卫装复霸亲触计记许诗试词认诞调谈请论诺谜讲识议护赞赞丰丰贝负货贵买卖卖费资赏质赵迹车轨军轮辞农这连周游遥适选遗乡乡醉释释针铃钢错键锁闪闭闲关关阵陆险险隐隐虽双鸡离灵灵静响页饭饮养余马驱驱骚骚驿驿凤鸦莺鹫龄龄骂罗义习圣闻联职脑脑脸兴举举舰庄庄万叶莲藏处处号补制规视览览约纸级纯细纺统绊络缚织系系缠罚网绮总总绪紧绵纵纵绩树权权历历残杀净净凉浅减测汤温汉洁涩涩湾浓洒为为无烟热灯营营争牵状狱狮现环产产画当叠叠疗愈盗尽监盘真碎确祸禅称种稳稳积窗窃竞笔节范筑简笼粮纪纳纹纷组弦绝绝丝继继缩后里里个们吗么与剑剑带带兽兽";
    // ASCII boundaries rather than \b: Android's ICU regex treats Han characters as word chars,
    // which would hide tags glued to CJK names like "陈君豪Remix".
    static final Pattern LATIN_TAG = Pattern.compile(
            "(?<![a-z0-9])(live|remix|instrumental|acoustic|sped[ -]up|cover|flip|mix|edit|demo|dj|slowed|nightcore|bootleg|mashup|karaoke|unplugged|acapella|a[ -]cappella|cantonese|mandarin|japanese|korean|english)(?![a-z0-9])");
    private static final String[] CJK_TAGS = {
            "伴奏", "翻唱", "纯音乐", "现场", "演唱会", "清唱", "小样", "混音", "加速", "降调", "电音",
            "粤语", "广东话", "白话", "国语", "普通话", "闽南语", "台语", "日语", "日文", "英语", "英文", "韩语", "韩文",
            "女版", "男版", "女声", "男声", "女生版", "男生版", "烟嗓", "片段", "高潮版", "铃声", "戏腔"
    };
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern SYMBOL_CHAR = Pattern.compile("\\p{S}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String SYMBOL_PREFIX = "\u0000";
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "(?i)\\s+/\\s+|\\s*[、;；]\\s*|\\s+(?:feat\\.?|ft\\.?|featuring|with|vs\\.?)\\s+");
    private static final Pattern BRACKET = Pattern.compile("[（(\\[【]([^）)\\]】]+)[）)\\]】]");
    private static final Pattern BENIGN = Pattern.compile(
            "(?i)^(?:explicit|clean|album version|single version|remaster(?:ed)?(?: (?:\\d{4}|version))?|\\d{4} remaster(?:ed)?|原版|完整版|专辑版|正式版)$");
    private static final Pattern ATTRIBUTION = Pattern.compile(
            "(?i)^(?:(?:from|feat|ft|featuring|with|theme\\s+from|prod\\s+by)\\s+|(?:feat|ft|prod)\\.\\s*\\S|(?:original\\s+soundtrack|ost)(?![a-z0-9]))");
    private static final Pattern LANGUAGE_SEGMENT = Pattern.compile(
            "^(?:cantonese|mandarin|japanese|korean|english|粤语|广东话|白话|国语|普通话|闽南语|台语|日语|日文|英语|英文|韩语|韩文)$");
    private static final Set<String> LANGUAGE_TAGS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "cantonese", "mandarin", "japanese", "korean", "english", "白话", "闽南语", "台语")));
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");

    /** Minimal recording metadata used by cross-source selection. */
    static final class Song {
        final String title;
        final String artist;
        final String album;
        final List<String> artistAliases;
        final List<ArtistAliases.Member> artistMembers;
        final long duration;

        Song(String title, String artist, String album, List<String> artistAliases,
             List<ArtistAliases.Member> artistMembers, long duration) {
            this.title = title;
            this.artist = artist;
            this.album = album == null ? "" : album;
            this.artistAliases = artistAliases;
            this.artistMembers = artistMembers;
            this.duration = duration;
        }

        static Song of(Track track) {
            return new Song(track.title, track.artist, track.album,
                    track.artistAliases, track.artistMembers, track.duration);
        }
    }

    private static String fold(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            int index = c < 0x3400 ? -1 : FOLD_FROM.indexOf(c);
            result.append(index < 0 ? c : FOLD_TO.charAt(index));
        }
        return result.toString();
    }

    private static String normalize(String text) {
        return NON_WORD.matcher(fold(text)).replaceAll("");
    }

    static String normalizeArtistToken(String text) {
        String normalized = normalize(text);
        if (!normalized.isEmpty()) return normalized;
        String collapsed = WHITESPACE.matcher(fold(text)).replaceAll("");
        if (!collapsed.isEmpty() && SYMBOL_CHAR.matcher(collapsed).find()) {
            return SYMBOL_PREFIX + collapsed;
        }
        return "";
    }

    private static boolean isBenignAnnotation(String annotation) {
        if (annotation.isEmpty()) return false;
        for (String part : annotation.split("\\s*\\|\\s*")) {
            if (!BENIGN.matcher(part.trim()).matches()) return false;
        }
        return true;
    }

    private static String cleanSearchTitle(String title) {
        String raw = title.trim();
        if (raw.isEmpty()) return "";
        Matcher brackets = BRACKET.matcher(raw);
        StringBuffer result = new StringBuffer();
        while (brackets.find()) {
            String annotation = fold(brackets.group(1)).trim();
            brackets.appendReplacement(result,
                    isBenignAnnotation(annotation) ? "" : Matcher.quoteReplacement(brackets.group()));
        }
        brackets.appendTail(result);
        String cleaned = WHITESPACE.matcher(result.toString()).replaceAll(" ").trim();
        return cleaned.isEmpty() ? raw : cleaned;
    }

    private static TreeSet<String> versionTags(String value) {
        String text = fold(value);
        Matcher latin = LATIN_TAG.matcher(text);
        TreeSet<String> tags = new TreeSet<>();
        while (latin.find()) tags.add(latin.group(1).replace(' ', '-'));
        for (String tag : CJK_TAGS) {
            if (!text.contains(tag)) continue;
            if ("现场".equals(tag) || "演唱会".equals(tag)) tags.add("live");
            else if ("粤语".equals(tag) || "广东话".equals(tag)) tags.add("cantonese");
            else if ("国语".equals(tag) || "普通话".equals(tag)) tags.add("mandarin");
            else if ("日语".equals(tag) || "日文".equals(tag)) tags.add("japanese");
            else if ("英语".equals(tag) || "英文".equals(tag)) tags.add("english");
            else if ("韩语".equals(tag) || "韩文".equals(tag)) tags.add("korean");
            else tags.add(tag);
        }
        Matcher brackets = BRACKET.matcher(text);
        while (brackets.find()) {
            String annotation = brackets.group(1).trim();
            if (annotation.endsWith("版") && !isBenignAnnotation(annotation)) {
                String normalized = normalize(annotation);
                if (!normalized.isEmpty()) tags.add("ver:" + normalized);
            }
        }
        return tags;
    }

    /** Main title with ignorable bracket segments removed; QQ joins subtitles as "(Pt. 2|Explicit)". */
    private static String mainTitle(String title) {
        return mainTitle(title, false);
    }

    private static String mainTitle(String title, boolean stripLanguage) {
        String text = fold(title);
        Matcher brackets = BRACKET.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (brackets.find()) {
            result.append(text, last, brackets.start());
            last = brackets.end();
            List<String> kept = new ArrayList<>();
            for (String segment : brackets.group(1).split("\\|")) {
                String trimmed = segment.trim();
                if (!trimmed.isEmpty() && !ignorableSegment(trimmed, result, stripLanguage)) kept.add(trimmed);
            }
            if (!kept.isEmpty()) result.append('(').append(String.join("|", kept)).append(')');
        }
        result.append(text.substring(last));
        return normalize(result.toString());
    }

    /** Edition markers, attributions, CJK translations and a repeat of the title's own ending carry no recording identity. */
    private static boolean ignorableSegment(String segment, CharSequence titleSoFar, boolean stripLanguage) {
        if (BENIGN.matcher(segment).matches()) return true;
        if (stripLanguage && LANGUAGE_SEGMENT.matcher(segment).matches()) return true;
        if (!versionTags(segment).isEmpty()) return false;
        if (ATTRIBUTION.matcher(segment).lookingAt()) return true;
        if (CJK.matcher(segment).find() && !segment.endsWith("版")) return true;
        String head = normalize(titleSoFar.toString()), tail = normalize(segment);
        return !tail.isEmpty() && head.length() > tail.length() && head.endsWith(tail);
    }

    static boolean sameRecordingTitle(String left, String right) {
        String a = mainTitle(left), b = mainTitle(right);
        return !a.isEmpty() && a.equals(b) && versionTags(left).equals(versionTags(right));
    }

    static boolean sameAlbum(String left, String right) {
        String a = mainTitle(left), b = mainTitle(right);
        return !a.isEmpty() && a.equals(b);
    }

    /** Accepts asymmetric language annotations (e.g. "K歌之王" vs "K歌之王 (粤语)") when callers verify sameAlbum. */
    private static boolean sameTitleExceptLanguage(String left, String right) {
        String a = mainTitle(left, true), b = mainTitle(right, true);
        if (a.isEmpty() || !a.equals(b)) return false;
        boolean leftHasLang = false, rightHasLang = false;
        Set<String> leftOther = new TreeSet<>(), rightOther = new TreeSet<>();
        for (String tag : versionTags(left)) {
            if (LANGUAGE_TAGS.contains(tag)) leftHasLang = true;
            else leftOther.add(tag);
        }
        for (String tag : versionTags(right)) {
            if (LANGUAGE_TAGS.contains(tag)) rightHasLang = true;
            else rightOther.add(tag);
        }
        return leftHasLang != rightHasLang && leftOther.equals(rightOther);
    }

    /** UNM search keyword: "title - artist1 / artist2". */
    public static String keyword(String title, String artist) {
        String name = title.trim();
        String names = artist.trim();
        return names.isEmpty() ? name : name + " - " + names;
    }

    /** Single fallback search keyword: strips benign title annotations and keeps only the first artist. */
    static String fallbackKeyword(String title, String artist) {
        String cleanedTitle = cleanSearchTitle(title);
        String rawArtist = artist.trim();
        int separator = rawArtist.indexOf(" / ");
        String lead = separator > 0 ? rawArtist.substring(0, separator).trim() : rawArtist;
        if (!lead.isEmpty()) {
            String stripped = WHITESPACE.matcher(BRACKET.matcher(lead).replaceAll(" ")).replaceAll(" ").trim();
            if (!stripped.isEmpty()) lead = stripped;
        }
        return keyword(cleanedTitle, lead);
    }

    private static final class Ranked<T> {
        final T item;
        final int tier;
        final int priority;

        Ranked(T item, int tier, int priority) {
            this.item = item;
            this.tier = tier;
            this.priority = priority;
        }
    }

    /**
     * Selects matching recordings in provider order:
     * 1. Requires sameRecordingTitle, or an asymmetric language-only annotation when sameAlbum holds.
     * 2. Duration tiers: within 5s; then same-master drift (both at least 60s, gap at most 15s and 10%);
     *    then unknown duration. A known duration outside these is never chosen, so previews stay out.
     * 3. Within each tier, ranks by sameAlbum > exactTitle > artistOverlap > original provider order.
     *    Reverse matching sets requireArtistOverlap=true.
     */
    static <T> List<T> select(Song target, List<T> candidates, Function<T, Song> toSong, boolean requireArtistOverlap) {
        if (candidates.isEmpty() || mainTitle(target.title).isEmpty()) return Collections.emptyList();
        Set<String> targetArtists = artistTokens(target);
        if (requireArtistOverlap && targetArtists.isEmpty()) return Collections.emptyList();

        List<Ranked<T>> accepted = new ArrayList<>();
        for (T item : candidates) {
            Song candidate = toSong.apply(item);
            int tier = durationTier(target.duration, candidate.duration);
            if (tier < 0) continue;
            boolean albumMatch = sameAlbum(target.album, candidate.album);
            boolean exactTitle = sameRecordingTitle(target.title, candidate.title);
            if (!exactTitle && !(albumMatch && sameTitleExceptLanguage(target.title, candidate.title))) continue;
            boolean artistMatch = !targetArtists.isEmpty()
                    && !Collections.disjoint(targetArtists, artistTokens(candidate));
            if (requireArtistOverlap && !artistMatch) continue;
            int priority = (albumMatch ? 4 : 0) + (exactTitle ? 2 : 0) + (artistMatch ? 1 : 0);
            accepted.add(new Ranked<>(item, tier, priority));
        }

        accepted.sort((left, right) -> {
            int byTier = Integer.compare(left.tier, right.tier);
            return byTier != 0 ? byTier : Integer.compare(right.priority, left.priority);
        });
        List<T> ordered = new ArrayList<>(accepted.size());
        for (Ranked<T> ranked : accepted) ordered.add(ranked.item);
        return ordered;
    }

    private static final long MIN_DRIFT_DURATION_MS = 60_000;
    private static final long MAX_DRIFT_MS = 15_000;

    /** 0: within 5s or no expected duration; 1: same-master drift; 2: unknown candidate duration; -1: mismatch. */
    private static int durationTier(long expected, long actual) {
        if (expected <= 0) return 0;
        if (actual <= 0) return 2;
        long gap = Math.abs(expected - actual);
        if (gap <= UNM_DURATION_MS) return 0;
        boolean fullLength = expected >= MIN_DRIFT_DURATION_MS && actual >= MIN_DRIFT_DURATION_MS;
        return fullLength && gap <= Math.min(MAX_DRIFT_MS, expected / 10) ? 1 : -1;
    }

    static void addCreditTokens(Set<String> tokens, String credit) {
        if (credit.isEmpty()) return;
        for (String part : ARTIST_SPLIT.split(credit)) addAtomTokens(tokens, part);
    }

    private static Set<String> artistTokens(Song song) {
        Set<String> tokens = new LinkedHashSet<>();
        addCreditTokens(tokens, song.artist);
        for (String alias : song.artistAliases) addAtomTokens(tokens, alias);
        for (ArtistAliases.Member member : song.artistMembers) {
            addAtomTokens(tokens, member.name);
            for (String alias : member.aliases) addAtomTokens(tokens, alias);
        }
        return tokens;
    }

    private static void addAtomTokens(Set<String> tokens, String text) {
        if (text.isEmpty()) return;
        String folded = fold(text);
        Matcher brackets = BRACKET.matcher(folded);
        StringBuffer base = new StringBuffer();
        while (brackets.find()) {
            addKnownToken(tokens, brackets.group(1));
            brackets.appendReplacement(base, "");
        }
        brackets.appendTail(base);
        addKnownToken(tokens, base.toString());
    }

    private static void addKnownToken(Set<String> tokens, String text) {
        String token = normalizeArtistToken(text);
        if (token.isEmpty() || isUnknownArtist(token)) return;
        tokens.add(token);
    }

    private static boolean isUnknownArtist(String token) {
        return "unknown".equals(token) || "unknownartist".equals(token)
                || "未知".equals(token) || "未知歌手".equals(token) || "未知艺术家".equals(token)
                || "variousartists".equals(token) || "群星".equals(token);
    }
}
