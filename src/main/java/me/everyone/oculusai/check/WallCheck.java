package me.everyone.oculusai.check;

import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class WallCheck implements CombatCheck {

    private static final double HALF_WIDTH = 0.24D;
    private static final double MIN_DISTANCE = 0.1D;
    private static final double HIT_MARGIN = 0.05D;

    private final OculusAI plugin;

    public WallCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "wall";
    }

    @Override
    public CheckResult test(Player attacker, PlayerData data, int targetEntityId) {
        if (!plugin.config().wallCheckEnabled()) {
            return CheckResult.pass();
        }
        if (targetEntityId == attacker.getEntityId()) {
            return CheckResult.pass();
        }

        EntityTracker.Tracked target = resolveTarget(attacker, targetEntityId);
        if (target == null) {
            return CheckResult.pass();
        }

        Location eye = attacker.getEyeLocation();
        World world = eye.getWorld();
        if (world == null || !world.getBlockAt(eye).isPassable()) {
            return CheckResult.pass();
        }

        double dx = target.x() - eye.getX();
        double dy = target.centreY() - eye.getY();
        double dz = target.z() - eye.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < MIN_DISTANCE || distance > plugin.config().wallCheckMaxDistance()) {
            return CheckResult.pass();
        }

        double[] xs = {target.x(), target.x() - HALF_WIDTH, target.x() + HALF_WIDTH};
        double[] zs = {target.z(), target.z() - HALF_WIDTH, target.z() + HALF_WIDTH};
        double[] ys = {
                target.y() + 0.25D,
                target.centreY(),
                target.y() + Math.max(0.25D, target.height() - 0.25D)
        };

        Vector eyeVector = eye.toVector();
        for (double y : ys) {
            for (double x : xs) {
                for (double z : zs) {
                    if (clear(world, eyeVector, x, y, z)) {
                        return CheckResult.pass();
                    }
                }
            }
        }

        String rounded = String.format(Locale.ROOT, "%.1f", distance);
        return CheckResult.fail(plugin.lang().text("check.wall", Map.of("distance", rounded)));
    }

    private EntityTracker.Tracked resolveTarget(Player attacker, int targetEntityId) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getEntityId() == targetEntityId) {
                Location location = online.getLocation();
                if (location.getWorld() == null || !location.getWorld().equals(attacker.getWorld())) {
                    return null;
                }
                return new EntityTracker.Tracked(
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        online.getHeight(),
                        true);
            }
        }
        return plugin.tracker().get(targetEntityId);
    }

    private boolean clear(World world, Vector eye, double x, double y, double z) {
        Vector direction = new Vector(x, y, z).subtract(eye);
        double distance = direction.length();
        if (distance <= HIT_MARGIN) {
            return true;
        }
        direction.multiply(1.0D / distance);
        RayTraceResult hit = world.rayTraceBlocks(
                new Location(world, eye.getX(), eye.getY(), eye.getZ()),
                direction,
                distance - HIT_MARGIN,
                FluidCollisionMode.NEVER,
                true);
        return hit == null;
    }
}
