package me.everyone.oculusai.check;

public record CheckResult(boolean failed, String detail) {

    private static final CheckResult PASS = new CheckResult(false, "");

    public static CheckResult pass() {
        return PASS;
    }

    public static CheckResult fail(String detail) {
        return new CheckResult(true, detail);
    }
}
