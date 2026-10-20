package me.everyone.oculusai.check;

public record CheckResult(boolean failed, boolean cancel, String detail) {

    private static final CheckResult PASS = new CheckResult(false, false, "");

    public static CheckResult pass() {
        return PASS;
    }

    public static CheckResult fail(String detail) {
        return new CheckResult(true, false, detail);
    }

    public static CheckResult cancel(String detail) {
        return new CheckResult(true, true, detail);
    }
}
