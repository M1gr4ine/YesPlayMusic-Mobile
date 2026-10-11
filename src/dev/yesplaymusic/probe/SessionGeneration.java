package dev.yesplaymusic.probe;

/** Monotonic request ticket used to reject stale authentication responses. */
final class SessionGeneration {
    private long value;

    synchronized long current() { return value; }
    synchronized long advance() { return ++value; }
    synchronized boolean accepts(long captured) { return value == captured; }
}
