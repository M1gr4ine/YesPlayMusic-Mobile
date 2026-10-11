package dev.yesplaymusic.probe;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Waits against one shared monotonic deadline while preserving completed partial results. */
final class RequestDeadline {
    private RequestDeadline() { }

    static <T> T await(Future<T> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (future.isDone()) return future.get();
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) throw new TimeoutException("Request deadline exceeded");
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }
}
