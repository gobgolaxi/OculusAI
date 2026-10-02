package me.everyone.yuppyai.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

public record FeatureVector(
        double scaleVsBaseline,
        double yawStdRelative,
        double pitchMeanRelative,
        double pitchStdRelative,
        double yawAccelRelative,
        double pitchAccelRelative,
        double pitchSpanRelative,
        double smoothness,
        double snapRatio,
        double zeroRatio,
        double axisCorrelation,
        double quantaPerTick,
        double quantumError,
        double attacksPerSecond,
        double aimErrorMean,
        double aimErrorStd,
        double aimErrorMin) {

    public Map<String, Double> toMap() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("scale_vs_baseline", scaleVsBaseline);
        map.put("yaw_std_rel", yawStdRelative);
        map.put("pitch_mean_rel", pitchMeanRelative);
        map.put("pitch_std_rel", pitchStdRelative);
        map.put("yaw_accel_rel", yawAccelRelative);
        map.put("pitch_accel_rel", pitchAccelRelative);
        map.put("pitch_span_rel", pitchSpanRelative);
        map.put("smoothness", smoothness);
        map.put("snap_ratio", snapRatio);
        map.put("zero_ratio", zeroRatio);
        map.put("axis_correlation", axisCorrelation);
        map.put("quanta_per_tick", quantaPerTick);
        map.put("quantum_error", quantumError);
        map.put("attacks_per_second", attacksPerSecond);
        map.put("aim_error_mean", aimErrorMean);
        map.put("aim_error_std", aimErrorStd);
        map.put("aim_error_min", aimErrorMin);
        return map;
    }
}
