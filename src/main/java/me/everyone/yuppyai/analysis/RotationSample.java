package me.everyone.yuppyai.analysis;

public record RotationSample(float yaw, float pitch, boolean attacked, double aimError,
                             AttackMoment moment) {

    public RotationSample(float yaw, float pitch, boolean attacked, double aimError) {
        this(yaw, pitch, attacked, aimError, null);
    }

    public boolean crit() {
        return moment != null && moment.crit();
    }
}
