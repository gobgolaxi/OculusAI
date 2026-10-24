package me.everyone.oculusai.npc;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
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
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.ui.menu.DummyMenu;
import me.everyone.oculusai.npc.NpcOptions;
import me.everyone.oculusai.npc.Npc;
import me.everyone.oculusai.npc.NpcPackets;
import me.everyone.oculusai.npc.NpcProtocol;
import me.everyone.oculusai.packet.UseActions;
import me.everyone.oculusai.player.EntityTracker;
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

public final class Npcs implements Service, Listener {

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
    /** How old a position sample may be before its player is ignored. The
     *  tracker takes one every tick, so this only ever excludes somebody on
     *  their way out. */
    private static final long SAMPLE_MAX_AGE_MS = 1_000L;

    private final OculusAI plugin;
    private final Map<UUID, Npc> npcs = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(ENTITY_ID_BASE);
    private final AtomicInteger names = new AtomicInteger();

    private NpcProtocol protocol;
    private ProtocolManager protocolManager;
    private PacketListener listener;
    private TickLoop.Handle task;
    private long ticks;

    public Npcs(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        protocol = new NpcPackets();
        protocolManager = ProtocolLibrary.getProtocolManager();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST,
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

        task = plugin.ticks().every(1L, this::tick);
    }

    /** Whether this server can run a manikin at all.
     *
     *  <p>True on both now. It used to be false on Folia, because the tick
     *  chased a player and hit them, and both of those read an entity owned by
     *  whatever region it had walked into. The manikin itself is not an entity
     *  - it is a few packets and a position this class keeps - so what it
     *  needed was to stop reading players: it takes their positions from the
     *  tracker's samples, and hands the one thing it does to a player, damage,
     *  to that player's own thread. */
    public boolean supported() {
        return true;
    }

    /** Where a player is, read from wherever that is legal.
     *
     *  <p>On Paper, from the player. On Folia, from the sample their own
     *  region thread took - the manikin's tick runs on the global region,
     *  which owns nobody. Null when nobody has sampled them recently, which
     *  means the manikin ignores them: a target it cannot locate is a target
     *  it cannot chase. */
    private Location positionOf(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        if (!plugin.platform().folia()) {
            return player.getLocation();
        }
        EntityTracker.Tracked sample = plugin.tracker().get(player.getEntityId());
        if (sample == null || System.currentTimeMillis() - sample.at() > SAMPLE_MAX_AGE_MS) {
            return null;
        }
        org.bukkit.World world = plugin.getServer().getWorld(sample.worldId());
        return world == null ? null
                : new Location(world, sample.x(), sample.y(), sample.z());
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

    public int spawn(Player owner, int count, NpcOptions options) {
        if (!supported()) {
            return 0;
        }
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

    public NpcOptions optionsOf(UUID id) {
        Npc npc = npcs.get(id);
        return npc == null ? null : npc.options();
    }

    public void updateOptions(UUID id, NpcOptions options) {
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
            Location at = target == null ? null : positionOf(target);
            if (target != null && at != null) {
                face(npc, target, at);
                step(npc, at);
                attack(npc, target, at);
            }
            broadcastLook(npc);
            plugin.tracker().track(npc.entityId(), npc.x(), npc.y(), npc.z(),
                    Npc.WIDTH, Npc.HEIGHT, npc.world());
        }
    }

    private Player target(Npc npc) {
        Player closest = null;
        double best = TARGET_RANGE_SQUARED;
        for (Player player : npc.world().getPlayers()) {
            Location at = positionOf(player);
            if (at == null || !npc.world().equals(at.getWorld())) {
                continue;
            }
            if (player.isDead() || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double distance = at.distanceSquared(npc.location());
            if (distance < best) {
                best = distance;
                closest = player;
            }
        }
        return closest;
    }

    private void face(Npc npc, Player target, Location at) {
        double dx = at.getX() - npc.x();
        double dy = (at.getY() + target.getEyeHeight()) - (npc.y() + EYE_HEIGHT);
        double dz = at.getZ() - npc.z();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.01D, flat)));
        npc.rotation(yaw, pitch);
    }

    private void step(Npc npc, Location at) {
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

    private void attack(Npc npc, Player target, Location at) {
        if (ticks - npc.lastAttackTick() < ATTACK_COOLDOWN_TICKS) {
            return;
        }
        if (at.distance(npc.location()) > REACH) {
            return;
        }
        npc.lastAttackTick(ticks);
        for (Player viewer : viewersOf(npc)) {
            protocol.swing(viewer, npc);
        }
        // The one thing the manikin does to a player, and the only part of
        // this that has to be on the player's own thread.
        plugin.platform().onEntity(target, () -> target.damage(ATTACK_DAMAGE));
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
            Location at = positionOf(player);
            boolean shouldSee = at != null && npc.world().equals(at.getWorld())
                    && at.distanceSquared(npc.location()) <= VIEW_DISTANCE_SQUARED;
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
        if (event.isCancelled()) {
            return;
        }
        int entityId = event.getPacket().getIntegers().read(0);
        Npc npc = byEntityId(entityId);
        if (npc == null) {
            return;
        }

        String action = UseActions.of(event.getPacket());
        Player player = event.getPlayer();

        // Onto the attacker's own thread, not the main one: everything either
        // branch touches is this player - a sound, their position, their
        // inventory screen - and on Folia the global region may touch none of
        // it.
        if ("ATTACK".equals(action)) {
            // Two threads, in order. Where the attacker is standing and the
            // sound they hear are theirs to read and theirs to be sent; the
            // manikin's own position, health and viewer packets belong to the
            // tick that owns them, or two threads would be moving one manikin.
            plugin.platform().onEntity(player, () -> {
                Location from = player.getLocation();
                player.playSound(from, hitSound(), 1.0F, 1.0F);
                plugin.mainThread().run(() -> hit(npc, player, from));
            });
            return;
        }
        if (player.isSneaking() && player.hasPermission("oculusai.npc")) {
            event.setCancelled(true);
            plugin.platform().onEntity(player, () -> new DummyMenu(plugin, player, npc.uuid()).open());
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

    /** A blow landing on the manikin, from an attacker standing at
     *  {@code from}. Runs where the manikin is ticked. */
    private void hit(Npc npc, Player attacker, Location from) {
        if (npc.dying() || !npcs.containsKey(npc.uuid())) {
            return;
        }
        for (Player viewer : viewersOf(npc)) {
            protocol.hurt(viewer, npc);
        }

        double dx = npc.x() - from.getX();
        double dz = npc.z() - from.getZ();
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
