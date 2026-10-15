package dev.yesplaymusic.probe;
import java.text.Normalizer;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QQMatch {
    private QQMatch() {}
    /** UNM select.js only looks at the first five search results. */
    public static final int UNM_WINDOW = 5;
    /** UNM select.js accepts a result whose duration is within 5 s of the requested song. */
    public static final long UNM_DURATION_MS = 5000;
    // Traditional Chinese / Japanese shinjitai forms that NetEase keeps but QQ often lists in simplified form
    // (米津玄師 vs 米津玄师, 宇多田 titles, 桜 vs 樱). Index-aligned: FOLD_FROM[i] becomes FOLD_TO[i].
    public static final String FOLD_FROM =
            "風颯飄師戀愛夢東樂楽聲時間語見會來説說傳伝長開門話電國學體氣気歲歳鳥書紅綠緑藍黃雲戰戦櫻桜憶獨歡戲劇線經経結給終繪絵續続練編聽聴誰讀読變変淚涙輝達遠運過還進邊辺邉鐘銀鏡陽陰隣鄰際難願顔顏題飛館魚鳴麗黒實実對対歸帰廣広應応惡悪搖揺晩樣様歩滿満燒焼發発髪髮竜龍緣縁藥薬覺覚觀観譯訳讓譲贈輕軽轉転鐵鉄錄録黙島嶋橋濱浜澤沢齋斎髙優宮葉華慶賀謝從従鬥闘舊壽圓円團団圍爾彌禮顯顕頭頂順須領頼賴騎驗験驚鬱鷹鶴黨齒歯龜亀蟲螢蛍衛裝複覇親觸計記許詩試詞認誕調談請論諾謎講識議護讃讚豐豊貝負貨貴買賣売費資賞質趙跡車軌軍輪辭農這連週遊遙適選遺鄉郷酔釋釈針鈴鋼錯鍵鎖閃閉閑關関陣陸險険隱隠雖雙雞離靈霊靜響頁飯飲養餘馬駆驅騒騷驛駅鳳鴉鶯鷲齢齡罵羅義習聖聞聯職脳腦臉興舉挙艦莊荘萬葉蓮蔵處処號補製規視覽覧約紙級純細紡統絆絡縛織繋繫纏罰網綺総總緒緊綿縦縱績樹權権歷歴殘殺淨浄涼淺減測湯溫漢潔澀渋灣濃灑為爲無煙熱燈營営爭牽狀獄獅現環產産畫當疊畳療癒盜盡監盤眞砕確禍禪稱種穩穏積窓竊競筆節範築簡籠糧紀納紋紛組絃絕絶絲継繼縮後裡裏個們嗎麼與劍剣帶帯獸獣";
    public static final String FOLD_TO =
            "风飒飘师恋爱梦东乐乐声时间语见会来说说传传长开门话电国学体气气岁岁鸟书红绿绿蓝黄云战战樱樱忆独欢戏剧线经经结给终绘绘续续练编听听谁读读变变泪泪辉达远运过还进边边边钟银镜阳阴邻邻际难愿颜颜题飞馆鱼鸣丽黑实实对对归归广广应应恶恶摇摇晚样样步满满烧烧发发发发龙龙缘缘药药觉觉观观译译让让赠轻轻转转铁铁录录默岛岛桥滨滨泽泽斋斋高优宫叶华庆贺谢从从斗斗旧寿圆圆团团围尔弥礼显显头顶顺须领赖赖骑验验惊郁鹰鹤党齿齿龟龟虫萤萤卫装复霸亲触计记许诗试词认诞调谈请论诺谜讲识议护赞赞丰丰贝负货贵买卖卖费资赏质赵迹车轨军轮辞农这连周游遥适选遗乡乡醉释释针铃钢错键锁闪闭闲关关阵陆险险隐隐虽双鸡离灵灵静响页饭饮养余马驱驱骚骚驿驿凤鸦莺鹫龄龄骂罗义习圣闻联职脑脑脸兴举举舰庄庄万叶莲藏处处号补制规视览览约纸级纯细纺统绊络缚织系系缠罚网绮总总绪紧绵纵纵绩树权权历历残杀净净凉浅减测汤温汉洁涩涩湾浓洒为为无烟热灯营营争牵状狱狮现环产产画当叠叠疗愈盗尽监盘真碎确祸禅称种稳稳积窗窃竞笔节范筑简笼粮纪纳纹纷组弦绝绝丝继继缩后里里个们吗么与剑剑带带兽兽";
    private static final Pattern LATIN_TAG = Pattern.compile("\\b(live|remix|instrumental|acoustic|sped up)\\b");
    private static final String[] CJK_TAGS = {"伴奏", "翻唱", "纯音乐", "现场"};
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("[/、,&，]");
    private static final Pattern SLASH = Pattern.compile("\\s*/\\s*");

    public static String normalize(String text) {
        String folded = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            int index = c < 0x3400 ? -1 : FOLD_FROM.indexOf(c);
            result.append(index < 0 ? c : FOLD_TO.charAt(index));
        }
        return NON_WORD.matcher(result).replaceAll("");
    }
    private static String version(String value) {
        String text = (value == null ? "" : value).toLowerCase(Locale.ROOT);
        Matcher latin = LATIN_TAG.matcher(text);
        TreeSet<String> tags = new TreeSet<>();
        while (latin.find()) tags.add(latin.group(1));
        for (String tag : CJK_TAGS) if (text.contains(tag)) tags.add("现场".equals(tag) ? "live" : tag);
        StringBuilder result = new StringBuilder();
        for (String tag : tags) result.append(tag).append('|');
        return result.toString();
    }
    public static int score(String title, String artist, long duration, String candidateTitle, String candidateArtist, long candidateDuration) {
        String a = normalize(title), b = normalize(candidateTitle);
        if (a.isEmpty() || b.isEmpty() || !version(title).equals(version(candidateTitle))) return -1;
        int score = a.equals(b) ? 100 : (a.contains(b) || b.contains(a)) ? 65 : -1;
        if (score < 0) return -1;
        String x = normalize(artist), y = normalize(candidateArtist);
        if (!x.isEmpty() && !y.isEmpty()) {
            if (x.equals(y) || x.contains(y) || y.contains(x)) score += 60;
            else {
                boolean shared = false;
                for (String part : ARTIST_SEPARATOR.split(artist)) {
                    String n = normalize(part);
                    if (n.length() >= 2 && y.contains(n)) { shared = true; break; }
                }
                if (!shared) return -1;
                score += 40;
            }
        } else score += 20;
        if (duration > 0 && candidateDuration > 0) {
            long difference = Math.abs(duration - candidateDuration);
            if (difference > 8000) return -1;
            score += difference <= 3000 ? 30 : 10;
        }
        return score >= 140 ? score : -1;
    }

    /** UNM search keyword: the song name followed by every credited artist. */
    public static String keyword(String title, String artist) {
        String names = SLASH.matcher(artist == null ? "" : artist.trim()).replaceAll(" ");
        String name = title == null ? "" : title.trim();
        return names.isEmpty() ? name : name + " " + names;
    }

    /**
     * UNM select.js: within the first {@link #UNM_WINDOW} results, take one whose duration is within
     * {@link #UNM_DURATION_MS}. Artist names are ignored (QQ often credits Japanese and Western artists
     * under a Chinese name), but a live/instrumental/remix result still has to match the requested version.
     * When the search query held only the title, the candidate title must also contain the requested one.
     */
    public static boolean unmAccepts(String title, long duration, String candidateTitle, long candidateDuration, boolean requireTitle) {
        String a = normalize(title), b = normalize(candidateTitle);
        if (a.isEmpty() || b.isEmpty() || !version(title).equals(version(candidateTitle))) return false;
        boolean related = a.contains(b) || b.contains(a);
        if (duration > 0 && candidateDuration > 0) {
            if (Math.abs(duration - candidateDuration) >= UNM_DURATION_MS) return false;
            return related || !requireTitle;
        }
        return related;
    }
}
