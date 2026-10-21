package me.everyone.oculusai.detection;

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
        assertNull(FeatureExtractor.extract(stillThenFlickWindow(20, 20), 1.0D));
        assertNull(FeatureExtractor.extract(List.of(sample(1, 1), sample(1, 1), sample(1, 1)), 1.0D));
    }

    @Test
    @DisplayName("a sparse flick is scored even when most samples are still")
    void sparseFlickIsScored() {
        // A short fight can be one fast turn surrounded by still samples. The
        // median delta is zero there, but the window is not empty - refusing it
        // let a fast hit-and-turn go unanalysed.
        for (int stillTicks = 10; stillTicks <= 18; stillTicks++) {
            List<RotationSample> window = stillThenFlickWindow(20, stillTicks);
            assertNotNull(FeatureExtractor.extract(window, 1.0D),
                    "stillTicks=" + stillTicks + " has a real flick");
        }
    }

    @Test
    @DisplayName("a one-tick 180 degree flick produces a bounded vector")
    void fastFlickAmongStillSamples() {
        List<RotationSample> window = new ArrayList<>(20);
        for (int i = 0; i < 19; i++) {
            window.add(sample(0.0D, 0.0D));
        }
        window.add(sample(180.0D, 0.0D));

        FeatureVector vector = FeatureExtractor.extract(window, 1.0D);

        assertNotNull(vector);
        Map<String, Double> features = vector.toMap();
        assertTrue(features.get("snap_ratio") > 1.0D);
        assertTrue(features.get("zero_ratio") > 0.9D);
        for (String name : RATIO_FEATURES) {
            assertTrue(Double.isFinite(features.get(name)), name + " is not finite");
            assertTrue(features.get(name) < 1.0E3D, name + " blew up to " + features.get(name));
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
    @DisplayName("a still window is refused regardless of the baseline")
    void baselineDoesNotRescueAStillWindow() {
        List<RotationSample> window = stillThenFlickWindow(20, 20);

        assertNull(FeatureExtractor.extract(window, 0.0D));
        assertNull(FeatureExtractor.extract(window, 1.0D));
        assertNull(FeatureExtractor.extract(window, 1.0E9D));
    }
}
