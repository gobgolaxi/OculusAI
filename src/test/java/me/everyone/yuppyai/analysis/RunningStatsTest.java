package me.everyone.yuppyai.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("running session statistics")
class RunningStatsTest {

    private static final String[] NAMES = {"a", "b"};

    @Test
    @DisplayName("an untouched set of statistics is all zeroes")
    void coldStartIsZero() {
        Map<String, Double> map = new RunningStats(NAMES).toMap();

        assertEquals(4, map.size());
        assertEquals(0.0D, map.get("a_run_mean"));
        assertEquals(0.0D, map.get("a_run_std"));
        assertEquals(0.0D, map.get("b_run_mean"));
        assertEquals(0.0D, map.get("b_run_std"));
    }

    @Test
    @DisplayName("one window is its own mean and has no spread")
    void singleWindow() {
        RunningStats stats = new RunningStats(NAMES);
        stats.add(new double[]{3.0D, -1.5D});
        Map<String, Double> map = stats.toMap();

        assertEquals(3.0D, map.get("a_run_mean"), 1.0E-12D);
        assertEquals(-1.5D, map.get("b_run_mean"), 1.0E-12D);
        assertEquals(0.0D, map.get("a_run_std"), 1.0E-12D);
    }

    @Test
    @DisplayName("mean and population std match the long way round")
    void matchesDirectArithmetic() {
        double[][] windows = {{1.0D, 10.0D}, {2.0D, 14.0D}, {6.0D, 12.0D}, {3.0D, 20.0D}};
        RunningStats stats = new RunningStats(NAMES);
        for (double[] window : windows) {
            stats.add(window);
        }
        Map<String, Double> map = stats.toMap();

        for (int column = 0; column < NAMES.length; column++) {
            double mean = 0.0D;
            for (double[] window : windows) {
                mean += window[column];
            }
            mean /= windows.length;
            double variance = 0.0D;
            for (double[] window : windows) {
                variance += Math.pow(window[column] - mean, 2);
            }
            variance /= windows.length;

            assertEquals(mean, map.get(NAMES[column] + "_run_mean"), 1.0E-9D);
            assertEquals(Math.sqrt(variance), map.get(NAMES[column] + "_run_std"), 1.0E-9D);
        }
    }

    @Test
    @DisplayName("a five-figure column keeps its precision over a long session")
    void staysPreciseOnLargeValues() {
        RunningStats stats = new RunningStats(new String[]{"q"});
        for (int i = 0; i < 5000; i++) {
            stats.add(new double[]{56000.0D + (i % 2 == 0 ? 0.5D : -0.5D)});
        }
        Map<String, Double> map = stats.toMap();

        assertEquals(56000.0D, map.get("q_run_mean"), 1.0E-6D);
        assertEquals(0.5D, map.get("q_run_std"), 1.0E-6D);
    }

    @Test
    @DisplayName("NaN and infinity are skipped rather than poisoning the mean")
    void ignoresNonFinite() {
        RunningStats stats = new RunningStats(NAMES);
        stats.add(new double[]{2.0D, Double.NaN});
        stats.add(new double[]{4.0D, Double.POSITIVE_INFINITY});
        Map<String, Double> map = stats.toMap();

        assertEquals(3.0D, map.get("a_run_mean"), 1.0E-12D);
        assertTrue(Double.isFinite(map.get("b_run_mean")));
    }

    @Test
    @DisplayName("a window of the wrong width is refused, not folded in")
    void refusesWrongWidth() {
        RunningStats stats = new RunningStats(NAMES);
        stats.add(new double[]{1.0D});
        stats.add(new double[]{1.0D, 2.0D, 3.0D});

        assertEquals(0L, stats.count());
    }

    @Test
    @DisplayName("every schema names the columns the service expects")
    void schemasMatchTheService() {
        assertEquals(17, FeatureSchemas.names(FeatureSchemas.V2).size());
        assertEquals(26, FeatureSchemas.names(FeatureSchemas.V3).size());
        assertEquals(51, FeatureSchemas.names(FeatureSchemas.V4).size());

        List<String> v4 = FeatureSchemas.names(FeatureSchemas.V4);
        assertEquals(FeatureSchemas.names(FeatureSchemas.V2), v4.subList(0, 17));
        assertEquals("scale_vs_baseline_run_mean", v4.get(17));
        assertEquals("aim_error_min_run_std", v4.get(50));
    }

    @Test
    @DisplayName("slicing takes columns by name, and v4 is not a prefix")
    void slicingIsByName() {
        Map<String, Double> everything = new LinkedHashMap<>();
        for (String name : FeatureSchemas.names(FeatureSchemas.V3)) {
            everything.put(name, 1.0D);
        }
        for (String name : FeatureSchemas.names(FeatureSchemas.V4)) {
            everything.putIfAbsent(name, 2.0D);
        }

        Map<String, Double> v4 = FeatureSchemas.slice(everything, FeatureSchemas.V4);
        assertEquals(51, v4.size());
        assertEquals(1.0D, v4.get("scale_vs_baseline"));
        assertEquals(2.0D, v4.get("scale_vs_baseline_run_mean"));
        assertEquals(null, v4.get("crit_rate"));

        Map<String, Double> missing = FeatureSchemas.slice(Map.of(), FeatureSchemas.V2);
        assertEquals(17, missing.size());
        assertEquals(0.0D, missing.get("smoothness"));
    }
}
