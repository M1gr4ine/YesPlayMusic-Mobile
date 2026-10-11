package dev.yesplaymusic.probe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure helpers for capped playlist expansion and stable order reconstruction. */
final class PlaylistTrackOrder {
    private PlaylistTrackOrder() { }

    static int targetCount(List<String> ids, int limit) {
        if (ids == null || limit <= 0) return 0;
        return Math.min(ids.size(), limit);
    }

    static List<String> missingIds(List<String> ids, Set<String> available, int limit) {
        List<String> missing = new ArrayList<>();
        if (ids == null || available == null) return missing;
        int count = targetCount(ids, limit);
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            String id = ids.get(i);
            if (id != null && !id.isEmpty() && !available.contains(id) && seen.add(id)) missing.add(id);
        }
        return missing;
    }

    static List<String> orderedAvailableIds(List<String> ids, Set<String> available, int limit) {
        List<String> ordered = new ArrayList<>();
        if (ids == null || available == null) return ordered;
        int count = targetCount(ids, limit);
        for (int i = 0; i < count; i++) {
            String id = ids.get(i);
            if (id != null && available.contains(id)) ordered.add(id);
        }
        return ordered;
    }
}