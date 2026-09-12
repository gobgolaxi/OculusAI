package me.everyone.yuppyai.manager;

public enum KickAnimation {
    NONE, EXPLODE, LIGHTNING, FIREWORK, WITHER;

    private static final KickAnimation[] VALUES = values();

    public KickAnimation next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public static KickAnimation parse(String name) {
        if (name == null) return NONE;
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return NONE;
        }
    }
}
