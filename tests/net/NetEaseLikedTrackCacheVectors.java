package dev.yesplaymusic.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Fixture-only checks for account/session scoping of the liked-song cache. */
public final class NetEaseLikedTrackCacheVectors {
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("dev.yesplaymusic.probe.NetEaseLikedTrackCache");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("NetEase liked-song cache is not implemented", missing);
        }

        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object cache = constructor.newInstance();
        Method replace = method(type, "replace", String.class, long.class, Set.class, long.class);
        Method isFresh = method(type, "isFresh", String.class, long.class, long.class);
        Method contains = method(type, "contains", String.class);
        Method update = method(type, "updateIfCurrent", String.class, long.class,
                String.class, boolean.class, long.class);
        Method clear = method(type, "clear");
        Method bindAccount = method(type, "bindAccount", String.class, long.class);
        Method revisionFor = method(type, "revisionFor", String.class, long.class);
        Method replaceIfRevision = method(type, "replaceIfRevision", String.class, long.class,
                long.class, Set.class, long.class);
        Method markMutation = method(type, "markMutationIfCurrent", String.class, long.class,
                String.class, boolean.class, long.class);

        replace.invoke(cache, "42", 7L, new HashSet<>(Arrays.asList("100", "200")), 1_000L);
        require((Boolean) isFresh.invoke(cache, "42", 7L, 1_001L), "fresh cache should match its account and session");
        require((Boolean) contains.invoke(cache, "100"), "cached liked song should be present");
        require(!(Boolean) contains.invoke(cache, "300"), "uncached song should not be liked");
        require(!(Boolean) isFresh.invoke(cache, "99", 7L, 1_001L), "cache must not cross accounts");
        require(!(Boolean) isFresh.invoke(cache, "42", 8L, 1_001L), "cache must not cross login sessions");
        require(!(Boolean) isFresh.invoke(cache, "42", 7L, 301_001L), "expired cache must be refreshed");

        require((Boolean) update.invoke(cache, "42", 7L, "300", true, 1_100L),
                "a confirmed current-session like should update cache");
        require((Boolean) contains.invoke(cache, "300"), "confirmed like should be visible immediately");
        require((Boolean) update.invoke(cache, "42", 7L, "100", false, 1_200L),
                "a confirmed current-session unlike should update cache");
        require(!(Boolean) contains.invoke(cache, "100"), "confirmed unlike should be removed");
        require(!(Boolean) update.invoke(cache, "99", 7L, "400", true, 1_300L),
                "stale account response must not mutate cache");

        clear.invoke(cache);
        require(!(Boolean) isFresh.invoke(cache, "42", 7L, 1_400L), "explicit logout must invalidate cache");

        Object concurrentCache = constructor.newInstance();
        bindAccount.invoke(concurrentCache, "42", 11L);
        long firstReadRevision = (Long) revisionFor.invoke(concurrentCache, "42", 11L);
        require((Boolean) replaceIfRevision.invoke(concurrentCache, "42", 11L,
                        firstReadRevision, new HashSet<>(Arrays.asList("100")), 2_000L),
                "first liked-song snapshot should commit");

        long staleReadRevision = (Long) revisionFor.invoke(concurrentCache, "42", 11L);
        require((Boolean) markMutation.invoke(concurrentCache, "42", 11L, "200", true, 2_100L),
                "confirmed like must advance the account cache revision");
        require(!(Boolean) replaceIfRevision.invoke(concurrentCache, "42", 11L,
                        staleReadRevision, new HashSet<>(Arrays.asList("100")), 2_200L),
                "an older in-flight snapshot must not overwrite a confirmed like");
        require((Boolean) contains.invoke(concurrentCache, "200"),
                "stale snapshot must not remove the confirmed like");

        Object parallelReadCache = constructor.newInstance();
        bindAccount.invoke(parallelReadCache, "42", 13L);
        long parallelRevision = (Long) revisionFor.invoke(parallelReadCache, "42", 13L);
        require((Boolean) replaceIfRevision.invoke(parallelReadCache, "42", 13L, parallelRevision,
                        new HashSet<>(Arrays.asList("400")), 4_000L),
                "first parallel read should populate cache");
        require(!(Boolean) replaceIfRevision.invoke(parallelReadCache, "42", 13L, parallelRevision,
                        new HashSet<String>(), 4_100L),
                "second read from the same revision must not overwrite a committed snapshot");
        require((Boolean) isFresh.invoke(parallelReadCache, "42", 13L, 4_200L)
                        && (Boolean) contains.invoke(parallelReadCache, "400"),
                "rejected parallel read leaves a fresh current snapshot available to its caller");

        Object coldCache = constructor.newInstance();
        bindAccount.invoke(coldCache, "42", 12L);
        long coldReadRevision = (Long) revisionFor.invoke(coldCache, "42", 12L);
        require((Boolean) markMutation.invoke(coldCache, "42", 12L, "300", true, 3_100L),
                "confirmed like must invalidate old reads even before a full snapshot loads");
        require(!(Boolean) replaceIfRevision.invoke(coldCache, "42", 12L,
                        coldReadRevision, new HashSet<>(Arrays.asList("100")), 3_200L),
                "old in-flight snapshot must not become the cache after a cold-cache mutation");
        require(!(Boolean) isFresh.invoke(coldCache, "42", 12L, 3_300L),
                "a rejected stale snapshot must leave the cache cold");

        System.out.println("NetEase liked-track cache vectors passed");
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}