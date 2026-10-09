package me.everyone.yuppyai.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CritTiming {

    private static final double[] FALL_LADDER = ladder();

    private static final int MAX_FALL_TICKS = 60;
    private static final double MIN_FALL = 1.0E-4D;

    private final List<Double> fallDistances = new ArrayList<>();
    private final List<Double> airTicks = new ArrayList<>();
    private final List<Double> critAimErrors = new ArrayList<>();
    private final List<Double> groundAimErrors = new ArrayList<>();
    private final List<Double> intervals = new ArrayList<>();
    private int shaped;
    private int impossible;
    private int swings;
    private int crits;
    private int critAfterCrit;
    private int sprintReleases;

    private CritTiming() {
    }

    public static CritTiming of(List<AttackMoment> history) {
        CritTiming timing = new CritTiming();
        if (history == null) {
            return timing;
        }
        AttackMoment previous = null;
        for (AttackMoment moment : history) {
            if (moment == null) {
                continue;
            }
            timing.swings++;
            if (moment.intervalMs() >= 0L) {
                timing.intervals.add((double) moment.intervalMs());
            }
            if (moment.sprintReleased()) {
                timing.sprintReleases++;
            }
            if (moment.crit()) {
                timing.crits++;
                if (previous != null && previous.crit()) {
                    timing.critAfterCrit++;
                }
            }
            previous = moment;
            if (moment.fallDistance() > 0.0F) {
                timing.shaped++;
                if (moment.impossibleCrit()) {
                    timing.impossible++;
                }
            }
            if (moment.crit()) {
                timing.fallDistances.add((double) moment.fallDistance());
                timing.airTicks.add((double) moment.airTicks());
                if (!Double.isNaN(moment.aimError())) {
                    timing.critAimErrors.add(moment.aimError());
                }
            } else if (!Double.isNaN(moment.aimError())) {
                timing.groundAimErrors.add(moment.aimError());
            }
        }
        return timing;
    }

    public double fallMean() {
        if (fallDistances.isEmpty()) {
            return 0.0D;
        }
        List<Double> logged = new ArrayList<>(fallDistances.size());
        for (double fall : fallDistances) {
            logged.add(Math.log1p(fall));
        }
        return FeatureExtractor.mean(logged);
    }

    public double fallMin() {
        double smallest = Double.MAX_VALUE;
        for (double fall : fallDistances) {
            smallest = Math.min(smallest, fall);
        }
        return fallDistances.isEmpty() ? 0.0D : smallest;
    }

    public double airTicksMean() {
        return FeatureExtractor.mean(airTicks);
    }

    public double airTicksStd() {
        return FeatureExtractor.standardDeviation(airTicks, airTicksMean());
    }

    public double airTicksModeShare() {
        if (airTicks.isEmpty()) {
            return 0.0D;
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int best = 0;
        for (double ticks : airTicks) {
            int bucket = (int) Math.round(ticks);
            int seen = counts.merge(bucket, 1, Integer::sum);
            best = Math.max(best, seen);
        }
        return best / (double) airTicks.size();
    }

    public double fallQuantumError() {
        if (fallDistances.isEmpty()) {
            return 0.0D;
        }
        List<Double> errors = new ArrayList<>(fallDistances.size());
        for (double fall : fallDistances) {
            if (fall < MIN_FALL) {
                errors.add(1.0D);
                continue;
            }
            double nearest = Double.MAX_VALUE;
            for (double rung : FALL_LADDER) {
                nearest = Math.min(nearest, Math.abs(fall - rung));
            }
            errors.add(Math.min(1.0D, nearest / fall));
        }
        return FeatureExtractor.mean(errors);
    }

    public double impossibleRatio() {
        return shaped == 0 ? 0.0D : impossible / (double) shaped;
    }

    public double aimErrorDelta() {
        if (critAimErrors.isEmpty() || groundAimErrors.isEmpty()) {
            return 0.0D;
        }
        return FeatureExtractor.mean(critAimErrors) - FeatureExtractor.mean(groundAimErrors);
    }

    public double intervalMean() {
        return FeatureExtractor.mean(intervals);
    }

    public double intervalStd() {
        return FeatureExtractor.standardDeviation(intervals, intervalMean());
    }

    public double critStreakShare() {
        return crits == 0 ? 0.0D : critAfterCrit / (double) crits;
    }

    public double critShare() {
        return swings == 0 ? 0.0D : crits / (double) swings;
    }

    public double sprintReleaseShare() {
        return swings == 0 ? 0.0D : sprintReleases / (double) swings;
    }

    public Map<String, Double> toMap(double windowCritRate) {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("crit_rate", windowCritRate);
        map.put("crit_share", critShare());
        map.put("crit_streak_share", critStreakShare());
        map.put("crit_fall_mean", fallMean());
        map.put("crit_fall_min", fallMin());
        map.put("crit_air_ticks_mean", airTicksMean());
        map.put("crit_air_ticks_std", airTicksStd());
        map.put("crit_air_ticks_mode_share", airTicksModeShare());
        map.put("crit_fall_quantum_error", fallQuantumError());
        map.put("crit_impossible_ratio", impossibleRatio());
        map.put("crit_aim_error_delta", aimErrorDelta());
        map.put("hit_interval_mean", intervalMean());
        map.put("hit_interval_std", intervalStd());
        map.put("sprint_release_share", sprintReleaseShare());
        return map;
    }

    private static double[] ladder() {
        List<Double> rungs = new ArrayList<>();
        for (double initial : new double[]{0.42D, 0.0D}) {
            double velocity = initial;
            double fallen = 0.0D;
            for (int tick = 0; tick < MAX_FALL_TICKS; tick++) {
                velocity = (velocity - 0.08D) * 0.98D;
                if (velocity < 0.0D) {
                    fallen += -velocity;
                    rungs.add(fallen);
                }
            }
        }
        double[] values = new double[rungs.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = rungs.get(i);
        }
        return values;
    }
}
