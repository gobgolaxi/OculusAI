package me.everyone.yuppyai.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FeatureSchemas {

    public static final int V2 = 2;
    public static final int V3 = 3;
    public static final int V4 = 4;
    public static final int V5 = 5;

    public static final String[] AIM = {
            "scale_vs_baseline",
            "yaw_std_rel",
            "pitch_mean_rel",
            "pitch_std_rel",
            "yaw_accel_rel",
            "pitch_accel_rel",
            "pitch_span_rel",
            "smoothness",
            "snap_ratio",
            "zero_ratio",
            "axis_correlation",
            "quanta_per_tick",
            "quantum_error",
            "attacks_per_second",
            "aim_error_mean",
            "aim_error_std",
            "aim_error_min",
    };

    public static final String[] CRIT = {
            "crit_rate",
            "crit_fall_mean",
            "crit_fall_min",
            "crit_air_ticks_mean",
            "crit_air_ticks_std",
            "crit_air_ticks_mode_share",
            "crit_fall_quantum_error",
            "crit_impossible_ratio",
            "crit_aim_error_delta",
    };

    public static final String[] HIT = {
            "crit_rate",
            "crit_share",
            "crit_streak_share",
            "crit_fall_mean",
            "crit_fall_min",
            "crit_air_ticks_mean",
            "crit_air_ticks_std",
            "crit_air_ticks_mode_share",
            "crit_fall_quantum_error",
            "crit_impossible_ratio",
            "crit_aim_error_delta",
            "hit_interval_mean",
            "hit_interval_std",
            "sprint_release_share",
    };

    public static final String[] AIM_V5 = aimWithout("attacks_per_second");

    private static String[] aimWithout(String dropped) {
        List<String> kept = new ArrayList<>();
        for (String name : AIM) {
            if (!name.equals(dropped)) {
                kept.add(name);
            }
        }
        return kept.toArray(new String[0]);
    }

    private FeatureSchemas() {
    }

    public static List<String> names(int version) {
        if (version == V5) {
            List<String> names = new ArrayList<>(List.of(AIM_V5));
            names.addAll(List.of(HIT));
            for (String name : AIM_V5) {
                names.add(name + "_run_mean");
            }
            for (String name : AIM_V5) {
                names.add(name + "_run_std");
            }
            return names;
        }

        List<String> names = new ArrayList<>(List.of(AIM));
        if (version == V3) {
            names.addAll(List.of(CRIT));
        } else if (version == V4) {
            for (String name : AIM) {
                names.add(name + "_run_mean");
            }
            for (String name : AIM) {
                names.add(name + "_run_std");
            }
        }
        return names;
    }

    public static boolean known(int version) {
        return version == V2 || version == V3 || version == V4 || version == V5;
    }

    public static Map<String, Double> slice(Map<String, Double> features, int version) {
        Map<String, Double> cut = new LinkedHashMap<>();
        for (String name : names(version)) {
            Double value = features.get(name);
            cut.put(name, value == null ? 0.0D : value);
        }
        return cut;
    }
}
