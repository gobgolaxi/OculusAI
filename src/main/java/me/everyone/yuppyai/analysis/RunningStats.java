package me.everyone.yuppyai.analysis;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RunningStats {

    private final String[] names;
    private final double[] means;
    private final double[] squaredDistance;
    private long count;

    public RunningStats(String[] names) {
        this.names = names.clone();
        this.means = new double[names.length];
        this.squaredDistance = new double[names.length];
    }

    public synchronized void add(double[] values) {
        if (values.length != means.length) {
            return;
        }
        count++;
        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            double delta = value - means[i];
            means[i] += delta / count;
            squaredDistance[i] += delta * (value - means[i]);
        }
    }

    public synchronized long count() {
        return count;
    }

    public synchronized void reset() {
        count = 0;
        Arrays.fill(means, 0.0D);
        Arrays.fill(squaredDistance, 0.0D);
    }

    public synchronized Map<String, Double> toMap() {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            map.put(names[i] + "_run_mean", count == 0 ? 0.0D : means[i]);
        }
        for (int i = 0; i < names.length; i++) {
            double variance = count > 0 ? squaredDistance[i] / count : 0.0D;
            map.put(names[i] + "_run_std", Math.sqrt(Math.max(0.0D, variance)));
        }
        return map;
    }
}
