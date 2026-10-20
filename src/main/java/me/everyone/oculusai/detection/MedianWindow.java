package me.everyone.oculusai.detection;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/** The median of the last few readings.
 *
 * <p>A window of analysis is half a second of combat and can hold a single
 * swing, and with one swing several columns are degenerate - aim_error_std is
 * identically zero, hit_interval_std has nothing to measure - so an individual
 * probability wobbles in a way the player's behaviour does not. Replaying every
 * recorded cheating session through the live model, the mean step between
 * consecutive windows is 0.035; three windows of median halve it to 0.020 and
 * all 47 sessions still reach the kick threshold at the same median of five
 * seconds. Seven windows calm it further and start costing half a second of
 * latency, which is why the default is three.
 *
 * <p>Median rather than mean deliberately: one window reading 0.05 in the
 * middle of a cheat should not be able to drag the answer below the suspicion
 * line, and against a mean it can.
 */
public final class MedianWindow {

    private final Deque<Double> recent = new ArrayDeque<>();
    private final int size;

    /** ``size`` of 0 or 1 means no smoothing: {@link #add} returns its input. */
    public MedianWindow(int size) {
        this.size = Math.max(1, size);
    }

    public int size() {
        return size;
    }

    public synchronized double add(double value) {
        if (size <= 1) {
            return value;
        }
        recent.addLast(value);
        while (recent.size() > size) {
            recent.pollFirst();
        }
        double[] sorted = new double[recent.size()];
        int at = 0;
        for (double entry : recent) {
            sorted[at++] = entry;
        }
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        // An even count averages the two middles, which only happens while the
        // window is still filling up.
        return sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2.0D;
    }

    public synchronized void reset() {
        recent.clear();
    }
}
