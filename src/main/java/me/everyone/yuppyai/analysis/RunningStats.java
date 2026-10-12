package me.everyone.yuppyai.analysis;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The running mean and spread of each aim column over a player's session.
 *
 * <p>These columns carry most of what the model reads - drop them and the
 * leave-one-player-out AUC falls from 0.927 to 0.779 - so how far back they
 * look decides how fast a cheat can be caught at all.
 *
 * <p>The first version averaged from the moment the player joined and never
 * forgot anything, which let a player build their own alibi: every honest
 * minute added weight to the mean, and after a thousand windows a new one
 * moved it by a tenth of a percent. Replaying one capture through the live
 * model both ways makes the cost plain - the same 123 cheating windows score
 * 0.909 from a fresh join and kick in five seconds, but 0.433 behind three
 * minutes of that player's own honest combat, where the buffer never reaches
 * the kick threshold at all. The cheat does not hide; it dissolves in the
 * history.
 *
 * <p>So the horizon decays instead. ``horizon`` is the period of an
 * exponential average - roughly how many recent windows still matter - and
 * the same replay at a horizon of fifteen scores 0.854 and kicks seven
 * seconds in, with no new false alarms across the recorded legit sessions. A
 * horizon of zero restores the old unbounded behaviour. */
public final class RunningStats {

    private final String[] names;
    private final double[] means;
    private final double[] squaredDistance;
    private final int horizon;
    private final double alpha;
    private long count;

    public RunningStats(String[] names) {
        this(names, 0);
    }

    public RunningStats(String[] names, int horizon) {
        this.names = names.clone();
        this.means = new double[names.length];
        this.squaredDistance = new double[names.length];
        this.horizon = Math.max(0, horizon);
        this.alpha = this.horizon > 0 ? 2.0D / (this.horizon + 1.0D) : 0.0D;
    }

    public synchronized void add(double[] values) {
        if (values.length != means.length) {
            return;
        }
        count++;
        if (horizon > 0) {
            addDecaying(values);
            return;
        }
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

    /** The first window has nothing to average against, so it becomes the mean
     *  outright with zero spread - the same thing the cumulative form does on
     *  its first sample, and what the model was trained to see at the start of
     *  a session. */
    private void addDecaying(double[] values) {
        boolean first = count == 1;
        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            if (first) {
                means[i] = value;
                squaredDistance[i] = 0.0D;
                continue;
            }
            double delta = value - means[i];
            means[i] += alpha * delta;
            squaredDistance[i] = (1.0D - alpha) * (squaredDistance[i] + alpha * delta * delta);
        }
    }

    public synchronized long count() {
        return count;
    }

    public int horizon() {
        return horizon;
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
            // The decaying form already carries the variance; the cumulative
            // one keeps a sum of squared distances and divides at the end.
            double variance;
            if (count == 0) {
                variance = 0.0D;
            } else if (horizon > 0) {
                variance = squaredDistance[i];
            } else {
                variance = squaredDistance[i] / count;
            }
            map.put(names[i] + "_run_std", Math.sqrt(Math.max(0.0D, variance)));
        }
        return map;
    }
}
