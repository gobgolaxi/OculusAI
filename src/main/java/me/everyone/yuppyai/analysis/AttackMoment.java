package me.everyone.yuppyai.analysis;

public record AttackMoment(double aimError, float fallDistance, int airTicks,
                           boolean onGround, boolean sprinting, boolean blocked,
                           long intervalMs, int sprintDroppedTicks) {

    public AttackMoment(double aimError, float fallDistance, int airTicks,
                        boolean onGround, boolean sprinting) {
        this(aimError, fallDistance, airTicks, onGround, sprinting, false, -1L, -1);
    }

    public AttackMoment(double aimError, float fallDistance, int airTicks,
                        boolean onGround, boolean sprinting, boolean blocked) {
        this(aimError, fallDistance, airTicks, onGround, sprinting, blocked, -1L, -1);
    }

    public boolean sprintReleased() {
        return sprintDroppedTicks >= 0 && sprintDroppedTicks <= 4;
    }

    public boolean crit() {
        return fallDistance > 0.0F && !onGround && !sprinting && !blocked;
    }

    public boolean impossibleCrit() {
        return fallDistance > 0.0F && (onGround || sprinting);
    }
}
