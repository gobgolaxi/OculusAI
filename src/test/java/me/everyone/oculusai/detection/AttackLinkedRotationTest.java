package me.everyone.oculusai.detection;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("attack-linked rotation")
class AttackLinkedRotationTest {

    private static RotationSample sample(float yaw, boolean attacked) {
        return new RotationSample(yaw, 0.0F, attacked, Double.NaN);
    }

    @Test
    @DisplayName("a flick before the attack sample still counts")
    void flickBeforeHit() {
        // The client can send the look that sets up the hit before the attack
        // packet. The attack flag then lands one sample later, and looking only
        // at the marked sample left the flick out - a fast hit-and-turn was
        // never analysed.
        List<RotationSample> window = List.of(
                sample(0.0F, false),
                sample(35.0F, false),
                sample(35.0F, true));
        assertEquals(35.0D, Detection.attackLinkedRotation(window), 1.0E-9D);
    }

    @Test
    @DisplayName("a flick after the attack sample still counts")
    void flickAfterHit() {
        List<RotationSample> window = List.of(
                sample(0.0F, false),
                sample(0.0F, true),
                sample(30.0F, false));
        assertEquals(30.0D, Detection.attackLinkedRotation(window), 1.0E-9D);
    }

    @Test
    @DisplayName("rotation with no attack nearby is not counted")
    void noAttack() {
        List<RotationSample> window = List.of(
                sample(0.0F, false),
                sample(30.0F, false),
                sample(60.0F, false));
        assertEquals(0.0D, Detection.attackLinkedRotation(window), 1.0E-9D);
    }
}
