package me.everyone.oculusai.check;

import java.util.ArrayList;
import java.util.List;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class WallCheck implements CombatCheck {

    private static final double BOX_MARGIN = 0.02D;
    private static final double MIN_DISTANCE = 0.1D;
    private static final double HIT_MARGIN = 0.03D;
    private static final double RAY_EPSILON = 0.005D;
    private static final int MAX_TRANSPARENT_PASSES = 24;
    private static final long HISTORY_MS = 500L;
    private static final int MAX_HISTORY_POSITIONS = 8;
    private static final int MAX_GRID_POSITIONS = 3;
    private static final int MAX_EYE_POSITIONS = 4;
    private static final double[] GRID = {0.04D, 0.25D, 0.5D, 0.75D, 0.96D};
    private static final double[] COARSE = {0.15D, 0.5D, 0.85D};

    private final OculusAI plugin;

    public WallCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "wall";
    }

    @Override
    public boolean immediate() {
        return true;
    }

    @Override
    public boolean testOnly() {
        return plugin.config().checkWallTestOnly();
    }

    @Override
    public long cooldownMs() {
        return plugin.config().checkWallCooldownMs();
    }

    @Override
    public CheckResult test(Player attacker, PlayerData data, int targetEntityId) {
        if (!plugin.config().wallCheckEnabled()) {
            return CheckResult.pass();
        }
        if (attacker.isDead() || attacker.getGameMode() == GameMode.SPECTATOR
                || attacker.isInsideVehicle()) {
            return CheckResult.pass();
        }
        if (targetEntityId == attacker.getEntityId()) {
            return CheckResult.pass();
        }

        Player online = findPlayer(targetEntityId);
        if (online != null) {
            if (online.isDead() || online.getGameMode() == GameMode.SPECTATOR
                    || online.isInvisible() || online.isInsideVehicle()
                    || !online.getWorld().equals(attacker.getWorld())) {
                return CheckResult.pass();
            }
        } else if (!plugin.tracker().playerLike(targetEntityId)) {
            return CheckResult.pass();
        }

        Location eye = attacker.getEyeLocation();
        World world = eye.getWorld();
        if (world == null
                || !world.isChunkLoaded(eye.getBlockX() >> 4, eye.getBlockZ() >> 4)) {
            return CheckResult.pass();
        }
        Block eyeBlock = world.getBlockAt(eye);
        if (!eyeBlock.isPassable()
                && eyeBlock.getBoundingBox().contains(eye.getX(), eye.getY(), eye.getZ())) {
            return CheckResult.pass();
        }

        double maxDistance = plugin.config().wallCheckMaxDistance();
        List<BoundingBox> targets = targetBoxes(online, targetEntityId, world, eye.toVector(), maxDistance);
        if (targets.isEmpty()) {
            return CheckResult.pass();
        }

        double nearest = Double.MAX_VALUE;
        for (BoundingBox box : targets) {
            nearest = Math.min(nearest, eye.toVector().distance(box.getCenter()));
        }
        if (nearest < MIN_DISTANCE) {
            return CheckResult.pass();
        }

        Vector look = lookDirection(data);
        List<Vector> eyes = eyePositions(attacker, data, world);
        if (visible(world, eyes, look, targets, maxDistance)) {
            return CheckResult.pass();
        }

        return CheckResult.cancel(plugin.lang().text("check.reason.wall"));
    }

    private Player findPlayer(int entityId) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getEntityId() == entityId) {
                return online;
            }
        }
        return null;
    }

    private List<BoundingBox> targetBoxes(Player online, int entityId, World world,
                                          Vector eye, double maxDistance) {
        List<BoundingBox> boxes = new ArrayList<>();
        if (online != null && online.getBoundingBox().getHeight() > 0.05D) {
            addBox(boxes, online.getBoundingBox());
        }

        List<EntityTracker.Tracked> recent = plugin.tracker().recent(entityId);
        long now = System.currentTimeMillis();
        if (online == null) {
            for (int i = recent.size() - 1; i >= 0; i--) {
                BoundingBox current = trackedBox(recent.get(i), world, now);
                if (current != null) {
                    addBox(boxes, current);
                    break;
                }
            }
        }
        int start = Math.max(0, recent.size() - MAX_HISTORY_POSITIONS);
        for (int i = start; i < recent.size(); i++) {
            BoundingBox box = trackedBox(recent.get(i), world, now);
            if (box != null) {
                addBox(boxes, box);
            }
        }

        double range = maxDistance + 1.0D;
        double limit = range * range;
        List<BoundingBox> within = new ArrayList<>(boxes.size());
        for (BoundingBox box : boxes) {
            if (!world.isChunkLoaded((int) Math.floor(box.getCenterX()) >> 4,
                    (int) Math.floor(box.getCenterZ()) >> 4)) {
                continue;
            }
            if (eye.distanceSquared(box.getCenter()) <= limit) {
                within.add(box);
            }
        }
        return within;
    }

    private BoundingBox trackedBox(EntityTracker.Tracked tracked, World world, long now) {
        if (now - tracked.at() > HISTORY_MS || tracked.worldId() == null
                || !tracked.worldId().equals(world.getUID())
                || !Double.isFinite(tracked.width()) || tracked.width() <= 0.01D
                || !Double.isFinite(tracked.height()) || tracked.height() <= 0.05D) {
            return null;
        }
        double halfWidth = tracked.width() / 2.0D;
        return new BoundingBox(
                tracked.x() - halfWidth, tracked.y(), tracked.z() - halfWidth,
                tracked.x() + halfWidth, tracked.y() + tracked.height(),
                tracked.z() + halfWidth);
    }

    private void addBox(List<BoundingBox> boxes, BoundingBox box) {
        BoundingBox expanded = box.clone().expand(BOX_MARGIN);
        for (BoundingBox existing : boxes) {
            if (existing.getMinX() == expanded.getMinX()
                    && existing.getMinY() == expanded.getMinY()
                    && existing.getMinZ() == expanded.getMinZ()
                    && existing.getMaxX() == expanded.getMaxX()
                    && existing.getMaxY() == expanded.getMaxY()
                    && existing.getMaxZ() == expanded.getMaxZ()) {
                return;
            }
        }
        boxes.add(expanded);
    }

    private List<Vector> eyePositions(Player attacker, PlayerData data, World world) {
        List<Vector> eyes = new ArrayList<>();
        Vector current = attacker.getEyeLocation().toVector();
        eyes.add(current);

        double eyeHeight = attacker.getEyeHeight();
        List<EntityTracker.Tracked> recent = plugin.tracker().recent(data.entityId());
        long now = System.currentTimeMillis();
        int start = Math.max(0, recent.size() - MAX_EYE_POSITIONS);
        for (int i = start; i < recent.size(); i++) {
            EntityTracker.Tracked tracked = recent.get(i);
            if (now - tracked.at() > HISTORY_MS || tracked.worldId() == null
                    || !tracked.worldId().equals(world.getUID())) {
                continue;
            }
            Vector eye = new Vector(tracked.x(), tracked.y() + eyeHeight, tracked.z());
            if (eye.distanceSquared(current) < 1.0E-4D
                    || !world.getBlockAt(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ()).isPassable()) {
                continue;
            }
            eyes.add(eye);
        }
        return eyes;
    }

    private Vector lookDirection(PlayerData data) {
        double yaw = Math.toRadians(data.lastYaw());
        double pitch = Math.toRadians(data.lastPitch());
        double cosPitch = Math.cos(pitch);
        return new Vector(-cosPitch * Math.sin(yaw), -Math.sin(pitch), cosPitch * Math.cos(yaw));
    }

    private boolean visible(World world, List<Vector> eyes, Vector look,
                            List<BoundingBox> targets, double maxDistance) {
        double reach = maxDistance + 2.0D;
        double limit = reach * reach;
        Vector currentEye = eyes.get(0);
        BoundingBox current = targets.get(0);

        RayTraceResult closestHit = null;
        double closestDistance = Double.MAX_VALUE;
        for (BoundingBox box : targets) {
            if (currentEye.distanceSquared(box.getCenter()) > limit) {
                continue;
            }
            RayTraceResult hit = box.rayTrace(currentEye, look, reach);
            if (hit == null) {
                continue;
            }
            double distance = currentEye.distanceSquared(hit.getHitPosition());
            if (distance < closestDistance) {
                closestDistance = distance;
                closestHit = hit;
            }
        }
        if (closestHit != null
                && closedDoorInPath(world, currentEye, closestHit.getHitPosition())) {
            return false;
        }
        if (!visibleFrom(world, currentEye, look, current, reach)
                && closedDoorInPath(world, currentEye, current.getCenter())) {
            return false;
        }

        for (Vector eye : eyes) {
            for (BoundingBox box : targets) {
                if (eye.distanceSquared(box.getCenter()) > limit) {
                    continue;
                }
                RayTraceResult hit = box.rayTrace(eye, look, reach);
                if (hit != null && clear(world, eye, hit.getHitPosition())) {
                    return true;
                }
            }
        }

        List<BoundingBox> gridTargets = gridTargets(targets);
        for (BoundingBox box : gridTargets) {
            for (Vector point : samples(box, true)) {
                if (clear(world, eyes.get(0), point)) {
                    return true;
                }
            }
        }
        for (int i = 1; i < eyes.size(); i++) {
            for (BoundingBox box : gridTargets) {
                for (Vector point : samples(box, false)) {
                    if (clear(world, eyes.get(i), point)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean visibleFrom(World world, Vector eye, Vector look,
                                BoundingBox box, double reach) {
        RayTraceResult hit = box.rayTrace(eye, look, reach);
        if (hit != null && clear(world, eye, hit.getHitPosition())) {
            return true;
        }
        for (Vector point : samples(box, true)) {
            if (clear(world, eye, point)) {
                return true;
            }
        }
        return false;
    }

    private boolean closedDoorInPath(World world, Vector eye, Vector point) {
        Block block = firstOccluder(world, eye, point);
        return block != null
                && block.getBlockData() instanceof Openable openable
                && !openable.isOpen();
    }

    private List<BoundingBox> gridTargets(List<BoundingBox> targets) {
        if (targets.size() <= MAX_GRID_POSITIONS + 1) {
            return targets;
        }
        List<BoundingBox> grid = new ArrayList<>(MAX_GRID_POSITIONS + 1);
        grid.add(targets.get(0));
        grid.addAll(targets.subList(targets.size() - MAX_GRID_POSITIONS, targets.size()));
        return grid;
    }

    private List<Vector> samples(BoundingBox box, boolean fine) {
        double[] fractions = fine ? GRID : COARSE;
        List<Vector> points = new ArrayList<>(fractions.length * fractions.length * fractions.length + 1);
        points.add(box.getCenter());
        for (double y : fractions) {
            for (double x : fractions) {
                for (double z : fractions) {
                    points.add(new Vector(lerp(box.getMinX(), box.getMaxX(), x),
                            lerp(box.getMinY(), box.getMaxY(), y),
                            lerp(box.getMinZ(), box.getMaxZ(), z)));
                }
            }
        }
        return points;
    }

    private double lerp(double from, double to, double fraction) {
        return from + (to - from) * fraction;
    }

    private boolean clear(World world, Vector eye, Vector point) {
        return firstOccluder(world, eye, point) == null;
    }

    private Block firstOccluder(World world, Vector eye, Vector point) {
        Vector direction = point.clone().subtract(eye);
        double distance = direction.length();
        if (distance <= HIT_MARGIN) {
            return null;
        }
        direction.multiply(1.0D / distance);
        double remaining = distance - HIT_MARGIN;
        Vector start = eye.clone();
        for (int i = 0; i < MAX_TRANSPARENT_PASSES; i++) {
            RayTraceResult hit = world.rayTraceBlocks(
                    new Location(world, start.getX(), start.getY(), start.getZ()),
                    direction, remaining, FluidCollisionMode.NEVER, false);
            if (hit == null || hit.getHitBlock() == null) {
                return null;
            }
            Block block = hit.getHitBlock();
            if (occludes(block)) {
                return block;
            }

            Vector hitPosition = hit.getHitPosition();
            double advance = hitPosition.distance(start);
            if (advance < RAY_EPSILON) {
                advance = RAY_EPSILON;
            }
            remaining -= advance + RAY_EPSILON;
            if (remaining <= 0.0D) {
                return null;
            }
            start = hitPosition.add(direction.clone().multiply(RAY_EPSILON));
        }
        return null;
    }

    private boolean occludes(Block block) {
        if (block.getBlockData() instanceof Openable openable) {
            return !openable.isOpen();
        }
        if (block.isPassable()) {
            return false;
        }
        Material material = block.getType();
        String name = material.name();
        return !(name.equals("GLASS")
                || name.endsWith("_STAINED_GLASS")
                || name.endsWith("GLASS_PANE"))
                && !name.endsWith("_LEAVES")
                && !name.endsWith("_BARS")
                && !name.endsWith("_FENCE")
                && !name.endsWith("_GRATE")
                && !name.endsWith("SPAWNER")
                && !name.endsWith("COPPER_BULB")
                && !name.equals("ICE")
                && !name.equals("BLUE_ICE")
                && !name.equals("FROSTED_ICE")
                && !name.equals("SLIME_BLOCK")
                && !name.equals("HONEY_BLOCK")
                && !name.equals("BEACON")
                && !name.equals("BARRIER")
                && !name.equals("VAULT")
                && !name.equals("LIGHT")
                && !name.equals("STRUCTURE_VOID")
                && !name.equals("END_GATEWAY")
                && !name.equals("END_PORTAL")
                && !name.equals("NETHER_PORTAL")
                && !name.equals("POWDER_SNOW")
                && !name.equals("BUBBLE_COLUMN");
    }
}
