package me.everyone.oculusai.player;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A recorded window carries what the model said about it.
 *
 *  <p>The score and buffer columns have been in the upload format from the
 *  start and went out empty for as long as analysis stopped while recording.
 *  They are filled in a round trip after the row is written, which is the
 *  whole reason the epoch exists: between those two moments the capture may
 *  have been committed and cleared, and a late answer must not be written
 *  into whatever row now sits at that index. */
@DisplayName("recorded windows carry the model's answer")
class RecordedScoresTest {

    private static final List<String> NAMES = List.of("a", "b");

    private static PlayerData player() {
        UUID uuid = UUID.nameUUIDFromBytes("Recorded".getBytes(StandardCharsets.UTF_8));
        Player stub = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> "Recorded";
                    case "getEntityId" -> 1;
                    case "getLocation" -> new Location(null, 0, 0, 0);
                    case "isOnline" -> true;
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Recorded";
                    default -> null;
                });
        return new PlayerData(stub);
    }

    @Test
    @DisplayName("a row is written with no score and gets one later")
    void fillsInTheAnswer() {
        PlayerData data = player();
        int epoch = data.recordedEpoch();

        int first = data.record(NAMES, new double[]{1.0D, 2.0D});
        int second = data.record(NAMES, new double[]{3.0D, 4.0D});
        assertEquals(0, first);
        assertEquals(1, second);

        // Written down before the service has answered: a capture must not
        // depend on the network, so the row exists with an empty score.
        assertTrue(Double.isNaN(data.recordedContext().get(0)[0]),
                "a fresh row has no score yet");

        data.scoreRecorded(epoch, first, 0.91D, 4.2D);
        data.scoreRecorded(epoch, second, 0.12D, 0.5D);

        assertEquals(0.91D, data.recordedContext().get(0)[0], 1.0E-9D);
        assertEquals(4.2D, data.recordedContext().get(0)[1], 1.0E-9D);
        assertEquals(0.12D, data.recordedContext().get(1)[0], 1.0E-9D);
        assertEquals(2, data.recordedCount());
    }

    @Test
    @DisplayName("an answer that arrives after the capture was committed is dropped")
    void dropsAStaleAnswer() {
        PlayerData data = player();
        int epoch = data.recordedEpoch();
        int row = data.record(NAMES, new double[]{1.0D, 2.0D});

        // What a commit does: the rows are uploaded and the capture starts
        // over. The request for the last window is still in flight.
        data.clearRecorded();
        assertEquals(0, data.recordedCount());

        int reused = data.record(NAMES, new double[]{9.0D, 9.0D});
        assertEquals(row, reused, "the new capture reuses the index");

        data.scoreRecorded(epoch, row, 0.99D, 7.0D);

        assertTrue(Double.isNaN(data.recordedContext().get(0)[0]),
                "the late answer did not land on the new row");
        assertNotEquals(epoch, data.recordedEpoch(), "committing moved the epoch on");
    }

    @Test
    @DisplayName("an index that is no longer there is ignored")
    void ignoresAMissingRow() {
        PlayerData data = player();
        int epoch = data.recordedEpoch();
        data.record(NAMES, new double[]{1.0D, 2.0D});

        // A window measured while not recording reports -1 as its row, and
        // the answer for it arrives all the same.
        data.scoreRecorded(epoch, -1, 0.5D, 1.0D);
        data.scoreRecorded(epoch, 99, 0.5D, 1.0D);

        assertEquals(1, data.recordedCount());
        assertTrue(Double.isNaN(data.recordedContext().get(0)[0]));
    }
}
