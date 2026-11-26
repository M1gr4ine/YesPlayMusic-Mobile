package dev.yesplaymusic.probe;

/** Runs the JVM-side unit tests for the pure helpers in src/; a failure throws AssertionError. */
public final class AllTests {
    private AllTests() {}

    public static void main(String[] args) throws Exception {
        ArtistAliasesTest.run();
        LrcLinesTest.run();
        MatchTextTest.run();
        MatchTraceTest.run();
        MusicApiTest.run();
        NetEaseCryptoTest.run();
        NetEaseLikedTrackCacheTest.run();
        PlaybackPolicyTest.run();
        PlaylistEditDeadlineTest.run();
        PlaylistEditPolicyTest.run();
        PlaylistTrackOrderTest.run();
        QQMatchTest.run();
        QQSourceTest.run();
        RecentPlaybackStoreTest.run();
        TrackTest.run();
        System.out.println("Java tests passed: " + Check.count() + " checks");
    }
}

final class Check {
    private static int count;

    private Check() {}

    static void that(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        count++;
    }

    static int count() { return count; }
}
