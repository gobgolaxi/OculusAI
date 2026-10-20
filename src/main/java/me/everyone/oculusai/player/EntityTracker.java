package me.everyone.oculusai.player;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class EntityTracker implements Service {

    /** ``playerLike`` separates what may be analysed from what may not. Aim
     *  against a mob is not the same measurement as aim against a player: a
     *  zombie walks a straight line at a fixed speed and never strafes, so a
     *  perfectly ordinary player farming one produces the flat, precise
     *  rotation that the model was taught to read as a cheat. Dummies count as
     *  player-like - they are player-shaped NPCs and exist to be recorded
     *  against. */
    public record Tracked(double x, double y, double z, double width, double height,
                          boolean playerLike, UUID worldId, long at) {

        public double centreY() {
            return y + height / 2.0D;
        }
    }

    private static final long HISTORY_MS = 500L;
    private static final int HISTORY_LIMIT = 12;

    private final OculusAI plugin;
    private final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<Tracked>> history = new ConcurrentHashMap<>();
    /** Entity id to the online player, refreshed once per tick. WallCheck used
     *  to scan every online player on every hit to find this mapping. */
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();
    private TickLoop.Handle task;

    public EntityTracker(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        task = plugin.ticks().every(1L, this::sample);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        tracked.clear();
        history.clear();
        players.clear();
    }

    private void sample() {
        // Players and dummies only. Mobs used to be sampled here as well, up
        // to a budget per tick, so that a swing at one could be scored - but a
        // swing at one is no longer analysed at all, so their positions were
        // being written twenty times a second and never read.
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world == null) {
                continue;
            }
            players.put(player.getEntityId(), player);
            remember(player.getEntityId(), new Tracked(
                    location.getX(), location.getY(), location.getZ(),
                    player.getWidth(), player.getHeight(), true, world.getUID(), now));
        }
    }

    public void track(int entityId, double x, double y, double z, double width,
                      double height, World world) {
        track(entityId, x, y, z, width, height, true, world);
    }

    public void track(int entityId, double x, double y, double z, double width,
                      double height, boolean playerLike, World world) {
        if (world == null) {
            return;
        }
        remember(entityId, new Tracked(x, y, z, width, height, playerLike,
                world.getUID(), System.currentTimeMillis()));
    }

    private void remember(int entityId, Tracked entry) {
        tracked.put(entityId, entry);
        Deque<Tracked> entries = history.computeIfAbsent(entityId, key -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(entry);
            long cutoff = entry.at() - HISTORY_MS;
            while (entries.size() > 1
                    && (entries.peekFirst().at() < cutoff || entries.size() > HISTORY_LIMIT)) {
                entries.removeFirst();
            }
        }
    }

    /** Whether this entity may be analysed at all. An entity nobody is
     *  tracking answers false: unknown is not the same as a player, and
     *  guessing in favour of analysis is how a mob fight becomes a ban. */
    public boolean playerLike(int entityId) {
        Tracked entry = tracked.get(entityId);
        return entry != null && entry.playerLike();
    }

    public List<Tracked> recent(int entityId) {
        Deque<Tracked> entries = history.get(entityId);
        if (entries == null) {
            return List.of();
        }
        long cutoff = System.currentTimeMillis() - HISTORY_MS;
        synchronized (entries) {
            while (!entries.isEmpty() && entries.peekFirst().at() < cutoff) {
                entries.removeFirst();
            }
            return List.copyOf(entries);
        }
    }

    public void forget(int entityId) {
        tracked.remove(entityId);
        history.remove(entityId);
        players.remove(entityId);
    }

    /** The online player for this entity id, or null for a dummy/unknown. */
    public Player player(int entityId) {
        Player player = players.get(entityId);
        return player != null && player.isOnline() ? player : null;
    }

    public Tracked get(int entityId) {
        return tracked.get(entityId);
    }

    public void forget(Player player) {
        forget(player.getEntityId());
    }
}
