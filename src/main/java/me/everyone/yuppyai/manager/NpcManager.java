package me.everyone.yuppyai.manager;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.gui.DummyMenu;
import me.everyone.yuppyai.manager.TestServerManager.DummyOptions;
import me.everyone.yuppyai.npc.Npc;
import me.everyone.yuppyai.npc.NpcPackets;
import me.everyone.yuppyai.npc.NpcProtocol;
import me.everyone.yuppyai.util.UseActions;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

public final class NpcManager implements Manager, Listener {

    private static final int MAX_NPCS = 5;
    private static final int VIEW_DISTANCE_SQUARED = 64 * 64;
    private static final int TARGET_RANGE_SQUARED = 48 * 48;

    private static final int ENTITY_ID_BASE = Integer.MAX_VALUE - 4096;

    private static final double SPEED = 0.23D;
    private static final double REACH = 3.2D;
    private static final double PREFERRED_DISTANCE = 2.4D;
    private static final long ATTACK_COOLDOWN_TICKS = 12L;
    private static final double ATTACK_DAMAGE = 2.0D;
    private static final double HIT_DAMAGE = 6.0D;
    private static final double KNOCKBACK = 0.35D;
    private static final long DEATH_LINGER_TICKS = 20L;
    private static final double EYE_HEIGHT = 1.62D;

    private final YuppyAI plugin;
    private final Map<UUID, Npc> npcs = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(ENTITY_ID_BASE);
    private final AtomicInteger names = new AtomicInteger();

    private NpcProtocol protocol;
    private ProtocolManager protocolManager;
    private PacketListener listener;
    private BukkitTask task;
    private long ticks;

