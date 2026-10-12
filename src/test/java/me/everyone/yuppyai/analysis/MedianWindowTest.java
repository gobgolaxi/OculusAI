package me.everyone.yuppyai.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("probability smoothing")
class MedianWindowTest {

    @Test
    @DisplayName("a window of one or zero keeps the raw reading")
    void passthrough() {
        MedianWindow one = new MedianWindow(1);
        assertEquals(0.42D, one.add(0.42D), 1.0E-12D);
        assertEquals(0.03D, one.add(0.03D), 1.0E-12D);

        assertEquals(1, new MedianWindow(0).size());
        assertEquals(0.42D, new MedianWindow(0).add(0.42D), 1.0E-12D);
    }

    @Test
    @DisplayName("one dropout mid-cheat does not reach the buffer")
    void ignoresOneDropout() {
        // The case this exists for: a window held a single swing, several
        // columns went degenerate, and the reading fell to near zero while the
        // player carried on cheating. A mean would pull the answer under the
        // 0.5 suspicion line and hand back the evidence; the median should not
        // notice at all.
        MedianWindow window = new MedianWindow(3);
        window.add(0.90D);
        window.add(0.88D);
        assertEquals(0.88D, window.add(0.05D), 1.0E-12D);
        assertEquals(0.88D, window.add(0.91D), 1.0E-12D);
    }

    @Test
    @DisplayName("it answers from fewer readings than the window holds")
    void coldStart() {
        MedianWindow window = new MedianWindow(3);
        assertEquals(0.80D, window.add(0.80D), 1.0E-12D);
        assertEquals(0.50D, window.add(0.20D), 1.0E-12D);
        assertEquals(0.20D, window.add(0.10D), 1.0E-12D);
    }

    @Test
    @DisplayName("a sustained change is followed, one window late")
    void followsRealChange() {
        MedianWindow window = new MedianWindow(3);
        window.add(0.10D);
        window.add(0.10D);
        assertEquals(0.10D, window.add(0.90D), 1.0E-12D);
        assertEquals(0.90D, window.add(0.90D), 1.0E-12D);
        assertEquals(0.90D, window.add(0.90D), 1.0E-12D);
    }

    @Test
    @DisplayName("a reset forgets the previous player's readings")
    void resetClears() {
        MedianWindow window = new MedianWindow(3);
        window.add(0.90D);
        window.add(0.90D);
        window.reset();
        assertEquals(0.10D, window.add(0.10D), 1.0E-12D);
    }
}
