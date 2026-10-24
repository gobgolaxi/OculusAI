package me.everyone.oculusai.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final double MIN_DISTANCE_SQUARED = MIN_DISTANCE * MIN_DISTANCE;
    private static final double HIT_MARGIN = 0.03D;
    private static final double RAY_EPSILON = 0.005D;
    private static final int MAX_TRANSPARENT_PASSES = 16;
    private static final long HISTORY_MS = 500L;
    private static final int MAX_HISTORY_POSITIONS = 6;
    private static final int MAX_GRID_POSITIONS = 1;
    private static final int MAX_EYE_POSITIONS = 4;
    private static final int MAX_GRID_EYES = 2;
    // Four points per axis instead of five: the direct bounding-box ray below
    // catches the normal case, so the grid is only a fallback for edges, and
    // 64 samples per box is already generous for a 0.6-block target.
    private static final double[] GRID = {0.05D, 0.35D, 0.65D, 0.95D};
    private static final double[] COARSE = {0.15D, 0.5D, 0.85D};

    /** Material occlusion is state-independent for everything except doors,
     *  trapdoors and fence gates, which are checked through {@link Openable}
     *  before this cache is consulted. */
    private static final Map<Material, Boolean> OCCLUDING = new ConcurrentHashMap<>();

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

    /** Reads blocks: the whole check is a ray through them. */
    @Override
    public boolean readsBlocks() {
        return true;
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

        double maxDistance = plugin.config().wallCheckMaxDistance();
        Vector eyeVector = eye.toVector();
        List<BoundingBox> targets = targetBoxes(online, targetEntityId, world,
                eyeVector, maxDistance);
        if (targets.isEmpty()) {
            return CheckResult.pass();
        }

        double nearest = Double.MAX_VALUE;
        for (BoundingBox box : targets) {
            nearest = Math.min(nearest, eyeVector.distanceSquared(box.getCenter()));
        }
        if (nearest < MIN_DISTANCE_SQUARED) {
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
        return plugin.tracker().player(entityId);
    }

    private List<BoundingBox> targetBoxes(Player online, int entityId, World world,
                                          Vector eye, double maxDistance) {
        List<BoundingBox> boxes = new ArrayList<>(8);
        if (online != null && online.getBoundingBox().getHeight() > 0.05D) {
            addBox(boxes, online.getBoundingBox());
        }

        List<EntityTracker.Tracked> recent = plugin.tracker().recent(entityId);
        long now = System.currentTimeMillis();
        UUID worldId = world.getUID();
        if (online == null) {
            for (int i = recent.size() - 1; i >= 0; i--) {
                BoundingBox current = trackedBox(recent.get(i), worldId, now);
                if (current != null) {
                    addBox(boxes, current);
                    break;
                }
            }
        }
        int start = Math.max(0, recent.size() - MAX_HISTORY_POSITIONS);
        for (int i = start; i < recent.size(); i++) {
            BoundingBox box = trackedBox(recent.get(i), worldId, now);
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

    private BoundingBox trackedBox(EntityTracker.Tracked tracked, UUID worldId, long now) {
        if (now - tracked.at() > HISTORY_MS || tracked.worldId() == null
                || !tracked.worldId().equals(worldId)
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
        BoundingBox expanded = new BoundingBox(
                box.getMinX() - BOX_MARGIN, box.getMinY() - BOX_MARGIN,
                box.getMinZ() - BOX_MARGIN, box.getMaxX() + BOX_MARGIN,
                box.getMaxY() + BOX_MARGIN, box.getMaxZ() + BOX_MARGIN);
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
        List<Vector> eyes = new ArrayList<>(MAX_EYE_POSITIONS + 1);
        Vector current = attacker.getEyeLocation().toVector();
        eyes.add(current);

        double eyeHeight = attacker.getEyeHeight();
        List<EntityTracker.Tracked> recent = plugin.tracker().recent(data.entityId());
        long now = System.currentTimeMillis();
        UUID worldId = world.getUID();
        int start = Math.max(0, recent.size() - MAX_EYE_POSITIONS);
        for (int i = start; i < recent.size(); i++) {
            EntityTracker.Tracked tracked = recent.get(i);
            if (now - tracked.at() > HISTORY_MS || tracked.worldId() == null
                    || !tracked.worldId().equals(worldId)) {
                continue;
            }
            Vector eye = new Vector(tracked.x(), tracked.y() + eyeHeight, tracked.z());
            if (eye.distanceSquared(current) < 1.0E-4D) {
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

        // A closed door over the attacker's eye used to make the whole check
        // return "pass". It is an occluder like any other block now, so being
        // inside a door no longer turns Wall off for that hit.
        boolean[] blocked = new boolean[eyes.size()];
        boolean anyClearEye = false;
        for (int i = 0; i < eyes.size(); i++) {
            blocked[i] = eyeOccluded(world, eyes.get(i));
            anyClearEye |= !blocked[i];
        }
        if (!anyClearEye) {
            // Every eye position is inside an occluder (the old code returned
            // "pass" for this case, which is why attackers inside a closed
            // door were never wall-checked).
            return false;
        }

        // First the exact ray the attacker used. This is the common case and
        // costs one block trace per target instead of the old duplicate pass
        // followed by a full sample grid. A closed door is an occluder here,
        // so no separate door walk is needed.
        for (int i = 0; i < eyes.size(); i++) {
            if (blocked[i]) {
                continue;
            }
            Vector eye = eyes.get(i);
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

        // Edge hits (target moving between ticks, or the ray passing beside
        // the sampled box) get a smaller grid. The old grid ran for every
        // history eye and every target box, which is what made a busy fight
        // expensive. The direct rays above already cover all history eyes;
        // this fallback only needs the current eye and the most recent one,
        // and only the current and latest target boxes.
        List<SampleBox> grid = sampleBoxes(targets);
        int gridEyes = Math.min(eyes.size(), MAX_GRID_EYES);
        for (int i = 0; i < gridEyes; i++) {
            if (blocked[i]) {
                continue;
            }
            Vector eye = eyes.get(i);
            boolean fine = i == 0;
            for (SampleBox target : grid) {
                List<Vector> points = fine ? target.fine() : target.coarse();
                for (Vector point : points) {
                    if (eye.distanceSquared(point) > limit) {
                        continue;
                    }
                    if (clear(world, eye, point)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean eyeOccluded(World world, Vector eye) {
        Block block = world.getBlockAt(
                (int) Math.floor(eye.getX()),
                (int) Math.floor(eye.getY()),
                (int) Math.floor(eye.getZ()));
        return occludes(block);
    }

    private List<SampleBox> sampleBoxes(List<BoundingBox> targets) {
        List<BoundingBox> selected;
        if (targets.size() <= MAX_GRID_POSITIONS + 1) {
            selected = targets;
        } else {
            selected = new ArrayList<>(MAX_GRID_POSITIONS + 1);
            selected.add(targets.get(0));
            selected.addAll(targets.subList(targets.size() - MAX_GRID_POSITIONS, targets.size()));
        }

        List<SampleBox> boxes = new ArrayList<>(selected.size());
        for (BoundingBox box : selected) {
            boxes.add(new SampleBox(box, samples(box, true), samples(box, false)));
        }
        return boxes;
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
        Location origin = new Location(world, start.getX(), start.getY(), start.getZ());
        for (int i = 0; i < MAX_TRANSPARENT_PASSES; i++) {
            origin.setX(start.getX());
            origin.setY(start.getY());
            origin.setZ(start.getZ());
            RayTraceResult hit = world.rayTraceBlocks(
                    origin, direction, remaining, FluidCollisionMode.NEVER, false);
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

        Material material = block.getType();
        Boolean cached = OCCLUDING.get(material);
        if (cached != null) {
            return cached;
        }
        boolean occludes = occludesMaterial(block);
        OCCLUDING.put(material, occludes);
        return occludes;
    }

    private static boolean occludesMaterial(Block block) {
        if (block.isPassable()) {
            return false;
        }
        String name = block.getType().name();
        return !(name.equals("GLASS")
                || name.endsWith("_GLASS")
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

    private record SampleBox(BoundingBox box, List<Vector> fine, List<Vector> coarse) {
    }
}
