package dev.yesplaymusic.probe;

import static dev.yesplaymusic.probe.Check.that;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class NetEaseLikedTrackCacheTest {
    private NetEaseLikedTrackCacheTest() {}

    static void run() {
        that(NetEaseAuthState.hasPositiveUserId("123456"), "positive ID is a login");
        for (String guest : new String[]{"0", "000", "", "-1", null}) {
            that(!NetEaseAuthState.hasPositiveUserId(guest), "guest ID " + guest + " is not a login");
        }

        NetEaseLikedTrackCache cache = loaded("42", 7L, 1_000L, "100", "200");
        that(cache.isFresh("42", 7L, 1_001L) && cache.contains("100") && !cache.contains("300"), "snapshot is readable");
        that(!cache.isFresh("99", 7L, 1_001L), "never crosses accounts");
        that(!cache.isFresh("42", 8L, 1_001L), "never crosses login sessions");
        that(!cache.isFresh("42", 7L, 301_001L), "expires after five minutes");

        that(cache.markMutationIfCurrent("42", 7L, "300", true, 1_100L) && cache.contains("300"), "confirmed like shows");
        that(cache.markMutationIfCurrent("42", 7L, "100", false, 1_200L) && !cache.contains("100"), "confirmed unlike shows");
        that(!cache.markMutationIfCurrent("99", 7L, "400", true, 1_300L), "stale account cannot mutate");
        cache.clear();
        that(!cache.isFresh("42", 7L, 1_400L), "logout invalidates");

        NetEaseLikedTrackCache racing = loaded("42", 11L, 2_000L, "100");
        long staleRead = racing.revisionFor("42", 11L);
        that(racing.markMutationIfCurrent("42", 11L, "200", true, 2_100L), "like during an in-flight read");
        that(!racing.replaceIfRevision("42", 11L, staleRead, set("100"), 2_200L) && racing.contains("200"),
                "an older snapshot cannot overwrite a confirmed like");

        NetEaseLikedTrackCache parallel = new NetEaseLikedTrackCache();
        parallel.bindAccount("42", 13L);
        long revision = parallel.revisionFor("42", 13L);
        that(parallel.replaceIfRevision("42", 13L, revision, set("400"), 4_000L), "first parallel read commits");
        that(!parallel.replaceIfRevision("42", 13L, revision, set(), 4_100L) && parallel.contains("400"),
                "a second read from the same revision is dropped");

        NetEaseLikedTrackCache cold = new NetEaseLikedTrackCache();
        cold.bindAccount("42", 12L);
        long coldRead = cold.revisionFor("42", 12L);
        that(cold.markMutationIfCurrent("42", 12L, "300", true, 3_100L), "mutation before the first snapshot");
        that(!cold.replaceIfRevision("42", 12L, coldRead, set("100"), 3_200L) && !cold.isFresh("42", 12L, 3_300L),
                "the stale first snapshot is rejected and the cache stays cold");
    }

    private static NetEaseLikedTrackCache loaded(String userId, long generation, long now, String... ids) {
        NetEaseLikedTrackCache cache = new NetEaseLikedTrackCache();
        cache.bindAccount(userId, generation);
        cache.replaceIfRevision(userId, generation, cache.revisionFor(userId, generation), set(ids), now);
        return cache;
    }

    private static Set<String> set(String... ids) {
        return ids.length == 0 ? Collections.<String>emptySet() : new HashSet<>(Arrays.asList(ids));
    }
}
