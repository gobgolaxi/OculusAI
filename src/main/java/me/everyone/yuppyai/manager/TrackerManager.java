package me.everyone.yuppyai.manager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.yuppyai.YuppyAI;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

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
        Set<World> inhabited = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Location location = player.getLocation();
            tracked.put(player.getEntityId(), new Tracked(
                    location.getX(), location.getY(), location.getZ(), player.getHeight()));
            inhabited.add(player.getWorld());
        }

        int budget = plugin.config().trackEntities();
        if (budget <= 0) {
            return;
        }
        for (World world : inhabited) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                if (budget-- <= 0) {
                    return;
                }
                Location location = entity.getLocation();
                tracked.put(entity.getEntityId(), new Tracked(
                        location.getX(), location.getY(), location.getZ(), entity.getHeight()));
            }
        }
    }

    public void track(int entityId, double x, double y, double z, double height) {
        tracked.put(entityId, new Tracked(x, y, z, height));
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
