package dev.yesplaymusic.probe;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Absolute wall-clock bound for a sequence of playlist mutation HTTP requests. */
final class PlaylistEditDeadline {
    static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

    private static final ThreadLocal<PlaylistEditDeadline> CURRENT = new ThreadLocal<>();
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private final long deadlineNanos;
    private boolean expired;
    private HttpURLConnection activeConnection;
    private ScheduledFuture<?> expiryTask;

    private PlaylistEditDeadline(long timeoutMillis) {
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        expiryTask = TIMER.schedule(this::expire, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    static Scope open() {
        return open(DEFAULT_TIMEOUT_MILLIS);
    }

    static Scope open(long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("截止时间必须大于零");
        if (CURRENT.get() != null) throw new IllegalStateException("不能嵌套歌单编辑截止时间");
        PlaylistEditDeadline deadline = new PlaylistEditDeadline(timeoutMillis);
        CURRENT.set(deadline);
        return new Scope(deadline);
    }

    static void checkCurrent() throws DeadlineExceededException {
        PlaylistEditDeadline deadline = CURRENT.get();
        if (deadline != null) deadline.check();
    }

    static void attachCurrent(HttpURLConnection connection) throws DeadlineExceededException {
        PlaylistEditDeadline deadline = CURRENT.get();
        if (deadline != null) deadline.attach(connection);
    }

    static void detachCurrent(HttpURLConnection connection) {
        PlaylistEditDeadline deadline = CURRENT.get();
        if (deadline != null) deadline.detach(connection);
    }

    private void check() throws DeadlineExceededException {
        HttpURLConnection toDisconnect = null;
        synchronized (this) {
            if (!expired && System.nanoTime() - deadlineNanos >= 0) {
                expired = true;
                toDisconnect = activeConnection;
                activeConnection = null;
            }
            if (!expired) return;
        }
        disconnect(toDisconnect);
        throw new DeadlineExceededException();
    }

    private void attach(HttpURLConnection connection) throws DeadlineExceededException {
        if (connection == null) throw new IllegalArgumentException("连接不能为空");
        HttpURLConnection toDisconnect = null;
        synchronized (this) {
            if (!expired && System.nanoTime() - deadlineNanos >= 0) {
                expired = true;
                toDisconnect = activeConnection;
                activeConnection = null;
            }
            if (!expired) {
                if (activeConnection != null) throw new IllegalStateException("仍有歌单请求正在执行");
                activeConnection = connection;
                return;
            }
        }
        disconnect(toDisconnect);
        throw new DeadlineExceededException();
    }

    private synchronized void detach(HttpURLConnection connection) {
        if (activeConnection == connection) activeConnection = null;
    }

    private void expire() {
        HttpURLConnection toDisconnect;
        synchronized (this) {
            if (expired) return;
            expired = true;
            toDisconnect = activeConnection;
            activeConnection = null;
        }
        disconnect(toDisconnect);
    }

    private void close() {
        HttpURLConnection toDisconnect;
        ScheduledFuture<?> task;
        synchronized (this) {
            task = expiryTask;
            expiryTask = null;
            toDisconnect = activeConnection;
            activeConnection = null;
        }
        if (task != null) task.cancel(false);
        disconnect(toDisconnect);
    }

    private static void disconnect(HttpURLConnection connection) {
        if (connection == null) return;
        try {
            connection.disconnect();
        } catch (RuntimeException ignored) {
            // The request thread still owns its normal finally cleanup.
        }
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "playlist-edit-deadline");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static final class Scope implements AutoCloseable {
        private PlaylistEditDeadline deadline;

        Scope(PlaylistEditDeadline deadline) { this.deadline = deadline; }

        @Override public void close() {
            PlaylistEditDeadline current = deadline;
            if (current == null) return;
            deadline = null;
            if (CURRENT.get() == current) CURRENT.remove();
            current.close();
        }
    }

    static final class DeadlineExceededException extends IOException {
        DeadlineExceededException() { super("歌单编辑超时，修改结果未确认，请刷新歌单"); }
    }
}
