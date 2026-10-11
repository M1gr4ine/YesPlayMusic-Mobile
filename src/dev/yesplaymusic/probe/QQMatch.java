package dev.yesplaymusic.probe;
import java.text.Normalizer;
import java.util.Locale;

public final class QQMatch {
    private QQMatch() {}
    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static String version(String value) {
        String text = (value == null ? "" : value).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (String tag : new String[]{"live", "remix", "伴奏", "instrumental", "翻唱", "纯音乐", "acoustic", "sped up"}) if (text.contains(tag)) result.append(tag).append('|');
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
                for (String part : artist.split("[/、,&，]")) { String n = normalize(part); if (n.length() >= 2 && y.contains(n)) shared = true; }
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
}
