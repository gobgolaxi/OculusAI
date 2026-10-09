package me.everyone.yuppyai.npc;

import com.comphenix.protocol.wrappers.WrappedGameProfile;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import me.everyone.yuppyai.manager.TestServerManager.DummyOptions;
import org.bukkit.Location;
import org.bukkit.World;

public final class Npc {

    public static final double HEIGHT = 1.8D;

    private final int entityId;
    private final UUID uuid;
    private final String name;
    private final WrappedGameProfile profile;
    private final UUID owner;
    private final World world;

    private final Set<UUID> viewers = new HashSet<>();

    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    private double maxHealth;
    private double health;
    private DummyOptions options;

    private long lastAttackTick;
    private long deathTick = -1L;
    private double strafePhase;

    public Npc(int entityId, UUID uuid, String name, WrappedGameProfile profile,
               UUID owner, Location location, DummyOptions options) {
        this.entityId = entityId;
        this.uuid = uuid;
        this.name = name;
        this.profile = profile;
        this.owner = owner;
        this.world = location.getWorld();
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
        this.strafePhase = Math.random() * Math.PI * 2.0D;
        applyOptions(options);
        this.health = this.maxHealth;
    }

    public void applyOptions(DummyOptions options) {
        this.options = options;
        double requested = options.health() > 0 ? options.health() : 20.0D;
        this.maxHealth = requested;
        this.health = Math.min(this.health <= 0 ? requested : this.health, requested);
    }

    public int entityId() {
        return entityId;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public WrappedGameProfile profile() {
        return profile;
    }

    public UUID owner() {
        return owner;
    }

    public World world() {
        return world;
    }

    public Set<UUID> viewers() {
        return viewers;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public void position(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public Location location() {
        return new Location(world, x, y, z, yaw, pitch);
    }

    public double health() {
        return health;
    }

    public double maxHealth() {
        return maxHealth;
    }

    public void health(double health) {
        this.health = health;
    }

    public DummyOptions options() {
        return options;
    }

    public long lastAttackTick() {
        return lastAttackTick;
    }

    public void lastAttackTick(long tick) {
        this.lastAttackTick = tick;
    }

    public boolean dying() {
        return deathTick >= 0L;
    }

    public long deathTick() {
        return deathTick;
    }

    public void deathTick(long tick) {
        this.deathTick = tick;
    }

    public double nextStrafe(double step) {
        strafePhase += step;
        return Math.sin(strafePhase);
    }
}
