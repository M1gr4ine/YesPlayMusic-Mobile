package dev.yesplaymusic.probe;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Verifies home requests share one deadline and can still collect completed partial results. */
public final class HomeDeadlineVectors {
    public static void main(String[] args) throws Throwable {
        Method await;
        try {
            Class<?> helper = Class.forName("dev.yesplaymusic.probe.RequestDeadline");
            await = helper.getDeclaredMethod("await", Future.class, long.class);
            await.setAccessible(true);
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError("RequestDeadline.await is not implemented", missing);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        Future<String> slow = executor.submit(() -> {
            releaseSlow.await();
            return "slow";
        });
        Future<String> fast = executor.submit(() -> "fast");
        require("fast".equals(fast.get(1, TimeUnit.SECONDS)), "fast request must complete before timing test");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
        long started = System.nanoTime();
        try {
            boolean timedOut = false;
            try {
                await.invoke(null, slow, deadline);
            } catch (InvocationTargetException error) {
                timedOut = error.getCause() instanceof TimeoutException;
            }
            require(timedOut, "slow request must stop waiting at the shared deadline");

            Object completed = await.invoke(null, fast, deadline);
            require("fast".equals(completed), "completed request must remain collectable after deadline");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            require(elapsedMs < 500, "sequential waits exceeded shared deadline: " + elapsedMs + "ms");
            System.out.println("Home request deadline vectors passed");
        } finally {
            slow.cancel(true);
            releaseSlow.countDown();
            executor.shutdownNow();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
