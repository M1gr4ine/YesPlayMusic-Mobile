package dev.yesplaymusic.probe;

/** Helpers for distinguishing an authenticated account from a successful guest API response. */
final class NetEaseAuthState {
    private NetEaseAuthState() { }

    static boolean hasPositiveUserId(String value) {
        if (value == null) return false;
        String id = value.trim();
        if (!id.matches("[0-9]{1,24}")) return false;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) != '0') return true;
        }
        return false;
    }
}
