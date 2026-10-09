package me.everyone.yuppyai.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("critical-hit timing")
class CritTimingTest {

    private static AttackMoment jumped(int airTicks, double aimError) {
        return new AttackMoment(aimError, (float) fallenAfter(airTicks), airTicks, false, false);
    }

    private static double fallenAfter(int airTicks) {
        double velocity = 0.42D;
        double fallen = 0.0D;
        for (int tick = 0; tick < airTicks; tick++) {
            velocity = (velocity - 0.08D) * 0.98D;
            if (velocity < 0.0D) {
                fallen += -velocity;
            }
        }
        return fallen;
    }

    private static AttackMoment grounded(double aimError) {
        return new AttackMoment(aimError, 0.0F, 0, true, false);
    }

    @Test
    @DisplayName("an empty history leaves every column at zero")
    void emptyHistoryIsNeutral() {
        CritTiming timing = CritTiming.of(List.of());

        assertEquals(0.0D, timing.fallMean());
        assertEquals(0.0D, timing.fallMin());
        assertEquals(0.0D, timing.airTicksMean());
        assertEquals(0.0D, timing.airTicksStd());
        assertEquals(0.0D, timing.airTicksModeShare());
        assertEquals(0.0D, timing.fallQuantumError());
        assertEquals(0.0D, timing.impossibleRatio());
        assertEquals(0.0D, timing.aimErrorDelta());
    }

    @Test
    @DisplayName("a fixed tick offset separates a forced crit from a jumped one")
    void metronomicTimingStandsOut() {
        List<AttackMoment> forced = new ArrayList<>();
        List<AttackMoment> human = new ArrayList<>();
        int[] humanTicks = {9, 11, 8, 13, 9, 8, 12, 10};
        for (int i = 0; i < humanTicks.length; i++) {
            forced.add(jumped(7, 1.0D));
            human.add(jumped(humanTicks[i], 1.0D));
        }

        CritTiming forcedTiming = CritTiming.of(forced);
        CritTiming humanTiming = CritTiming.of(human);

        assertEquals(0.0D, forcedTiming.airTicksStd());
        assertEquals(1.0D, forcedTiming.airTicksModeShare());
        assertTrue(humanTiming.airTicksStd() > 1.0D);
        assertTrue(humanTiming.airTicksModeShare() < 0.5D);
        assertTrue(forcedTiming.fallMin() < humanTiming.fallMin());
    }

    @Test
    @DisplayName("a fall distance off the physics ladder scores an error, a real one does not")
    void fallDistanceIsCheckedAgainstPhysics() {
        CortRealAndFake pair = new CortRealAndFake();

        assertTrue(pair.real.fallQuantumError() < 0.05D);
        assertTrue(pair.fake.fallQuantumError() > 0.3D);
    }

    private static final class CortRealAndFake {
        final CritTiming real;
        final CritTiming fake;

        CortRealAndFake() {
            List<AttackMoment> realHits = new ArrayList<>();
            List<AttackMoment> fakeHits = new ArrayList<>();
            for (int ticks = 6; ticks < 12; ticks++) {
                realHits.add(jumped(ticks, 1.0D));
                fakeHits.add(new AttackMoment(1.0D, 0.004F, ticks, false, false));
            }
            real = CritTiming.of(realHits);
            fake = CritTiming.of(fakeHits);
        }
    }

    @Test
    @DisplayName("a crit while sprinting or on the ground is counted as impossible")
    void impossibleCritsAreCounted() {
        List<AttackMoment> history = List.of(
                jumped(8, 1.0D),
                new AttackMoment(1.0D, 0.5F, 4, false, true),
                new AttackMoment(1.0D, 0.5F, 0, true, false),
                grounded(1.0D));

        CritTiming timing = CritTiming.of(history);

        assertEquals(2.0D / 3.0D, timing.impossibleRatio(), 1.0E-9D);
    }

    @Test
    @DisplayName("a swing in water or on a mount is not a crit")
    void blockedSwingsAreNotCrits() {
        AttackMoment inWater = new AttackMoment(1.0D, 0.5F, 9, false, false, true);
        assertFalse(inWater.crit());
        assertFalse(inWater.impossibleCrit());

        CritTiming timing = CritTiming.of(List.of(inWater, jumped(9, 1.0D)));
        assertEquals(9.0D, timing.airTicksMean(), 1.0E-9D);
        assertEquals(1.0D, timing.airTicksModeShare(), 1.0E-9D);
    }

