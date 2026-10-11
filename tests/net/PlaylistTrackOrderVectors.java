package dev.yesplaymusic.probe;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PlaylistTrackOrderVectors {
    public static void main(String[] args) {
        List<String> ids = Arrays.asList("20", "10", "30", "20", "40");
        Set<String> available = new HashSet<>(Arrays.asList("10", "20", "40"));
        require(PlaylistTrackOrder.targetCount(ids, 3) == 3, "playlist cap must limit the requested prefix");
        require(PlaylistTrackOrder.missingIds(ids, available, 3).equals(Arrays.asList("30")),
                "missing IDs must be deduplicated and stay in playlist order");
        require(PlaylistTrackOrder.orderedAvailableIds(ids, available, 5)
                        .equals(Arrays.asList("20", "10", "20", "40")),
                "resolved tracks must preserve playlist order and duplicate entries");
        require(PlaylistTrackOrder.orderedAvailableIds(ids, available, 0).isEmpty(),
                "zero cap must return no tracks");
        System.out.println("Playlist track ordering vectors passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}