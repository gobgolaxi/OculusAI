package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class PunishmentManager implements Manager {

    private record Case(long openedAt, double buffer, BukkitTask task) {
    }

    private static final Material[] SCATTER_ITEMS = {
            Material.DIAMOND_SWORD, Material.GOLDEN_APPLE, Material.IRON_CHESTPLATE,
            Material.ENDER_PEARL, Material.BONE, Material.ROTTEN_FLESH,
            Material.ARROW, Material.BLAZE_ROD, Material.EXPERIENCE_BOTTLE,
            Material.IRON_INGOT
    };

    private static final Particle PARTICLE_EXPLOSION = safeParticle("EXPLOSION", "EXPLOSION_LARGE");
    private static final Particle PARTICLE_EXPLOSION_BIG = safeParticle("EXPLOSION_EMITTER", "EXPLOSION_HUGE");
    private static final Particle PARTICLE_SPARK = safeParticle("FIREWORK", "FIREWORKS_SPARK");
    private static final Particle PARTICLE_LARGE_SMOKE = safeParticle("LARGE_SMOKE", "SMOKE_LARGE");
    private static final Particle PARTICLE_SMOKE = safeParticle("SMOKE", "SMOKE_NORMAL");
    private static final Particle PARTICLE_SOUL_FIRE = safeParticle("SOUL_FIRE_FLAME", "SOUL_FIRE_FLAME", "FLAME");

    private final YuppyAI plugin;
    private final Map<UUID, Case> open = new ConcurrentHashMap<>();
    private final Set<UUID> animating = ConcurrentHashMap.newKeySet();

    public PunishmentManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        for (Case pending : open.values()) {
            pending.task().cancel();
        }
        open.clear();
        animating.clear();
    }

    public void handle(PlayerData data) {
        if (!plugin.config().punishmentEnabled() || open.containsKey(data.uuid())) {
            return;
        }
        if (animating.contains(data.uuid())) {
            return;
        }
        if (!plugin.config().evidenceEnabled()) {
            punish(data);
            return;
        }
        if (data.recording()) {
            punish(data);
            return;
        }

        data.recording(true, plugin.config().evidenceLabel(), plugin.config().evidenceFloor());
        int seconds = plugin.config().evidenceSeconds();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> close(data), seconds * 20L);
        open.put(data.uuid(), new Case(System.currentTimeMillis(), data.buffer(), task));

        announce(Msg.parse(plugin.config().prefix() + "<gold>Collecting evidence on <white>"
                + data.name() + "<gold> for " + seconds + "s <dark_gray>(buffer "
                + Msg.round(data.buffer(), 1) + ")"));
    }

    private void close(PlayerData data) {
        open.remove(data.uuid());
        String label = plugin.config().evidenceLabel();
        data.recording(false, null);

        int captured = data.recordedCount();
        int dropped = data.discardedCount();
        boolean held = data.buffer() >= plugin.config().cancelBelow();

        if (captured > 0) {
            plugin.datasets().commit(data, label, false, held ? "evidence" : "cancelled");
        }

        if (!data.player().isOnline()) {
            return;
        }
        String kept = "<gray>kept <white>" + captured + "<gray> windows, dropped <white>"
                + dropped + "<gray> that were not convincing enough";

        if (!held) {
            announce(Msg.parse(plugin.config().prefix() + "<gray>No action against <white>"
                    + data.name() + "<gray>: buffer fell to <white>" + Msg.round(data.buffer(), 1)
                    + "<gray>, " + kept + " <dark_gray>(filed as cancelled, not as cheating)"));
            return;
        }
        announce(Msg.parse(plugin.config().prefix() + "<gray>Evidence on <white>" + data.name()
                + "<gray>: " + kept));
        punish(data);
    }

    private void punish(PlayerData data) {
        Player player = data.player();
        if (!player.isOnline()) {
            return;
        }

        KickAnimation animation = plugin.config().kickAnimation();
        if (animation == KickAnimation.NONE) {
            executeKick(data);
            return;
        }

        if (!animating.add(data.uuid())) {
            return;
        }

        switch (animation) {
            case EXPLODE -> playExplode(data);
            case LIGHTNING -> playLightning(data);
            case FIREWORK -> playFirework(data);
            case WITHER -> playWither(data);
            default -> {
                animating.remove(data.uuid());
                executeKick(data);
            }
        }
    }

    private void executeKick(PlayerData data) {
        animating.remove(data.uuid());
        Player player = data.player();
        if (!player.isOnline()) {
            return;
        }
        Map<String, String> placeholders = Map.of(
                "player", data.name(),
                "probability", Msg.percent(data.probability()),
                "buffer", Msg.round(data.buffer(), 1));

        String command = Msg.fill(plugin.config().punishmentCommand(), placeholders);
        if (command.isBlank()) {
            plugin.getLogger().warning("Punishment is on but no command is set");
            return;
        }

        plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
        plugin.getLogger().info("Acted on " + data.name() + " -> " + command);

        String broadcast = plugin.config().punishmentBroadcast();
        if (!broadcast.isBlank()) {
            announce(Msg.parse(plugin.config().prefix() + broadcast, placeholders));
        }
    }

    private void playExplode(PlayerData data) {
        Player player = data.player();
        World world = player.getWorld();

        player.setVelocity(new Vector(0, 1.8, 0));
        world.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0F, 0.5F);

        for (int t = 1; t <= 15; t++) {
            final int tick = t;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) { animating.remove(data.uuid()); return; }
                Location loc = player.getLocation().add(0, 1, 0);
                double angle = tick * 0.8;
                double r = 0.8;
                loc.add(Math.cos(angle) * r, 0, Math.sin(angle) * r);
                world.spawnParticle(Particle.FLAME, loc, 3, 0.05, 0.05, 0.05, 0.01);
                world.spawnParticle(PARTICLE_SMOKE, loc, 2, 0.1, 0.1, 0.1, 0.01);
            }, t);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { animating.remove(data.uuid()); return; }
            Location loc = player.getLocation().add(0, 1, 0);
            world.spawnParticle(PARTICLE_EXPLOSION_BIG, loc, 3, 0.5, 0.5, 0.5, 0);
            world.spawnParticle(PARTICLE_EXPLOSION, loc, 8, 1.5, 1.5, 1.5, 0);
            world.spawnParticle(Particle.FLAME, loc, 40, 1.0, 1.0, 1.0, 0.15);
            world.spawnParticle(PARTICLE_LARGE_SMOKE, loc, 20, 1.0, 1.0, 1.0, 0.08);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 3.0F, 0.8F);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 3.0F, 1.2F);
            scatterItems(player);
        }, 18L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { animating.remove(data.uuid()); return; }
            Location loc = player.getLocation().add(0, 1, 0);
            world.spawnParticle(PARTICLE_LARGE_SMOKE, loc, 15, 2.0, 1.5, 2.0, 0.05);
        }, 25L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> executeKick(data), 30L);
    }

    private void playLightning(PlayerData data) {
        Player player = data.player();
        World world = player.getWorld();

        world.strikeLightningEffect(player.getLocation());
        world.spawnParticle(Particle.FLAME, player.getLocation().add(0, 1, 0), 30, 0.8, 1.0, 0.8, 0.05);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { animating.remove(data.uuid()); return; }
            world.strikeLightningEffect(player.getLocation());
            player.setVelocity(new Vector(0, 0.6, 0));
            Location loc = player.getLocation().add(0, 1, 0);
            world.spawnParticle(PARTICLE_SOUL_FIRE, loc, 40, 1.0, 0.5, 1.0, 0.08);
        }, 12L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { animating.remove(data.uuid()); return; }
            world.strikeLightningEffect(player.getLocation());
            Location loc = player.getLocation().add(0, 1, 0);
            world.spawnParticle(PARTICLE_EXPLOSION, loc, 5, 1.0, 1.0, 1.0, 0);
            world.spawnParticle(Particle.FLAME, loc, 50, 1.5, 1.0, 1.5, 0.12);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0F, 1.5F);
        }, 22L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> executeKick(data), 28L);
    }

    private void playFirework(PlayerData data) {
        Player player = data.player();
        World world = player.getWorld();

        player.setVelocity(new Vector(0, 2.2, 0));
        world.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2.0F, 1.0F);

        for (int t = 1; t <= 20; t++) {
            final int tick = t;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) { animating.remove(data.uuid()); return; }
                Location loc = player.getLocation();
                world.spawnParticle(PARTICLE_SPARK, loc, 5, 0.1, 0.1, 0.1, 0.05);
            }, t);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { animating.remove(data.uuid()); return; }
            Location loc = player.getLocation().add(0, 1.5, 0);
            world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 3.0F, 1.0F);
            world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 3.0F, 1.2F);
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int burst = 0; burst < 3; burst++) {
                double ox = (rng.nextDouble() - 0.5) * 3;
                double oy = rng.nextDouble() * 2;
                double oz = (rng.nextDouble() - 0.5) * 3;
                Location burstLoc = loc.clone().add(ox, oy, oz);
                world.spawnParticle(PARTICLE_SPARK, burstLoc, 30, 0.6, 0.6, 0.6, 0.15);
                world.spawnParticle(PARTICLE_EXPLOSION, burstLoc, 2, 0.3, 0.3, 0.3, 0);
            }
            world.spawnParticle(PARTICLE_SPARK, loc, 60, 1.5, 2.0, 1.5, 0.2);
        }, 22L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { animating.remove(data.uuid()); return; }
            Location loc = player.getLocation().add(0, 1, 0);
            world.spawnParticle(PARTICLE_SPARK, loc, 40, 2.0, 2.0, 2.0, 0.1);
            world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 2.0F, 0.8F);
        }, 28L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> executeKick(data), 32L);
    }

    private void playWither(PlayerData data) {
        Player player = data.player();
        World world = player.getWorld();

        world.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN, 2.0F, 1.5F);
        Location center = player.getLocation().add(0, 1, 0);
        world.spawnParticle(PARTICLE_LARGE_SMOKE, center, 40, 1.5, 1.5, 1.5, 0.02);
        world.spawnParticle(PARTICLE_SOUL_FIRE, center, 20, 1.0, 0.5, 1.0, 0.03);

        for (int t = 1; t <= 25; t++) {
            final int tick = t;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) { animating.remove(data.uuid()); return; }
                Location loc = player.getLocation().add(0, 1, 0);
                double radius = 3.0 - (tick / 25.0) * 2.5;
                int points = 8;
                for (int p = 0; p < points; p++) {
                    double angle = (2 * Math.PI / points) * p + tick * 0.3;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    world.spawnParticle(PARTICLE_SOUL_FIRE, loc.clone().add(x, 0, z), 1, 0, 0, 0, 0);
                    world.spawnParticle(PARTICLE_SMOKE, loc.clone().add(x, 0.3, z), 1, 0, 0, 0, 0);
                }
                if (tick % 5 == 0) {
                    world.spawnParticle(PARTICLE_LARGE_SMOKE, loc, 8, 0.5, 0.5, 0.5, 0.02);
                }
            }, t);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) { animating.remove(data.uuid()); return; }
            player.setVelocity(new Vector(0, 1.2, 0));
            Location loc = player.getLocation().add(0, 1, 0);
            world.spawnParticle(PARTICLE_EXPLOSION_BIG, loc, 2, 0.5, 0.5, 0.5, 0);
            world.spawnParticle(PARTICLE_SOUL_FIRE, loc, 60, 2.0, 2.0, 2.0, 0.15);
            world.spawnParticle(PARTICLE_LARGE_SMOKE, loc, 40, 2.0, 2.0, 2.0, 0.08);
            world.playSound(loc, Sound.ENTITY_WITHER_DEATH, 2.0F, 1.5F);
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.0F, 0.6F);
        }, 28L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> executeKick(data), 35L);
    }


    private void scatterItems(Player player) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Location loc = player.getLocation().add(0, 1.5, 0);
        World world = player.getWorld();
        for (int i = 0; i < 10; i++) {
            Material mat = SCATTER_ITEMS[rng.nextInt(SCATTER_ITEMS.length)];
            Item item = world.dropItem(loc.clone(), new ItemStack(mat));
            item.setPickupDelay(Integer.MAX_VALUE);
            item.setVelocity(new Vector(
                    (rng.nextDouble() - 0.5) * 2.0,
                    rng.nextDouble() * 0.8 + 0.5,
                    (rng.nextDouble() - 0.5) * 2.0));
            plugin.getServer().getScheduler().runTaskLater(plugin, item::remove, 50L);
        }
    }

    private static Particle safeParticle(String... names) {
        for (String name : names) {
            try { return Particle.valueOf(name); } catch (Exception ignored) {}
        }
        return Particle.FLAME;
    }

    private void announce(String message) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("yuppyai.alerts")) {
                online.sendMessage(message);
            }
        }
    }

    public boolean pending(UUID uuid) {
        return open.containsKey(uuid);
    }

    public long remainingSeconds(UUID uuid) {
        Case pending = open.get(uuid);
        if (pending == null) {
            return 0L;
        }
        long elapsed = (System.currentTimeMillis() - pending.openedAt()) / 1000L;
        return Math.max(0L, plugin.config().evidenceSeconds() - elapsed);
    }

    public void forget(UUID uuid) {
        Case pending = open.remove(uuid);
        if (pending != null) {
            pending.task().cancel();
        }
        animating.remove(uuid);
    }
}
