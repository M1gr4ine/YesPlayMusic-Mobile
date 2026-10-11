package dev.yesplaymusic.probe;

import java.lang.reflect.Method;

/** Verifies logout or a new login invalidates cookies from older in-flight requests. */
public final class SessionGenerationVectors {
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("dev.yesplaymusic.probe.SessionGeneration");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("SessionGeneration is not implemented", missing);
        }
        Object gate = type.getDeclaredConstructor().newInstance();
        Method current = type.getDeclaredMethod("current");
        Method advance = type.getDeclaredMethod("advance");
        Method accepts = type.getDeclaredMethod("accepts", long.class);
        current.setAccessible(true);
        advance.setAccessible(true);
        accepts.setAccessible(true);

        long pendingQrPoll = (Long) current.invoke(gate);
        require((Boolean) accepts.invoke(gate, pendingQrPoll), "current QR poll should be accepted");
        advance.invoke(gate); // logout or a newly started login
        require(!(Boolean) accepts.invoke(gate, pendingQrPoll), "stale QR response must not restore cookies");
        long activeLogin = (Long) current.invoke(gate);
        require((Boolean) accepts.invoke(gate, activeLogin), "active login response should be accepted");
        System.out.println("Session generation vectors passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
