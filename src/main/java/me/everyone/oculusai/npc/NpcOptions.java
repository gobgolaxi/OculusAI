package me.everyone.oculusai.npc;

public record NpcOptions(double health, boolean stationary, boolean invulnerable) {

    public static final NpcOptions DEFAULT = new NpcOptions(-1.0D, false, true);

    public boolean endless() {
        return invulnerable;
    }
}