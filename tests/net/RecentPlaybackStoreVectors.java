package dev.yesplaymusic.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fixture checks for local recent-playback ordering, catalog deduplication, and URL exclusion. */
public final class RecentPlaybackStoreVectors {
    public static void main(String[] args) throws Exception {
        Class<?> store;
        try {
            store = Class.forName("dev.yesplaymusic.probe.RecentPlaybackStore");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("RecentPlaybackStore is not implemented", missing);
        }

        Class<?> entryType = Class.forName("dev.yesplaymusic.probe.RecentPlaybackStore$Entry");
        Constructor<?> entryConstructor = entryType.getDeclaredConstructor(String.class,
                String.class, String.class, String.class, String.class, String.class,
                long.class, String.class, long.class);
        entryConstructor.setAccessible(true);
        Method merge = store.getDeclaredMethod("merge", List.class, entryType, int.class);
        merge.setAccessible(true);
        Method fromTrack = store.getDeclaredMethod("fromTrack", Track.class, long.class);
        fromTrack.setAccessible(true);
        Method encode = entryType.getDeclaredMethod("toJsonString");
        encode.setAccessible(true);

        Object oldNetease = entry(entryConstructor, "123", "netease", "old title", 100L);
        Object sameIdQq = entry(entryConstructor, "123", "qq", "QQ catalog", 200L);
        Object otherNetease = entry(entryConstructor, "456", "netease", "other", 250L);
        List<Object> old = new ArrayList<>(Arrays.asList(oldNetease, sameIdQq, otherNetease));
        Object newestNetease = entry(entryConstructor, "123", "netease", "new title", 300L);
        @SuppressWarnings("unchecked")
        List<Object> ordered = (List<Object>) merge.invoke(null, old, newestNetease, 100);
        require(ordered.size() == 3, "same provider/id must deduplicate while another provider remains");
        require(key(ordered.get(0)).equals("netease:123"), "most recently played must be first");
        require(title(ordered.get(0)).equals("new title"), "the newest duplicate metadata must win");
        require(key(ordered.get(1)).equals("netease:456"), "remaining tracks must be newest-first");
        require(key(ordered.get(2)).equals("qq:123"), "same id from another catalog must remain distinct");

        List<Object> oversized = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            oversized.add(entry(entryConstructor, String.valueOf(i), "netease", "track " + i, i + 1L));
        }
        @SuppressWarnings("unchecked")
        List<Object> bounded = (List<Object>) merge.invoke(null, oversized, null, 100);
        require(bounded.size() == 100, "history must be capped at 100 entries");
        require(key(bounded.get(0)).equals("netease:104"), "cap must retain the newest tracks");

        Track track = Track.fromJson(new org.json.JSONObject()
                .put("id", "789").put("title", "Safe title").put("artist", "Artist")
                .put("album", "Album").put("artwork", "https://img.example/cover.jpg")
                .put("duration", 180000L).put("provider", "netease")
                .put("url", "https://audio.example/play?signature=fixture-only-secret"));
        Object saved = fromTrack.invoke(null, track, 500L);
        String persistedJson = String.valueOf(encode.invoke(saved));
        require(!persistedJson.contains("\"url\""), "history must not persist a playback URL field");
        require(!persistedJson.contains("fixture-only-secret"), "history must not persist signed URL data");

        System.out.println("Recent playback store vectors passed");
    }

    private static Object entry(Constructor<?> constructor, String id, String provider,
                                 String title, long playedAt) throws Exception {
        return constructor.newInstance(id, provider, title, "Artist", "Album", "", 180000L,
                "", playedAt);
    }

    private static String key(Object entry) throws Exception {
        return field(entry, "provider") + ":" + field(entry, "id");
    }

    private static String title(Object entry) throws Exception {
        return field(entry, "title");
    }

    private static String field(Object entry, String name) throws Exception {
        java.lang.reflect.Field field = entry.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return String.valueOf(field.get(entry));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
