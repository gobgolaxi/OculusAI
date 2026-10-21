package me.everyone.oculusai.storage;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("the roster is found by name, online or not")
class DatasetStoreRosterTest {

    private static PlayerData player(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        Player stub = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getName" -> name;
                    case "getEntityId" -> 1;
                    case "getLocation" -> new Location(null, 0, 0, 0);
                    case "isOnline" -> true;
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> null;
                });
        return new PlayerData(stub);
    }

    @Test
    @DisplayName("a player who logged off is still on the roster by name")
    void findsAPlayerWhoLeft() {
        // The case this exists for: an admin wants somebody off the roster
        // after they logged off, and the roster used to be reachable only
        // through the players who are online right now.
        DatasetStore store = new DatasetStore(null);
        PlayerData data = player("Hokuso17");
        store.enrol(data, DatasetStore.CHEATER);

        DatasetStore.RosterEntry entry = store.entryByName("Hokuso17");

        assertEquals(data.uuid(), entry.uuid());
        assertEquals("Hokuso17", entry.name());
        assertEquals(DatasetStore.CHEATER, entry.label());
    }

    @Test
    @DisplayName("the name is matched the way an admin types it")
    void ignoresCaseAndSpaces() {
        DatasetStore store = new DatasetStore(null);
        store.enrol(player("Hokuso17"), DatasetStore.LEGIT);

        assertEquals("Hokuso17", store.entryByName("hokuso17").name());
        assertEquals("Hokuso17", store.entryByName("  HoKuSo17  ").name());
    }

    @Test
    @DisplayName("a name nobody signed up with finds nothing")
    void unknownNameFindsNothing() {
        DatasetStore store = new DatasetStore(null);
        store.enrol(player("Hokuso17"), DatasetStore.CHEATER);

        assertNull(store.entryByName("Somebody"));
        assertNull(store.entryByName(""));
        assertNull(store.entryByName("   "));
        assertNull(store.entryByName(null));
    }
}
