package me.everyone.oculusai.detection.crystal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import me.everyone.oculusai.detection.RotationSample;
import org.junit.jupiter.api.Test;

class CrystalTimelineTest {

    private static RotationSample tick() {
        return new RotationSample(0.0F, 0.0F, false, Double.NaN);
    }

    @Test
    void aBurstOfEventsBecomesOneFullWindow() {
        CrystalTimeline timeline = new CrystalTimeline();
        timeline.recording(true);
        for (int i = 0; i < 25; i++) {
            timeline.onTick(tick());
        }
        timeline.placed(5.0D, 3.0D);
        timeline.onTick(tick());
        timeline.onTick(tick());
        timeline.broken(12.0D, 3.5D);
        timeline.slotSwitched();
        timeline.onTick(tick());
        for (int i = 0; i < 20; i++) {
            timeline.onTick(tick());
        }

        assertEquals(1, timeline.recorded());
        List<List<double[]>> drained = timeline.drain();
        double[] row = drained.get(0).get(0);
        double[] ticks = drained.get(1).get(0);
        assertEquals(CrystalSchema.WINDOW * CrystalSchema.TICK_CHANNELS.size(), ticks.length);
        // places, breaks, place->break ticks, place angle, break angle,
        // slot switches, dealt, taken, totems
        assertArrayEquals(new double[]{1, 1, 2, 5, 12, 1, 0, 0, 0}, row, 1.0e-9);
        assertEquals(0, timeline.recorded());
    }

    @Test
    void nothingIsWrittenWhileNotRecording() {
        CrystalTimeline timeline = new CrystalTimeline();
        for (int i = 0; i < 30; i++) {
            timeline.onTick(tick());
        }
        timeline.placed(5.0D, 3.0D);
        for (int i = 0; i < 20; i++) {
            timeline.onTick(tick());
        }
        assertEquals(0, timeline.recorded());
    }

    @Test
    void anEventBeforeAFullHistoryIsDropped() {
        CrystalTimeline timeline = new CrystalTimeline();
        timeline.recording(true);
        timeline.onTick(tick());
        timeline.placed(5.0D, 3.0D);
        for (int i = 0; i < 12; i++) {
            timeline.onTick(tick());
        }
        assertEquals(0, timeline.recorded());
    }

    @Test
    void schemaStartsWithTheAimChannels() {
        assertEquals(me.everyone.oculusai.detection.Detection.TICK_CHANNELS,
                CrystalSchema.TICK_CHANNELS.subList(0, me.everyone.oculusai.detection.Detection.TICK_CHANNELS.size()));
        assertEquals(26, CrystalSchema.TICK_CHANNELS.size());
    }
}