    public NpcManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        protocol = new NpcPackets();
        protocolManager = ProtocolLibrary.getProtocolManager();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        listener = new PacketAdapter(plugin, ListenerPriority.LOW,
                PacketType.Play.Client.USE_ENTITY) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    handleUse(event);
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Error reading an NPC interaction", throwable);
                }
            }
        };
        protocolManager.addPacketListener(listener);

        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (listener != null && protocolManager != null) {
            protocolManager.removePacketListener(listener);
            listener = null;
        }
        clearAll();
    }

    public int spawn(Player owner, int count, DummyOptions options) {
        int allowed = Math.min(Math.max(1, count), MAX_NPCS - npcs.size());
        if (allowed <= 0) {
            return 0;
        }

        for (int i = 0; i < allowed; i++) {
            Location spot = owner.getLocation().clone()
                    .add(owner.getLocation().getDirection().setY(0).normalize().multiply(2.5D));
            spot.setYaw(owner.getLocation().getYaw() + 180.0F);
            spot.setPitch(0.0F);

            UUID uuid = UUID.randomUUID();
            String name = "Dummy" + names.incrementAndGet();
            Npc npc = new Npc(ids.decrementAndGet(), uuid, name,
                    profileFor(owner, uuid, name), owner.getUniqueId(), spot, options);
            npcs.put(uuid, npc);
            refreshViewers(npc);
        }
        return allowed;
    }

    private WrappedGameProfile profileFor(Player owner, UUID uuid, String name) {
        WrappedGameProfile profile = new WrappedGameProfile(uuid, name);
        try {
            WrappedGameProfile source = WrappedGameProfile.fromPlayer(owner);
            profile.getProperties().putAll(source.getProperties());
        } catch (Throwable ignored) {
        }
        return profile;
    }

    public void clearAll() {
        for (Npc npc : new ArrayList<>(npcs.values())) {
            destroy(npc);
        }
        npcs.clear();
    }

    public void remove(UUID id) {
        Npc npc = npcs.remove(id);
        if (npc != null) {
            destroy(npc);
        }
    }

    private void destroy(Npc npc) {
        for (UUID viewerId : new ArrayList<>(npc.viewers())) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                protocol.despawn(viewer, npc);
            }
        }
        npc.viewers().clear();
        plugin.tracker().forget(npc.entityId());
    }

    public int count() {
        return npcs.size();
    }

    public boolean isNpc(UUID id) {
        return npcs.containsKey(id);
    }

    public DummyOptions optionsOf(UUID id) {
        Npc npc = npcs.get(id);
        return npc == null ? null : npc.options();
    }

    public void updateOptions(UUID id, DummyOptions options) {
        Npc npc = npcs.get(id);
        if (npc != null) {
            npc.applyOptions(options);
        }
    }

    private void tick() {
        ticks++;
        for (Npc npc : npcs.values()) {
            if (npc.dying()) {
                if (ticks - npc.deathTick() >= DEATH_LINGER_TICKS) {
                    remove(npc.uuid());
                }
                continue;
            }
            refreshViewers(npc);
            Player target = npc.options().stationary() ? null : target(npc);
            if (target != null) {
                face(npc, target);
                step(npc, target);
                attack(npc, target);
            }
            broadcastLook(npc);
            plugin.tracker().track(npc.entityId(), npc.x(), npc.y(), npc.z(), Npc.HEIGHT);
        }
    }

    private Player target(Npc npc) {
        Player closest = null;
        double best = TARGET_RANGE_SQUARED;
        for (Player player : npc.world().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(npc.location());
            if (distance < best) {
                best = distance;
                closest = player;
            }
        }
        return closest;
    }

    private void face(Npc npc, Player target) {
        double dx = target.getLocation().getX() - npc.x();
        double dy = (target.getLocation().getY() + target.getEyeHeight()) - (npc.y() + EYE_HEIGHT);
        double dz = target.getLocation().getZ() - npc.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.01D, flat)));
        npc.rotation(yaw, pitch);
    }

    private void step(Npc npc, Player target) {
        Location at = target.getLocation();
        double dx = at.getX() - npc.x();
        double dz = at.getZ() - npc.z();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.0001D) {
            return;
        }

        double forward = distance > PREFERRED_DISTANCE + 0.3D ? 1.0D
                : distance < PREFERRED_DISTANCE - 0.6D ? -1.0D : 0.0D;
        double strafe = npc.nextStrafe(0.12D) * (distance < REACH ? 1.0D : 0.3D);

        double nx = dx / distance;
        double nz = dz / distance;
        double moveX = (nx * forward - nz * strafe) * SPEED;
        double moveZ = (nz * forward + nx * strafe) * SPEED;
        double moveY = at.getY() - npc.y();
        moveY = Math.max(-0.4D, Math.min(0.4D, moveY));

        move(npc, moveX, moveY, moveZ);
    }

    private void move(Npc npc, double dx, double dy, double dz) {
        double stepX = quantise(dx);
        double stepY = quantise(dy);
        double stepZ = quantise(dz);
        npc.position(npc.x() + stepX, npc.y() + stepY, npc.z() + stepZ);
        for (Player viewer : viewersOf(npc)) {
            protocol.move(viewer, npc, stepX, stepY, stepZ);
        }
    }

    private static double quantise(double blocks) {
        return Math.round(blocks * 4096.0D) / 4096.0D;
    }

    private void attack(Npc npc, Player target) {
        if (ticks - npc.lastAttackTick() < ATTACK_COOLDOWN_TICKS) {
            return;
        }
        if (target.getLocation().distance(npc.location()) > REACH) {
            return;
        }
        npc.lastAttackTick(ticks);
        for (Player viewer : viewersOf(npc)) {
            protocol.swing(viewer, npc);
        }
        target.damage(ATTACK_DAMAGE);
    }

    private void broadcastLook(Npc npc) {
        for (Player viewer : viewersOf(npc)) {
            protocol.look(viewer, npc);
        }
    }

    private List<Player> viewersOf(Npc npc) {
        List<Player> players = new ArrayList<>(npc.viewers().size());
        for (UUID id : npc.viewers()) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null && viewer.isOnline()) {
                players.add(viewer);
            }
        }
        return players;
    }

    private void refreshViewers(Npc npc) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean shouldSee = player.getWorld().equals(npc.world())
                    && player.getLocation().distanceSquared(npc.location()) <= VIEW_DISTANCE_SQUARED;
            boolean sees = npc.viewers().contains(player.getUniqueId());
            if (shouldSee && !sees) {
                npc.viewers().add(player.getUniqueId());
                protocol.spawn(player, npc);
                protocol.equip(player, npc);
                protocol.look(player, npc);
            } else if (!shouldSee && sees) {
                npc.viewers().remove(player.getUniqueId());
                protocol.despawn(player, npc);
            }
        }
        npc.viewers().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Npc npc : npcs.values()) {
            npc.viewers().remove(id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        if (!to.getWorld().equals(event.getFrom().getWorld())
                || event.getFrom().distanceSquared(to) > 16.0D) {
            forget(event.getPlayer());
        }
    }

    private void forget(Player player) {
        UUID id = player.getUniqueId();
        for (Npc npc : npcs.values()) {
            npc.viewers().remove(id);
        }
    }

    private void handleUse(PacketEvent event) {
        int entityId = event.getPacket().getIntegers().read(0);
        Npc npc = byEntityId(entityId);
        if (npc == null) {
            return;
        }

        String action = UseActions.of(event.getPacket());
        Player player = event.getPlayer();

        if ("ATTACK".equals(action)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> hit(npc, player));
            return;
        }
        if (player.isSneaking() && player.hasPermission("yuppyai.npc")) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> new DummyMenu(plugin, player, npc.uuid()).open());
        }
    }

    private static Sound hitSound() {
        for (String name : new String[] {"ENTITY_PLAYER_HURT", "ENTITY_PLAYER_ATTACK_STRONG"}) {
            try {
                return Sound.valueOf(name);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Sound.values()[0];
    }

    private Npc byEntityId(int entityId) {
        for (Npc npc : npcs.values()) {
            if (npc.entityId() == entityId) {
                return npc;
            }
        }
        return null;
    }

    private void hit(Npc npc, Player attacker) {
        if (npc.dying() || !npcs.containsKey(npc.uuid())) {
            return;
        }
        for (Player viewer : viewersOf(npc)) {
            protocol.hurt(viewer, npc);
        }
        attacker.playSound(attacker.getLocation(), hitSound(), 1.0F, 1.0F);

        double dx = npc.x() - attacker.getLocation().getX();
        double dz = npc.z() - attacker.getLocation().getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat > 0.0001D) {
            move(npc, dx / flat * KNOCKBACK, 0.0D, dz / flat * KNOCKBACK);
        }

        if (npc.options().invulnerable()) {
            return;
        }
        npc.health(npc.health() - HIT_DAMAGE);
        if (npc.health() > 0) {
            return;
        }
        npc.deathTick(ticks);
        for (Player viewer : viewersOf(npc)) {
            protocol.death(viewer, npc);
        }
        attacker.sendMessage(plugin.config().prefix()
                + plugin.lang().text("npc.killed", Map.of("value", npc.name())));
    }
}
