package dev.yesplaymusic.probe;

/** Pure validation rules shared by QQ favorite operations and offline tests. */
public final class QQFavoriteProtocol {
    public static final int PAGE_SIZE = 100;
    public static final int MAX_PAGES = 100;
    public static final int MAX_SONGS = PAGE_SIZE * MAX_PAGES;

    private QQFavoriteProtocol() {}

    public static String normalizeMid(String value) {
        String mid = value == null ? "" : value.trim();
        if (mid.startsWith("qq:")) mid = mid.substring(3).trim();
        return mid;
    }

    public static boolean matchesMid(String requested, String candidate) {
        String left = normalizeMid(requested);
        String right = normalizeMid(candidate);
        return !left.isEmpty() && left.equals(right);
    }

    public static String writeMethod(boolean liked) {
        return liked ? "AddSonglist" : "DelSonglist";
    }

    public static boolean needsWrite(boolean currentlyLiked, boolean desiredLiked) {
        return currentlyLiked != desiredLiked;
    }

    /**
     * Returns whether another page is required, and rejects an incomplete or
     * non-progressing response instead of silently treating it as unliked.
     * hasMore is 1, 0, or -1 when the upstream omitted the field.
     */
    public static boolean shouldFetchNextPage(int total, int loaded, int hasMore, int pageLength) throws Failure {
        if (loaded < 0 || pageLength < 0 || hasMore < -1 || hasMore > 1) {
            throw new Failure(502, "invalid pagination values");
        }
        if (loaded > MAX_SONGS || (total > MAX_SONGS)) {
            throw new Failure(502, "liked playlist exceeds the safe page limit");
        }
        if (total >= 0 && loaded > total) {
            throw new Failure(502, "liked playlist count exceeded the reported total");
        }
        if (total >= 0 && loaded == total) {
            if (hasMore == 1) throw new Failure(502, "liked playlist pagination conflicts with the reported total");
            return false;
        }
        if (pageLength == 0) {
            if (hasMore == 1) throw new Failure(502, "liked playlist pagination made no progress");
            if (total >= 0 && loaded < total) throw new Failure(502, "incomplete liked playlist: empty page before total");
            return false;
        }
        if (total >= 0) {
            if (hasMore == 0) throw new Failure(502, "incomplete liked playlist: hasmore ended before total");
            return loaded < total;
        }
        if (hasMore == 1) return true;
        if (hasMore == 0) return false;
        return pageLength >= PAGE_SIZE;
    }

    public static void validatePageCount(int pagesFetched) throws Failure {
        if (pagesFetched < 1 || pagesFetched > MAX_PAGES) {
            throw new Failure(502, "liked playlist page limit exceeded");
        }
    }

    public static void requireWriteSuccess(int httpStatus, int outerCode, int blockCode, int retCode, String action) throws Failure {
        String operation = action == null || action.trim().isEmpty() ? "QQ favorite write" : action.trim();
        if (httpStatus != 200) throw new Failure(502, operation + " HTTP " + httpStatus);
        if (outerCode != 0) throw new Failure(outerCode, operation + " outer code " + outerCode);
        if (blockCode != 0) throw new Failure(blockCode, operation + " block code " + blockCode);
        if (retCode != 0) throw new Failure(retCode, operation + " retCode " + retCode);
    }

    public static final class Failure extends Exception {
        private final int resultCode;

        public Failure(int resultCode, String message) {
            super(message);
            this.resultCode = resultCode == 200 || resultCode <= 0 ? 502 : resultCode;
        }

        public int getResultCode() {
            return resultCode;
        }
    }
}
