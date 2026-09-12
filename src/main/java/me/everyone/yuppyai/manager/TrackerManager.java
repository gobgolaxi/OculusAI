package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TrackerManager implements Manager {

    public record Tracked(double x, double y, double z, double height) {

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
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location location = player.getLocation();
            tracked.put(player.getEntityId(), new Tracked(
                    location.getX(), location.getY(), location.getZ(), player.getHeight()));
        }
    }

    public Tracked get(int entityId) {
        return tracked.get(entityId);
    }

    public void forget(Player player) {
        tracked.remove(player.getEntityId());
    }
}
