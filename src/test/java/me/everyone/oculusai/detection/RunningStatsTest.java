package me.everyone.oculusai.detection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("a decaying horizon still starts as its own mean")
    void decayingSingleWindow() {
        RunningStats stats = new RunningStats(NAMES, 15);
        stats.add(new double[]{3.0D, -1.5D});
        Map<String, Double> map = stats.toMap();

        assertEquals(3.0D, map.get("a_run_mean"), 1.0E-12D);
        assertEquals(-1.5D, map.get("b_run_mean"), 1.0E-12D);
        assertEquals(0.0D, map.get("a_run_std"), 1.0E-12D);
    }

    @Test
    @DisplayName("a decaying horizon matches the exponential average the long way round")
    void decayingMatchesDirectArithmetic() {
        double[][] windows = {{1.0D, 10.0D}, {2.0D, 14.0D}, {6.0D, 12.0D}, {3.0D, 20.0D}};
        int horizon = 3;
        double alpha = 2.0D / (horizon + 1.0D);
        RunningStats stats = new RunningStats(NAMES, horizon);
        for (double[] window : windows) {
            stats.add(window);
        }
        Map<String, Double> map = stats.toMap();

        for (int column = 0; column < NAMES.length; column++) {
            double mean = windows[0][column];
            double variance = 0.0D;
            for (int i = 1; i < windows.length; i++) {
                double delta = windows[i][column] - mean;
                mean += alpha * delta;
                variance = (1.0D - alpha) * (variance + alpha * delta * delta);
            }
            assertEquals(mean, map.get(NAMES[column] + "_run_mean"), 1.0E-9D);
            assertEquals(Math.sqrt(variance), map.get(NAMES[column] + "_run_std"), 1.0E-9D);
        }
    }

    @Test
    @DisplayName("old history stops deciding the mean once the horizon decays")
    void decayingForgetsTheAlibi() {
        // The point of the horizon: a long honest session must not be able to
        // swallow a change in behaviour. Cumulatively it does - after a
        // thousand windows at zero, twenty windows at one barely move the
        // mean - and that is how a cheat switched on late used to dissolve in
        // its own player's history.
        RunningStats cumulative = new RunningStats(NAMES, 0);
        RunningStats decaying = new RunningStats(NAMES, 15);
        for (int i = 0; i < 1000; i++) {
            cumulative.add(new double[]{0.0D, 0.0D});
            decaying.add(new double[]{0.0D, 0.0D});
        }
        for (int i = 0; i < 20; i++) {
            cumulative.add(new double[]{1.0D, 1.0D});
            decaying.add(new double[]{1.0D, 1.0D});
        }

        assertTrue(cumulative.toMap().get("a_run_mean") < 0.05D,
                "a cumulative mean is still dominated by the thousand quiet windows");
        assertTrue(decaying.toMap().get("a_run_mean") > 0.8D,
                "a decaying mean should have caught up with the recent twenty");
    }

    @Test
    @DisplayName("horizon zero is the old unbounded behaviour")
    void horizonZeroIsCumulative() {
        RunningStats explicit = new RunningStats(NAMES, 0);
        RunningStats byDefault = new RunningStats(NAMES);
        for (double value : new double[]{1.0D, 7.0D, 3.0D}) {
            explicit.add(new double[]{value, value});
            byDefault.add(new double[]{value, value});
        }

        assertEquals(0, explicit.horizon());
        assertEquals(byDefault.toMap(), explicit.toMap());
    }

    @Test
    @DisplayName("both schemas name the same columns, in the order the service expects")
    void schemasMatchTheService() {
        List<String> columns = FeatureSchemas.names(FeatureSchemas.COLUMNS);
        // 16 колонок вращения, 14 удара, затем бегущие среднее и разброс по
        // колонкам вращения - по 16 каждое.
        assertEquals(62, columns.size());
        assertEquals("scale_vs_baseline", columns.get(0));
        assertEquals("crit_rate", columns.get(16));
        assertEquals("scale_vs_baseline_run_mean", columns.get(30));
        assertEquals("aim_error_min_run_std", columns.get(61));

        // Схемы различаются не колонками, а тем, что лежит под строкой:
        // v2 требует сырых тиков, v1 - нет.
        assertEquals(columns, FeatureSchemas.names(FeatureSchemas.TICKS));
        assertFalse(FeatureSchemas.needsTicks(FeatureSchemas.COLUMNS));
        assertTrue(FeatureSchemas.needsTicks(FeatureSchemas.TICKS));
        assertTrue(FeatureSchemas.known(FeatureSchemas.COLUMNS));
        assertTrue(FeatureSchemas.known(FeatureSchemas.TICKS));
        assertFalse(FeatureSchemas.known(2));
    }

    @Test
    @DisplayName("slicing takes columns by name, not by position")
    void slicingIsByName() {
        Map<String, Double> everything = new LinkedHashMap<>();
        for (String name : FeatureSchemas.names(FeatureSchemas.COLUMNS)) {
            everything.put(name, 1.0D);
        }
        everything.put("attacks_per_second", 9.0D);

        Map<String, Double> sliced = FeatureSchemas.slice(everything, FeatureSchemas.COLUMNS);
        assertEquals(62, sliced.size());
        assertEquals(1.0D, sliced.get("scale_vs_baseline"));
        assertEquals(1.0D, sliced.get("scale_vs_baseline_run_mean"));
        // Выброшенная колонка не возвращается, даже если её кто-то прислал.
        assertEquals(null, sliced.get("attacks_per_second"));

        Map<String, Double> missing = FeatureSchemas.slice(Map.of(), FeatureSchemas.COLUMNS);
        assertEquals(62, missing.size());
        assertEquals(0.0D, missing.get("smoothness"));
    }
}
