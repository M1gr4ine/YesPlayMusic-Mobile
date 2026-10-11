import dev.yesplaymusic.probe.QQFavoriteProtocol;

public final class QQFavoriteProtocolTest {
    public static void main(String[] args) throws Exception {
        check("0039MnYb0qxYhV".equals(QQFavoriteProtocol.normalizeMid(" qq:0039MnYb0qxYhV ")), "normalizes track MID");
        check(QQFavoriteProtocol.matchesMid("qq:0039MnYb0qxYhV", "0039MnYb0qxYhV"), "matches liked-list MID");
        check(!QQFavoriteProtocol.matchesMid("0039MnYb0qxYhV", "0039OtherMid"), "does not match another song");
        check("AddSonglist".equals(QQFavoriteProtocol.writeMethod(true)), "likes add to liked playlist");
        check("DelSonglist".equals(QQFavoriteProtocol.writeMethod(false)), "unlikes remove from liked playlist");
        check(!QQFavoriteProtocol.needsWrite(true, true), "liked state is idempotent");
        check(QQFavoriteProtocol.needsWrite(false, true), "requests add when currently unliked");
        check(QQFavoriteProtocol.needsWrite(true, false), "requests remove when currently liked");

        QQFavoriteProtocol.requireWriteSuccess(200, 0, 0, 0, "AddSonglist");
        check(failureCode(() -> QQFavoriteProtocol.requireWriteSuccess(200, 0, 80105, 0, "AddSonglist")) == 80105,
                "preserves QQ rejection code for the UI");
        fails(() -> QQFavoriteProtocol.requireWriteSuccess(200, 0, 80105, 0, "AddSonglist"), "80105");
        fails(() -> QQFavoriteProtocol.requireWriteSuccess(200, 0, 0, 80105, "AddSonglist"), "retCode");
        fails(() -> QQFavoriteProtocol.requireWriteSuccess(500, 0, 0, 0, "AddSonglist"), "HTTP 500");
        fails(() -> QQFavoriteProtocol.requireWriteSuccess(200, 10000, 0, 0, "AddSonglist"), "outer code 10000");

        check(QQFavoriteProtocol.shouldFetchNextPage(250, 100, 1, 100), "continues while total says more");
        check(!QQFavoriteProtocol.shouldFetchNextPage(250, 250, 0, 50), "stops at reported total");
        fails(() -> QQFavoriteProtocol.shouldFetchNextPage(100, 101, 0, 101), "exceeded the reported total");
        fails(() -> QQFavoriteProtocol.shouldFetchNextPage(100, 100, 1, 100), "conflicts with the reported total");
        fails(() -> QQFavoriteProtocol.shouldFetchNextPage(250, 100, 0, 100), "incomplete");
        fails(() -> QQFavoriteProtocol.shouldFetchNextPage(250, 100, 1, 0), "no progress");
        fails(() -> QQFavoriteProtocol.validatePageCount(101), "page limit");

        System.out.println("PASS QQ favorite protocol cases");
    }

    private static void fails(ThrowingRunnable action, String messagePart) throws Exception {
        try {
            action.run();
        } catch (Exception error) {
            if (error.getMessage() != null && error.getMessage().contains(messagePart)) return;
            throw new AssertionError("expected message containing " + messagePart + ", got " + error, error);
        }
        throw new AssertionError("expected failure containing " + messagePart);
    }

    private static int failureCode(ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (QQFavoriteProtocol.Failure error) {
            return error.getResultCode();
        }
        throw new AssertionError("expected protocol failure");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
