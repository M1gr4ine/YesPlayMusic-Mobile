package dev.yesplaymusic.probe;

import java.lang.reflect.Method;

/** Authentication must be inferred from a positive account ID, never HTTP code 200 alone. */
public final class NetEaseAuthStateVectors {
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("dev.yesplaymusic.probe.NetEaseAuthState");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("NetEaseAuthState is not implemented", missing);
        }
        Method positiveId = type.getDeclaredMethod("hasPositiveUserId", String.class);
        positiveId.setAccessible(true);
        require((Boolean) positiveId.invoke(null, "123456"), "positive user ID must mean logged in");
        require(!(Boolean) positiveId.invoke(null, "0"), "guest ID string zero must not mean logged in");
        require(!(Boolean) positiveId.invoke(null, "000"), "zero-padded guest ID must not mean logged in");
        require(!(Boolean) positiveId.invoke(null, ""), "empty user ID must not mean logged in");
        require(!(Boolean) positiveId.invoke(null, "-1"), "negative user ID must not mean logged in");
        System.out.println("NetEase auth state vectors passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
