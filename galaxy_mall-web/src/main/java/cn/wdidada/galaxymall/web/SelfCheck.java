package cn.wdidada.galaxymall.web;

public final class SelfCheck {

    private SelfCheck() {
    }

    public static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("SELF-CHECK FAILED: " + message);
        }
    }
}
