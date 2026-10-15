package dev.yesplaymusic.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Timed lyric lines for the media session; mirrors assets/lyrics.js without translations. */
public final class LrcLines {
    public static final LrcLines EMPTY = new LrcLines(new long[0], new String[0]);

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\[offset:([+-]?\\d+)\\]");
    private static final Pattern TAG = Pattern.compile("(?i)^\\[[a-z]+:.*");

    private final long[] times;
    private final String[] texts;

    private LrcLines(long[] times, String[] texts) {
        this.times = times;
        this.texts = texts;
    }

    public static LrcLines parse(String source) {
        if (source == null || source.isEmpty()) return EMPTY;
        String text = source.replace("\r", "");
        long offset = 0L;
        Matcher offsetMatch = OFFSET.matcher(text);
        if (offsetMatch.find()) {
            try { offset = Long.parseLong(offsetMatch.group(1).replace("+", "")); }
            catch (NumberFormatException ignored) { }
        }
        Map<Long, String> byTime = new HashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher stamps = STAMP.matcher(line);
            List<Long> found = new ArrayList<>();
            while (stamps.find()) found.add(stampMs(stamps) + offset);
            if (found.isEmpty()) continue;
            String lyric = STAMP.matcher(line).replaceAll("").trim();
            if (lyric.isEmpty() || TAG.matcher(lyric).matches()) continue;
            for (long time : found) {
                long at = Math.max(0L, time);
                String existing = byTime.get(at);
                byTime.put(at, existing == null ? lyric : existing + " " + lyric);
            }
        }
        if (byTime.isEmpty()) return EMPTY;
        List<Long> keys = new ArrayList<>(byTime.keySet());
        Collections.sort(keys);
        long[] times = new long[keys.size()];
        String[] texts = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            times[i] = keys.get(i);
            texts[i] = byTime.get(keys.get(i));
        }
        return new LrcLines(times, texts);
    }

    private static long stampMs(Matcher match) {
        String fraction = match.group(3) == null ? "0" : match.group(3);
        long millis = Long.parseLong(fraction);
        if (fraction.length() == 1) millis *= 100L;
        else if (fraction.length() == 2) millis *= 10L;
        return (Long.parseLong(match.group(1)) * 60L + Long.parseLong(match.group(2))) * 1000L + millis;
    }

    public boolean isEmpty() {
        return times.length == 0;
    }

    /** Index of the last line starting at or before the position, or -1 before the first line. */
    public int indexAt(long positionMs) {
        int low = 0, high = times.length - 1, found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= positionMs) {
                found = middle;
                low = middle + 1;
            } else high = middle - 1;
        }
        return found;
    }

    public String text(int index) {
        return index >= 0 && index < texts.length ? texts[index] : null;
    }

    /** Start time of the line after the index, or -1 when it is the last line. */
    public long nextTime(int index) {
        return index + 1 < times.length ? times[index + 1] : -1L;
    }
}
