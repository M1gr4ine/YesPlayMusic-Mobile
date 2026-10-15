package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PlaylistTrackOrderTest {
    private PlaylistTrackOrderTest() {}

    static void run() {
        List<String> ids = Arrays.asList("20", "10", "30", "20", "40");
        Set<String> available = new HashSet<>(Arrays.asList("10", "20", "40"));
        that(PlaylistTrackOrder.missingIds(ids, available, 3).equals(Arrays.asList("30")),
                "missing IDs are capped, deduplicated and in playlist order");
        that(PlaylistTrackOrder.orderedAvailableIds(ids, available, 5).equals(Arrays.asList("20", "10", "20", "40")),
                "resolved tracks keep playlist order and duplicates");
        that(PlaylistTrackOrder.orderedAvailableIds(ids, available, 0).isEmpty(), "zero cap returns nothing");
    }
}
