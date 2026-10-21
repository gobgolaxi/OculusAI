package me.everyone.oculusai.detection;

/** The arithmetic that turns model readings into a suspicion buffer.
 *
 * <p>Readings above the suspicion line add evidence to the buffer; readings
 * below it subtract their own distance. That subtraction alone is not enough
 * to clear a short burst: at a probability just under the line the distance
 * is almost zero, so a buffer filled in two seconds can sit above the alert
 * threshold for the rest of the fight. Every later honest window then reports
 * a low probability next to a high buffer - visible as a detection that the
 * current reading does not support.
 *
 * <p>So a below-the-line reading also drops a share of the buffer itself
 * ({@code leak}). The leak does not touch the growth path: evidence above the
 * line accumulates exactly as before, which keeps the tuned detection speed.
 * It only guarantees that when the evidence stops, the buffer decays even if
 * the next reading lands right on the suspicion line.
 */
public final class SuspicionBuffer {

    private SuspicionBuffer() {
    }

    /** One reading applied to the buffer. {@code leak} is the fraction of the
     *  buffer dropped by a reading at or below the suspicion line, 0..0.95;
     *  {@code decay} is the old per-reading subtraction, kept so existing
     *  tuning still means what it meant. */
    public static double add(double buffer, double probability, double suspicion,
                             double gain, double decay, double leak, double max) {
        double distance = probability - suspicion;
        double next = buffer;
        if (distance > 0.0D) {
            next += distance * gain;
        } else {
            next += distance * decay;
            next *= 1.0D - clamp(leak, 0.0D, 0.95D);
        }
        if (Double.isNaN(next) || next < 0.0D) {
            next = 0.0D;
        }
        return Math.min(Math.max(0.1D, max), next);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
