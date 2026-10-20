package me.everyone.oculusai.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("journal survives a restart")
class JournalPersistenceTest {

    private static final String UUID_ONE = "11111111-1111-4111-8111-111111111111";

    private static JsonObject parse(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    private static String written() {
        return "{\"entries\":[{"
                + "\"uuid\":\"" + UUID_ONE + "\","
                + "\"name\":\"Hokuso17\","
                + "\"first_seen_at\":1000,"
                + "\"last_seen_at\":5000,"
                + "\"peak_buffer\":7.25,"
                + "\"last_buffer\":3.5,"
                + "\"last_probability\":0.82,"
                + "\"windows\":412,"
                + "\"history\":[{\"t\":4000,\"p\":0.7,\"b\":2.5},"
                + "{\"t\":5000,\"p\":0.82,\"b\":3.5}]}]}";
    }

    @Test
    @DisplayName("everything the panel shows comes back from disk")
    void readsBackEveryField() {
        Map<UUID, JournalStore.Entry> entries = JournalStore.decode(parse(written()));

        assertEquals(1, entries.size());
        JournalStore.Entry entry = entries.get(UUID.fromString(UUID_ONE));
        assertEquals("Hokuso17", entry.name());
        assertEquals(1000L, entry.firstSeenAt());
        assertEquals(5000L, entry.lastSeenAt());
        assertEquals(7.25D, entry.peakBuffer(), 1e-9);
        assertEquals(3.5D, entry.lastBuffer(), 1e-9);
        assertEquals(0.82D, entry.lastProbability(), 1e-9);
        assertEquals(412, entry.windowsAnalysed());
        assertEquals(2, entry.history().size());
        assertEquals(0.7D, entry.history().get(0).probability(), 1e-9);
    }

    @Test
    @DisplayName("writing what was read gives the same file back")
    void roundTripsUnchanged() {
        JsonObject once = JournalStore.encode(
                JournalStore.decode(parse(written())).values());
        JsonObject twice = JournalStore.encode(
                JournalStore.decode(once).values());

        assertEquals(once.toString(), twice.toString());
    }

    @Test
    @DisplayName("one broken row does not cost the whole journal")
    void skipsBrokenRows() {
        String json = "{\"entries\":[{\"uuid\":\"not-a-uuid\"},"
                + written().substring(written().indexOf("{\"uuid\"")) ;
        Map<UUID, JournalStore.Entry> entries = JournalStore.decode(parse(json));

        assertEquals(1, entries.size());
        assertTrue(entries.containsKey(UUID.fromString(UUID_ONE)));
    }

    @Test
    @DisplayName("an empty or missing file is an empty journal, not a crash")
    void emptyIsEmpty() {
        assertEquals(0, JournalStore.decode(parse("{}")).size());
        assertEquals(0, JournalStore.decode(null).size());
    }
}
