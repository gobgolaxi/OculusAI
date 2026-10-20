package me.everyone.oculusai.check;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.detection.RotationSample;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Cancels an attack that does not touch the target's real hitbox.
 *
 * <p>This is the hitbox check for expanded-hitbox and no-look attacks. The
 * server cannot see the client's hitbox, but it can see the attack packet and
 * the last look direction. If that ray never touches the target's actual
 * server-side box, the client attacked something that is not there. Recent
 * target boxes cover the movement between the last position packet and the
 * attack; no neural data is involved. A single borderline miss is not enough
 * to act, so the normal confirmation rule is used.
 *
 * <p>The attack packet does not carry a rotation. A legitimate flick can
 * therefore be sent before the look packet for the same client tick, leaving
 * the server with the previous look. The target boxes and the attacker's eye
 * are also sampled a tick behind the client. The check therefore accepts the
 * same hit through a small, configurable cone around the real box instead of
 * cancelling a normal hit on a one-packet timing difference. */
public final class HitBoxCheck implements CombatCheck {

    private static final double MIN_DISTANCE = 0.1D;
    private static final double MIN_DISTANCE_SQUARED = MIN_DISTANCE * MIN_DISTANCE;
    private static final long HISTORY_MS = 1000L;
    private static final long EYE_HISTORY_MS = 500L;
    private static final int MAX_HISTORY_BOXES = 20;
    private static final int MAX_EYE_POSITIONS = 8;

    private final OculusAI plugin;

