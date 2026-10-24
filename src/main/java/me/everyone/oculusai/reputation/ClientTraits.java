package me.everyone.oculusai.reputation;

import com.comphenix.protocol.ProtocolLibrary;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** What the client told the server about itself, as a handful of short
 *  strings.
 *
 *  <p>This exists because of offline-mode servers. There the UUID is computed
 *  from the name, so the identity the service keeps *is* a name - and a name
 *  is the one thing a returning cheater changes. Nothing here is an account,
 *  and none of it is asked for: the server already has every one of these
 *  values the moment the player is in the world. But the combination repeats
 *  across one person's nicknames and rarely across two people's, which is
 *  enough to turn a fresh name from "unknown" into "unknown, on the client
 *  that was banned here last week".
 *
 *  <p>Deliberately passive. No packet is sent to the client, nothing is
 *  measured by timing it, and a client that refuses to say something simply
 *  contributes one trait fewer - an anticheat that probes the client to
 *  identify it is a different and much worse thing than one that reads what
 *  arrived on its own.
 *
 *  <p>Every read is guarded, because every one of them is optional in some
 *  direction: {@code getClientBrandName} and {@code getClientViewDistance}
 *  are Paper's rather than Bukkit's and a fork may not have them, ProtocolLib
 *  can be mid-reload, and Floodgate is usually not installed at all. A trait
 *  that cannot be read is left out rather than filled with a placeholder.
 */
public final class ClientTraits {

    /** Floodgate's API, looked up once. Null when Bedrock players are not a
     *  thing on this server, which is the common case. */
    private static final Method FLOODGATE_PLAYER = floodgateLookup("getPlayer");
    private static final Method FLOODGATE_XUID = floodgateLookup("getXuid");
    private static final Object FLOODGATE_API = floodgateApi();

    private ClientTraits() {
    }

    /** The traits as they stand now, or an empty map if nothing could be read.
     *
     *  <p>Must be called from the thread that owns the player: most of these
     *  read live connection state.
     *
     *  <p>Order matters only for reading - the service canonicalises the set
     *  itself before hashing it, so a trait appearing or disappearing from
     *  this map does not quietly redefine every fingerprint ever stored. */
    public static Map<String, String> of(Player player) {
        Map<String, String> traits = new LinkedHashMap<>();
        if (player == null || !player.isOnline()) {
            return traits;
        }
        put(traits, "brand", brand(player));
        put(traits, "version", version(player));
        put(traits, "locale", locale(player));
        put(traits, "view", viewDistance(player));
        put(traits, "hand", mainHand(player));
        put(traits, "xuid", xuid(player));
        return traits;
    }

    /** Whether a snapshot is worth sending at all.
     *
     *  <p>One trait is not a fingerprint: on a server where only the locale
     *  can be read, every player in the country shares an edge, and an edge
     *  everybody shares is worse than no edge because it looks like a finding.
     *  A Bedrock id is the exception - that one value is an account. */
    public static boolean usable(Map<String, String> traits) {
        return traits.containsKey("xuid") || traits.size() >= 3;
    }

    /** The client's self-reported brand: {@code vanilla}, {@code fabric},
     *  {@code lunarclient:...}. A client is free to lie about it, and a client
     *  that lies consistently is still identifiable by the lie. */
    private static String brand(Player player) {
        try {
            String brand = player.getClientBrandName();
            return brand == null ? "" : brand.toLowerCase(Locale.ROOT).trim();
        } catch (Throwable notOnThisServer) {
            return "";
        }
    }

    /** The protocol the client speaks, which on a ViaVersion server is the
     *  client's own version rather than the server's. */
    private static String version(Player player) {
        try {
            int protocol = ProtocolLibrary.getProtocolManager().getProtocolVersion(player);
            return protocol > 0 ? Integer.toString(protocol) : "";
        } catch (Throwable protocolLibBusy) {
            return "";
        }
    }

    private static String locale(Player player) {
        try {
            String locale = player.getLocale();
            return locale == null ? "" : locale.toLowerCase(Locale.ROOT).trim();
        } catch (Throwable notOnThisServer) {
            return "";
        }
    }

    private static String viewDistance(Player player) {
        try {
            int distance = player.getClientViewDistance();
            return distance > 0 ? Integer.toString(distance) : "";
        } catch (Throwable notOnThisServer) {
            return "";
        }
    }

    private static String mainHand(Player player) {
        try {
            return player.getMainHand().name().toLowerCase(Locale.ROOT);
        } catch (Throwable notOnThisServer) {
            return "";
        }
    }

    /** The Bedrock account id, when the player came in through Geyser and
     *  Floodgate is here to say so. The only value in this map that is an
     *  account rather than a configuration, which is why the service links on
     *  it without the "is this combination common" test the rest get. */
    private static String xuid(Player player) {
        if (FLOODGATE_API == null || FLOODGATE_PLAYER == null || FLOODGATE_XUID == null) {
            return "";
        }
        try {
            Object bedrock = FLOODGATE_PLAYER.invoke(FLOODGATE_API, player.getUniqueId());
            if (bedrock == null) {
                return "";
            }
            Object xuid = FLOODGATE_XUID.invoke(bedrock);
            return xuid == null ? "" : xuid.toString().trim();
        } catch (ReflectiveOperationException | RuntimeException notBedrock) {
            return "";
        }
    }

    private static void put(Map<String, String> traits, String key, String value) {
        if (value != null && !value.isBlank()) {
            traits.put(key, value.length() > 48 ? value.substring(0, 48) : value);
        }
    }

    private static Object floodgateApi() {
        try {
            // Asked of the plugin manager first: Floodgate's classes can be on
            // the classpath of a proxy-shaded build without Floodgate running
            // here, and getInstance() then hands back something that answers
            // for nobody.
            if (Bukkit.getServer() == null
                    || Bukkit.getPluginManager().getPlugin("floodgate") == null) {
                return null;
            }
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            return api.getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }

    private static Method floodgateLookup(String name) {
        try {
            if ("getPlayer".equals(name)) {
                return Class.forName("org.geysermc.floodgate.api.FloodgateApi")
                        .getMethod("getPlayer", java.util.UUID.class);
            }
            return Class.forName("org.geysermc.floodgate.api.player.FloodgatePlayer")
                    .getMethod("getXuid");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            return null;
        }
    }
}
