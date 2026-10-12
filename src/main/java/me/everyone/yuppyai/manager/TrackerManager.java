package me.everyone.yuppyai.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.yuppyai.YuppyAI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class TrackerManager implements Manager {

    /** ``playerLike`` separates what may be analysed from what may not. Aim
     *  against a mob is not the same measurement as aim against a player: a
     *  zombie walks a straight line at a fixed speed and never strafes, so a
     *  perfectly ordinary player farming one produces the flat, precise
     *  rotation that the model was taught to read as a cheat. Dummies count as
     *  player-like - they are player-shaped NPCs and exist to be recorded
     *  against. */
    public record Tracked(double x, double y, double z, double height, boolean playerLike) {

        public double centreY() {
            return y + height / 2.0D;
        }
    }

    private final YuppyAI plugin;
    private final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();
    private BukkitTask task;

    public TrackerManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sample, 1L, 1L);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        tracked.clear();
    }

    private void sample() {
        // Players and dummies only. Mobs used to be sampled here as well, up
        // to a budget per tick, so that a swing at one could be scored - but a
        // swing at one is no longer analysed at all, so their positions were
        // being written twenty times a second and never read.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location location = player.getLocation();
            tracked.put(player.getEntityId(), new Tracked(
                    location.getX(), location.getY(), location.getZ(), player.getHeight(), true));
        }
    }

    public void track(int entityId, double x, double y, double z, double height) {
        track(entityId, x, y, z, height, true);
    }

    public void track(int entityId, double x, double y, double z, double height,
                      boolean playerLike) {
        tracked.put(entityId, new Tracked(x, y, z, height, playerLike));
    }

    /** Whether this entity may be analysed at all. An entity nobody is
     *  tracking answers false: unknown is not the same as a player, and
     *  guessing in favour of analysis is how a mob fight becomes a ban. */
    public boolean playerLike(int entityId) {
        Tracked entry = tracked.get(entityId);
        return entry != null && entry.playerLike();
    }

    public void forget(int entityId) {
        tracked.remove(entityId);
    }

    public Tracked get(int entityId) {
        return tracked.get(entityId);
    }

    public void forget(Player player) {
        tracked.remove(player.getEntityId());
    }
}
