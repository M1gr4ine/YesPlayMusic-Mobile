package dev.yesplaymusic.probe;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Contract tests for the absolute deadline around multi-request playlist edits. */
final class PlaylistEditDeadlineTest {
    private PlaylistEditDeadlineTest() { }

    static void run() throws Exception {
        disconnectsActiveRequestAndRejectsLaterRequests();
        closingScopeDisconnectsAndClearsThreadLocal();
    }

    private static void disconnectsActiveRequestAndRejectsLaterRequests() throws Exception {
        FakeConnection active = new FakeConnection();
        FakeConnection later = new FakeConnection();
        PlaylistEditDeadline.Scope scope = PlaylistEditDeadline.open(300);
        try {
            PlaylistEditDeadline.attachCurrent(active);
            Check.that(active.disconnected.await(2, TimeUnit.SECONDS),
                    "deadline disconnects the active HTTP request");
            expectExpired(PlaylistEditDeadline::checkCurrent,
                    "the absolute deadline rejects later request work");
            expectExpired(() -> PlaylistEditDeadline.attachCurrent(later),
                    "the absolute deadline rejects attaching a later HTTP request");
            Check.that(later.disconnectCount.get() == 0,
                    "a rejected later connection is not attached to the expired scope");
        } finally {
            scope.close();
        }
    }

    private static void closingScopeDisconnectsAndClearsThreadLocal() throws Exception {
        FakeConnection active = new FakeConnection();
        PlaylistEditDeadline.Scope scope = PlaylistEditDeadline.open(5000);
        PlaylistEditDeadline.attachCurrent(active);
        scope.close();
        Check.that(active.disconnected.await(1, TimeUnit.SECONDS),
                "closing the scope disconnects its active HTTP request");
        PlaylistEditDeadline.checkCurrent();
        Check.that(active.disconnectCount.get() == 1,
                "closing the scope cancels the deadline and disconnects exactly once");
    }

    private static void expectExpired(ThrowingAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (PlaylistEditDeadline.DeadlineExceededException expected) {
            Check.that(true, message);
        }
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static final class FakeConnection extends HttpURLConnection {
        final CountDownLatch disconnected = new CountDownLatch(1);
        final AtomicInteger disconnectCount = new AtomicInteger();

        FakeConnection() throws Exception { super(new URL("http://localhost/")); }

        @Override public void disconnect() {
            disconnectCount.incrementAndGet();
            disconnected.countDown();
        }

        @Override public boolean usingProxy() { return false; }

        @Override public void connect() { }
    }
}
