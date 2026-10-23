package me.everyone.oculusai.detection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("suspicion buffer arithmetic")
class SuspicionBufferTest {

    private static final double SUSPICION = 0.5D;
    private static final double GAIN = 1.5D;
    private static final double DECAY = 1.4D;
    private static final double LEAK = 0.4D;
    private static final double MAX = 10.0D;
    private static final double NO_CAP = 0.0D;

    private static double add(double buffer, double probability) {
        return SuspicionBuffer.add(buffer, probability, SUSPICION, GAIN, DECAY, LEAK, MAX, NO_CAP);
    }

    @Test
    @DisplayName("a burst of cheating builds the buffer exactly as before")
    void growthIsUnchanged() {
        // 0.9 is 0.4 above the line, gain 1.5 adds 0.6 per window. The leak
        // must not touch the evidence path, or the tuned detection speed moves.
        double buffer = 0.0D;
        for (int i = 0; i < 4; i++) {
            buffer = add(buffer, 0.90D);
        }
        assertEquals(2.4D, buffer, 1.0E-12D);
    }

    @Test
    @DisplayName("one honest window clears a two-second cheat burst")
    void shortBurstDoesNotOutliveTheCheat() {
        double buffer = 0.0D;
        for (int i = 0; i < 4; i++) {
            buffer = add(buffer, 0.90D);
        }
        assertEquals(2.4D, buffer, 1.0E-12D);
        // Without the leak this only fell to 1.84 - still above the 1.7 alert
        // threshold - and the first honest window was flagged.
        buffer = add(buffer, 0.10D);
        assertEquals(1.104D, buffer, 1.0E-12D);
    }

    @Test
    @DisplayName("a reading on the suspicion line still releases the buffer")
    void lineItselfLeaks() {
        // The old rule subtracted distance * decay. At exactly 0.5 that is
        // zero, so the buffer froze and the next fight inherited it.
        assertEquals(1.5D, add(2.5D, SUSPICION), 1.0E-12D);
    }

    @Test
    @DisplayName("a zero leak reproduces the old arithmetic")
    void leakCanBeDisabled() {
        double withOldRule = Math.max(0.0D, Math.min(MAX,
                2.5D + (0.10D - SUSPICION) * DECAY));
        assertEquals(withOldRule, SuspicionBuffer.add(2.5D, 0.10D, SUSPICION,
                GAIN, DECAY, 0.0D, MAX, NO_CAP), 1.0E-12D);
    }

    @Test
    @DisplayName("the cap limits one reading without touching the rest")
    void capLimitsASingleReading() {
        // 0.99 is 0.49 above the line and would add 0.735 uncapped.
        assertEquals(0.735D,
                SuspicionBuffer.add(0.0D, 0.99D, SUSPICION, GAIN, DECAY, LEAK, MAX, NO_CAP),
                1.0E-12D);
        assertEquals(0.4D,
                SuspicionBuffer.add(0.0D, 0.99D, SUSPICION, GAIN, DECAY, LEAK, MAX, 0.4D),
                1.0E-12D);
        // A step already under the cap is untouched, so the cap only ever
        // removes the spikes it was added for.
        assertEquals(0.15D,
                SuspicionBuffer.add(0.0D, 0.60D, SUSPICION, GAIN, DECAY, LEAK, MAX, 0.4D),
                1.0E-12D);
    }

    @Test
    @DisplayName("the cap does not interfere with release")
    void capLeavesDecayAlone() {
        assertEquals(add(2.5D, 0.10D),
                SuspicionBuffer.add(2.5D, 0.10D, SUSPICION, GAIN, DECAY, LEAK, MAX, 0.4D),
                1.0E-12D);
    }

    @Test
    @DisplayName("the buffer stays inside its bounds")
    void clamped() {
        assertEquals(MAX, add(MAX, 0.99D), 1.0E-12D);
        assertEquals(0.0D, add(0.0D, 0.01D), 1.0E-12D);
    }
}