    @Test
    @DisplayName("the aim error delta is zero until both kinds of hit exist")
    void aimErrorDeltaNeedsBothKinds() {
        assertEquals(0.0D, CritTiming.of(List.of(jumped(8, 4.0D))).aimErrorDelta());
        assertEquals(0.0D, CritTiming.of(List.of(grounded(1.0D))).aimErrorDelta());

        CritTiming mixed = CritTiming.of(List.of(jumped(8, 4.0D), grounded(1.0D)));
        assertEquals(3.0D, mixed.aimErrorDelta(), 1.0E-9D);
    }

    @Test
    @DisplayName("crit_rate comes from the window, every other column from the history")
    void windowAndHistoryAreSeparate() {
        List<RotationSample> window = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean attack = i == 5 || i == 15;
            AttackMoment moment = attack ? jumped(8, 1.5D) : null;
            window.add(new RotationSample(i * 3.0F, 10.0F + i * 0.5F, attack,
                    attack ? 1.5D : Double.NaN, moment));
        }

        assertEquals(1.0D, FeatureExtractor.critRate(window), 1.0E-9D);

        Map<String, Double> hit = CritTiming
                .of(List.of(jumped(8, 1.5D), jumped(8, 1.5D), grounded(0.5D)))
                .toMap(FeatureExtractor.critRate(window));

        assertEquals(14, hit.size());
        assertEquals(1.0D, hit.get("crit_rate"), 1.0E-9D);
        assertEquals(1.0D, hit.get("crit_air_ticks_mode_share"), 1.0E-9D);
        assertEquals(8.0D, hit.get("crit_air_ticks_mean"), 1.0E-9D);
        assertEquals(1.0D, hit.get("crit_aim_error_delta"), 1.0E-9D);
        assertEquals(0.0D, hit.get("crit_impossible_ratio"), 1.0E-9D);
        assertTrue(hit.get("crit_fall_min") > 0.0D);
        assertEquals(2.0D / 3.0D, hit.get("crit_share"), 1.0E-9D);
        assertEquals(0.5D, hit.get("crit_streak_share"), 1.0E-9D);
    }

    @Test
    @DisplayName("a window with no attacks keeps crit_rate at zero")
    void noAttacksMeansNoRate() {
        List<RotationSample> window = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            window.add(new RotationSample(i * 3.0F, 10.0F, false, Double.NaN));
        }

        assertEquals(0.0D, FeatureExtractor.critRate(window));
        assertEquals(0.0D, CritTiming.of(List.of()).toMap(0.0D).get("crit_rate"));
    }

    @Test
    @DisplayName("the rhythm of the swings is measured, not the window they fell in")
    void rhythmComesFromIntervals() {
        List<AttackMoment> metronome = new ArrayList<>();
        List<AttackMoment> hand = new ArrayList<>();
        long[] handGaps = {280L, 410L, 330L, 520L, 300L, 390L};
        for (int i = 0; i < handGaps.length; i++) {
            metronome.add(swing(350L, -1));
            hand.add(swing(handGaps[i], -1));
        }

        CritTiming metronomic = CritTiming.of(metronome);
        CritTiming human = CritTiming.of(hand);

        assertEquals(350.0D, metronomic.intervalMean(), 1.0E-9D);
        assertEquals(0.0D, metronomic.intervalStd(), 1.0E-9D);
        assertTrue(human.intervalStd() > 50.0D);
    }

    @Test
    @DisplayName("a sprint released right before the swing is counted")
    void sprintReleaseIsCounted() {
        CritTiming timing = CritTiming.of(List.of(
                swing(300L, 2),
                swing(300L, 0),
                swing(300L, 40),
                swing(300L, -1)));

        assertEquals(0.5D, timing.sprintReleaseShare(), 1.0E-9D);
    }

    private static AttackMoment swing(long intervalMs, int sprintDroppedTicks) {
        return new AttackMoment(1.0D, 0.0F, 0, true, false, false,
                intervalMs, sprintDroppedTicks);
    }
}
