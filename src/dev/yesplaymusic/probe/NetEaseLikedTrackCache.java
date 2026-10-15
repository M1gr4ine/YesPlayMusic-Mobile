package dev.yesplaymusic.probe;

import java.util.HashSet;
import java.util.Set;

/** In-memory liked-song snapshot scoped to a NetEase account and auth generation. */
final class NetEaseLikedTrackCache {
    private static final long FRESH_FOR_MS = 5L * 60L * 1000L;

    private final Set<String> likedIds = new HashSet<>();
    private String userId = "";
    private long sessionGeneration = Long.MIN_VALUE;
    private long fetchedAtElapsedMs;
    private long revision;
    private boolean accountResolved;
    private boolean loaded;

    synchronized void bindAccount(String nextUserId, long generation) {
        String normalized = NetEaseAuthState.hasPositiveUserId(nextUserId)
                ? nextUserId.trim() : "";
        if (!accountResolved || sessionGeneration != generation || !userId.equals(normalized)) {
            likedIds.clear();
            loaded = false;
            fetchedAtElapsedMs = 0L;
            revision++;
        }
        userId = normalized;
        sessionGeneration = generation;
        accountResolved = true;
    }

    synchronized boolean hasAccountForGeneration(long generation) {
        return accountResolved && sessionGeneration == generation;
    }

    synchronized String userIdForGeneration(long generation) {
        return hasAccountForGeneration(generation) ? userId : "";
    }

    synchronized long revisionFor(String expectedUserId, long generation) {
        return matches(expectedUserId, generation) ? revision : Long.MIN_VALUE;
    }

    synchronized boolean isFresh(String expectedUserId, long generation, long nowElapsedMs) {
        return loaded && matches(expectedUserId, generation) && nowElapsedMs >= fetchedAtElapsedMs
                && nowElapsedMs - fetchedAtElapsedMs < FRESH_FOR_MS;
    }

    synchronized boolean contains(String id) {
        return loaded && likedIds.contains(id);
    }

    synchronized boolean replaceIfRevision(String expectedUserId, long generation, long expectedRevision,
                                            Set<String> ids, long nowElapsedMs) {
        if (!matches(expectedUserId, generation) || revision != expectedRevision) return false;
        putSnapshot(ids, nowElapsedMs);
        revision++;
        return true;
    }

    /** Records a confirmed server mutation and invalidates any older in-flight liked-list read. */
    synchronized boolean markMutationIfCurrent(String expectedUserId, long generation, String id,
                                               boolean liked, long nowElapsedMs) {
        if (!NetEaseAuthState.hasPositiveUserId(id) || !matches(expectedUserId, generation)) return false;
        if (isFresh(expectedUserId, generation, nowElapsedMs)) applyOne(id, liked);
        revision++;
        return true;
    }

    synchronized void clear() {
        likedIds.clear();
        userId = "";
        sessionGeneration = Long.MIN_VALUE;
        fetchedAtElapsedMs = 0L;
        accountResolved = false;
        loaded = false;
        revision++;
    }

    private void putSnapshot(Set<String> ids, long nowElapsedMs) {
        likedIds.clear();
        if (ids != null) {
            for (String id : ids) {
                if (NetEaseAuthState.hasPositiveUserId(id)) likedIds.add(id.trim());
            }
        }
        fetchedAtElapsedMs = nowElapsedMs;
        loaded = true;
    }

    private void applyOne(String id, boolean liked) {
        if (liked) likedIds.add(id.trim());
        else likedIds.remove(id.trim());
    }

    private boolean matches(String expectedUserId, long generation) {
        return accountResolved && sessionGeneration == generation && userId.equals(expectedUserId);
    }
}