    public HitBoxCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "hitbox";
    }

    @Override
    public boolean testOnly() {
        return plugin.config().checkHitboxTestOnly();
    }

    /** A hitbox miss is based on the server's view of a packet that carries no
     *  rotation. One borderline miss is not enough to cancel a normal hit;
     *  two in a row are still a repeatable geometry failure. */
    @Override
    public int confirmations() {
        return Math.max(2, plugin.config().checkConfirmations());
    }

    @Override
    public long cooldownMs() {
        return plugin.config().checkHitboxCooldownMs();
    }

    @Override
    public CheckResult test(Player attacker, PlayerData data, int targetEntityId) {
        if (!plugin.config().hitboxCheckEnabled()) {
            return CheckResult.pass();
        }
        if (attacker.isDead() || attacker.getGameMode() == GameMode.SPECTATOR
                || attacker.isInsideVehicle() || targetEntityId == attacker.getEntityId()) {
            return CheckResult.pass();
        }

        Player online = plugin.tracker().player(targetEntityId);
        if (online != null) {
            if (online.isDead() || online.getGameMode() == GameMode.SPECTATOR
                    || online.isInsideVehicle()
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

        List<Vector> looks = lookDirections(data);
        if (looks.isEmpty()) {
            return CheckResult.pass();
        }

        double maxDistance = plugin.config().hitboxMaxDistance();
        Vector eyeVector = eye.toVector();
        List<BoundingBox> targets = targetBoxes(online, targetEntityId, world);
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

        double reach = maxDistance + 2.0D;
        List<Vector> eyes = eyePositions(attacker, data, world);
        if (touches(eyes, looks, targets, reach)) {
            return CheckResult.pass();
        }

        // The attack packet carries no rotation of its own. A normal flick can
        // be sent one packet before the look that goes with it, so the latest
        // server look can be a tick behind the crosshair. Accept a small cone
        // around the real box; an attack aimed away from the target is still
        // far outside it. This is a geometric tolerance, not a size shortcut.
        double minDot = Math.cos(Math.toRadians(plugin.config().hitboxMaxAngle()));
        if (aimedAt(eyes, looks, targets, reach, minDot)) {
            return CheckResult.pass();
        }

        return CheckResult.cancel(plugin.lang().text("check.reason.hitbox"));
    }

    private List<Vector> lookDirections(PlayerData data) {
        List<Vector> looks = new ArrayList<>(2);
        addLook(looks, data.lastYaw(), data.lastPitch());

        // Keep the previous reading as well. It covers a hit that was sent
        // before the newest look packet but after the target moved.
        List<RotationSample> window = data.snapshotWindow(2);
        if (window != null && window.size() >= 2) {
            RotationSample previous = window.get(window.size() - 2);
            addLook(looks, previous.yaw(), previous.pitch());

            // The client may have sent the attack before this tick's look
            // packet. Continue the current rotation for one sample: a normal
            // flick lands where the next packet is about to point, while a
            // hit aimed away from the target still does not.
            float yaw = data.lastYaw();
            float pitch = data.lastPitch();
            float yawStep = (float) Math.IEEEremainder(yaw - previous.yaw(), 360.0D);
            float pitchStep = pitch - previous.pitch();
            addLook(looks, yaw + yawStep,
                    (float) clamp(pitch + pitchStep, -90.0D, 90.0D));
        }
        return looks;
    }

    private void addLook(List<Vector> looks, float yawDegrees, float pitchDegrees) {
        Vector look = lookDirection(yawDegrees, pitchDegrees);
        if (look == null) {
            return;
        }
        for (Vector existing : looks) {
            if (existing.distanceSquared(look) < 1.0E-6D) {
                return;
            }
        }
        looks.add(look);
    }

    private Vector lookDirection(float yawDegrees, float pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        Vector look = new Vector(-cosPitch * Math.sin(yaw), -Math.sin(pitch),
                cosPitch * Math.cos(yaw));
        if (!Double.isFinite(look.getX()) || !Double.isFinite(look.getY())
                || !Double.isFinite(look.getZ()) || look.lengthSquared() < 1.0E-6D) {
            return null;
        }
        return look.normalize();
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
            if (now - tracked.at() > EYE_HISTORY_MS || tracked.worldId() == null
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

    private boolean touches(List<Vector> eyes, List<Vector> looks,
                            List<BoundingBox> targets, double reach) {
        double limit = reach * reach;
        for (Vector eye : eyes) {
            for (Vector look : looks) {
                for (BoundingBox box : targets) {
                    if (eye.distanceSquared(box.getCenter()) > limit) {
                        continue;
                    }
                    if (box.rayTrace(eye, look, reach) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean aimedAt(List<Vector> eyes, List<Vector> looks,
                            List<BoundingBox> targets, double reach, double minDot) {
        double limit = reach * reach;
        for (Vector eye : eyes) {
            for (Vector look : looks) {
                for (BoundingBox box : targets) {
                    if (eye.distanceSquared(box.getCenter()) > limit) {
                        continue;
                    }
                    if (aimedAt(eye, look, box, minDot)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean aimedAt(Vector eye, Vector look, BoundingBox box, double minDot) {
        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX());
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY());
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ());
        Vector to = new Vector(x - eye.getX(), y - eye.getY(), z - eye.getZ());
        double length = to.length();
        if (length < MIN_DISTANCE) {
            return true;
        }
        return look.dot(to) / length >= minDot;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<BoundingBox> targetBoxes(Player online, int entityId, World world) {
        List<BoundingBox> boxes = new ArrayList<>(MAX_HISTORY_BOXES + 2);
        double margin = plugin.config().hitboxMargin();

        if (online != null && online.getBoundingBox().getHeight() > 0.05D) {
            addBox(boxes, online.getBoundingBox(), margin);
        }

        EntityTracker.Tracked latest = plugin.tracker().get(entityId);
        long now = System.currentTimeMillis();
        UUID worldId = world.getUID();
        BoundingBox current = trackedBox(latest, worldId, now);
        if (current != null) {
            addBox(boxes, current, margin);
        }

        List<EntityTracker.Tracked> recent = plugin.tracker().recent(entityId);
        int start = Math.max(0, recent.size() - MAX_HISTORY_BOXES);
        for (int i = start; i < recent.size(); i++) {
            BoundingBox box = trackedBox(recent.get(i), worldId, now);
            if (box != null) {
                addBox(boxes, box, margin);
            }
        }

        // Distance is checked against each candidate eye in touches/aimedAt.
        // Filtering here against the current eye would drop a box that the
        // attacker was still able to reach a moment ago while moving.
        List<BoundingBox> within = new ArrayList<>(boxes.size());
        for (BoundingBox box : boxes) {
            if (world.isChunkLoaded((int) Math.floor(box.getCenterX()) >> 4,
                    (int) Math.floor(box.getCenterZ()) >> 4)) {
                within.add(box);
            }
        }
        return within;
    }

    private BoundingBox trackedBox(EntityTracker.Tracked tracked, UUID worldId, long now) {
        if (tracked == null
                || now - tracked.at() > HISTORY_MS
                || tracked.worldId() == null
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

    private void addBox(List<BoundingBox> boxes, BoundingBox box, double margin) {
        BoundingBox expanded = new BoundingBox(
                box.getMinX() - margin, box.getMinY() - margin,
                box.getMinZ() - margin, box.getMaxX() + margin,
                box.getMaxY() + margin, box.getMaxZ() + margin);
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
}
