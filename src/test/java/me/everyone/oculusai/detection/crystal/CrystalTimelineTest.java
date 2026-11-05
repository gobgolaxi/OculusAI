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
        // slot switches, dealt, taken, totems, anchor charges, anchor
        // explosions, blast angle mean (-1: no look yet), unseen hits
        assertArrayEquals(new double[]{1, 1, 2, 5, 12, 1, 0, 0, 0, 0, 0, -1, 0}, row, 1.0e-9);
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
    void windowsGoToPredictionWithoutRecording() {
        CrystalTimeline timeline = new CrystalTimeline();
        java.util.List<double[]> sent = new java.util.ArrayList<>();
        timeline.sink((columns, ticks) -> sent.add(columns));
        for (int i = 0; i < 25; i++) {
            timeline.onTick(tick());
        }
        timeline.broken(3.0D, 2.0D);
        for (int i = 0; i < 15; i++) {
            timeline.onTick(tick());
        }
        assertEquals(1, sent.size());
        assertEquals(1.0D, sent.get(0)[1], 1.0e-9);
        // Без записи в датасет ничего не копится.
        assertEquals(0, timeline.recorded());
    }

    @Test
    void theCrystalBufferIsItsOwn() {
        CrystalTimeline timeline = new CrystalTimeline();
        for (int i = 0; i < 5; i++) {
            timeline.applyProbability(0.95D, 0.5D, 1.5D, 1.4D, 0.4D, 10.0D, 0.0D);
        }
        org.junit.jupiter.api.Assertions.assertTrue(timeline.buffer() > 3.0D, "buffer " + timeline.buffer());
        assertEquals(0.95D, timeline.probability(), 1.0e-9);
        timeline.resetBuffer();
        assertEquals(0.0D, timeline.buffer(), 1.0e-9);
    }

    @Test
    void aHitBeforeTheSpawnPacketIsAPrediction() throws Exception {
        CrystalTimeline timeline = new CrystalTimeline();
        timeline.spawned(100, true, 0, 64, 0);
        Thread.sleep(2);
        timeline.attackArrived(100);
        org.junit.jupiter.api.Assertions.assertFalse(timeline.hitBeforeSeen(100),
                "a crystal the client had been sent");

        timeline.attackArrived(101);
        Thread.sleep(2);
        timeline.spawned(101, true, 0, 64, 0);
        org.junit.jupiter.api.Assertions.assertTrue(timeline.hitBeforeSeen(101),
                "the spawn went out after the hit");

        timeline.attackArrived(102);
        org.junit.jupiter.api.Assertions.assertTrue(timeline.hitBeforeSeen(102),
                "never sent at all");
    }

    @Test
    void noSpawnsSeenMeansNoVerdict() {
        CrystalTimeline timeline = new CrystalTimeline();
        timeline.attackArrived(7);
        org.junit.jupiter.api.Assertions.assertFalse(timeline.hitBeforeSeen(7),
                "with no spawn packet ever seen the listener is not working");
    }

    @Test
    void theLookAtTheNearestCrystalIsWrittenEveryTick() {
        CrystalTimeline timeline = new CrystalTimeline();
        timeline.recording(true);
        // Кристалл в 4 блоках прямо по курсу (yaw 0 смотрит на +z).
        timeline.spawned(5, true, 0.0D, 63.0D, 4.0D);
        timeline.look("world", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        for (int i = 0; i < 25; i++) {
            timeline.onTick(tick());
        }
        timeline.placed(0.0D, 4.0D);
        for (int i = 0; i < 15; i++) {
            timeline.onTick(tick());
        }
        double[] ticks = timeline.drain().get(1).get(0);
        int channels = CrystalSchema.TICK_CHANNELS.size();
        int angleAt = CrystalSchema.TICK_CHANNELS.indexOf("blast_angle");
        int distanceAt = CrystalSchema.TICK_CHANNELS.indexOf("blast_distance");
        // Первый тик окна, без всякого события: наводка уже есть.
        assertEquals(0.0D, ticks[angleAt], 1.0e-6);
        assertEquals(4.0D, ticks[distanceAt], 1.0e-6);
        timeline.destroyed(5);
    }

    @Test
    void aChargedAnchorCountsAsABlastSource() {
        CrystalTimeline timeline = new CrystalTimeline();
        CrystalTimeline.anchorArmed("world", 2, 63, 0);
        try {
            timeline.recording(true);
            timeline.look("world", 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
            for (int i = 0; i < 25; i++) {
                timeline.onTick(tick());
            }
            timeline.anchorCharged(10.0D, 2.5D);
            for (int i = 0; i < 15; i++) {
                timeline.onTick(tick());
            }
            List<List<double[]>> drained = timeline.drain();
            double[] ticks = drained.get(1).get(0);
            int distanceAt = CrystalSchema.TICK_CHANNELS.indexOf("blast_distance");
            org.junit.jupiter.api.Assertions.assertTrue(ticks[distanceAt] > 2.5D && ticks[distanceAt] < 3.0D,
                    "distance " + ticks[distanceAt]);
            assertEquals(1.0D, drained.get(0).get(0)[CrystalSchema.COLUMNS.indexOf("anchor_charges")], 1.0e-9);
        } finally {
            CrystalTimeline.clearAnchors();
        }
    }

    @Test
    void schemaStartsWithTheAimChannels() {
        assertEquals(me.everyone.oculusai.detection.Detection.TICK_CHANNELS,
                CrystalSchema.TICK_CHANNELS.subList(0, me.everyone.oculusai.detection.Detection.TICK_CHANNELS.size()));
        assertEquals(32, CrystalSchema.TICK_CHANNELS.size());
        assertEquals(13, CrystalSchema.COLUMNS.size());
    }
}
