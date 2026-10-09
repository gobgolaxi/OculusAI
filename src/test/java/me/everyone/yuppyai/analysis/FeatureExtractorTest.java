package me.everyone.yuppyai.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureExtractorTest {

    private static final String[] RATIO_FEATURES = {
            "yaw_std_rel", "pitch_mean_rel", "pitch_std_rel",
            "yaw_accel_rel", "pitch_accel_rel", "pitch_span_rel"};

    private static RotationSample sample(double yaw, double pitch) {
        return new RotationSample((float) yaw, (float) pitch, false, Double.NaN);
    }

    private static List<RotationSample> movingWindow(int ticks) {
        List<RotationSample> window = new ArrayList<>(ticks);
        double yaw = 0.0D;
        double pitch = 0.0D;
        for (int i = 0; i < ticks; i++) {
            window.add(sample(yaw, pitch));
            yaw += 2.5D + (i % 3);
            pitch += 0.4D * (i % 2 == 0 ? 1 : -1);
        }
        return window;
    }

    private static List<RotationSample> stillThenFlickWindow(int ticks, int stillTicks) {
        List<RotationSample> window = new ArrayList<>(ticks);
        for (int i = 0; i < ticks; i++) {
            window.add(sample(0.0D, 0.0D));
        }
        double yaw = 0.0D;
        double pitch = 0.0D;
        for (int i = stillTicks; i < ticks; i++) {
            window.set(i, sample(yaw, pitch));
            yaw += 0.8D;
            pitch += 0.2D;
        }
        return window;
    }

    @Test
    @DisplayName("a window that never moved is refused instead of scored")
    void stillWindowIsRefused() {
        assertNull(FeatureExtractor.extract(stillThenFlickWindow(20, 18), 1.0D));
        assertNull(FeatureExtractor.extract(List.of(sample(1, 1), sample(1, 1), sample(1, 1)), 1.0D));
    }

    @Test
    @DisplayName("a refused window is exactly the one whose median delta collapsed")
    void refusalMatchesZeroRatio() {
        for (int stillTicks = 10; stillTicks <= 19; stillTicks++) {
            List<RotationSample> window = stillThenFlickWindow(20, stillTicks);
            boolean medianIsZero = (20 - 1 - stillTicks) < 10;
            FeatureVector vector = FeatureExtractor.extract(window, 1.0D);
            if (medianIsZero) {
                assertNull(vector, "stillTicks=" + stillTicks + " leaves a zero median");
            } else {
                assertNotNull(vector, "stillTicks=" + stillTicks + " leaves a real median");
            }
        }
    }

    @Test
    @DisplayName("no window that is scored carries a divided-by-nothing value")
    void ratioFeaturesStayBounded() {
        for (int ticks = 3; ticks <= 40; ticks++) {
            for (int stillTicks = 0; stillTicks < ticks; stillTicks += 3) {
                List<RotationSample> window = stillThenFlickWindow(ticks, stillTicks);
                FeatureVector vector = FeatureExtractor.extract(window, 1.0D);
                if (vector == null) {
                    continue;
                }
                Map<String, Double> features = vector.toMap();
                for (String name : RATIO_FEATURES) {
                    double value = features.get(name);
                    assertTrue(Double.isFinite(value), name + " is not finite");
                    assertTrue(value < 1.0E3D,
                            name + " blew up to " + value + " for ticks=" + ticks
                                    + " stillTicks=" + stillTicks);
                }
            }
        }
    }

    @Test
    @DisplayName("an ordinary window still produces a usable vector")
    void movingWindowIsScored() {
        FeatureVector vector = FeatureExtractor.extract(movingWindow(20), 2.0D);

        assertNotNull(vector);
        Map<String, Double> features = vector.toMap();
        assertEquals(17, features.size());
        assertTrue(features.get("yaw_std_rel") > 0.0D);
        assertTrue(features.get("quanta_per_tick") > 0.0D);
        assertTrue(features.get("scale_vs_baseline") > 0.0D);
    }

    @Test
    @DisplayName("the refusal depends on the median, not on the baseline")
    void baselineDoesNotRescueAStillWindow() {
        List<RotationSample> window = stillThenFlickWindow(20, 18);

        assertNull(FeatureExtractor.extract(window, 0.0D));
        assertNull(FeatureExtractor.extract(window, 1.0D));
        assertNull(FeatureExtractor.extract(window, 1.0E9D));
    }
}
