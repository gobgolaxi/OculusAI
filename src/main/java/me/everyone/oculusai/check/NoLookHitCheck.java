package me.everyone.oculusai.check;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Cancels an attack when the attacker's look ray does not touch the target.
 *
 * <p>Vanilla only sends an attack packet after the client's crosshair is on an
 * entity, so a packet without that line is either a desynchronised client or a
 * cheat. The check uses the target's recent boxes as well as the current one,
 * and expands them by a configurable margin, because the target may have moved
 * between its last movement packet and the attack. */
public final class NoLookHitCheck implements CombatCheck {

    private static final double MIN_DISTANCE = 0.1D;
    private static final double MIN_DISTANCE_SQUARED = MIN_DISTANCE * MIN_DISTANCE;
    private static final long HISTORY_MS = 500L;
    private static final int MAX_HISTORY_BOXES = 6;

    private final OculusAI plugin;

    public NoLookHitCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "nolookhit";
    }

    @Override
    public boolean immediate() {
        return true;
    }

    @Override
    public boolean testOnly() {
        return plugin.config().checkNoLookHitTestOnly();
    }

    @Override
    public long cooldownMs() {
        return plugin.config().checkNoLookHitCooldownMs();
    }

    @Override
    public CheckResult test(Player attacker, PlayerData data, int targetEntityId) {
        if (!plugin.config().noLookHitCheckEnabled()) {
            return CheckResult.pass();
        }
        if (attacker.isDead() || attacker.getGameMode() == GameMode.SPECTATOR
                || attacker.isInsideVehicle() || targetEntityId == attacker.getEntityId()) {
            return CheckResult.pass();
        }

        Player online = plugin.tracker().player(targetEntityId);
        if (online != null) {
            if (online.isDead() || online.getGameMode() == GameMode.SPECTATOR
                    || !online.getWorld().equals(attacker.getWorld())) {
                return CheckResult.pass();
            }
        }

        Location eye = attacker.getEyeLocation();
        World world = eye.getWorld();
        if (world == null
                || !world.isChunkLoaded(eye.getBlockX() >> 4, eye.getBlockZ() >> 4)) {
            return CheckResult.pass();
        }

        Vector look = lookDirection(data);
        if (look == null) {
            return CheckResult.pass();
        }

        double maxDistance = plugin.config().noLookHitMaxDistance();
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

        double reach = maxDistance + 1.0D;
        double limit = reach * reach;
        for (BoundingBox box : targets) {
            if (eyeVector.distanceSquared(box.getCenter()) > limit) {
                continue;
            }
            if (box.rayTrace(eyeVector, look, reach) != null) {
                return CheckResult.pass();
            }
        }

        // The attack packet carries no rotation of its own. A legitimate flick
        // can therefore arrive one packet after the server's last look, when
        // the crosshair has already moved past the box. Treat the target as
        // aimed at while it stays inside a small cone; a hit aimed away from
        // the target is still far outside it.
        double minDot = Math.cos(Math.toRadians(plugin.config().noLookHitMaxAngle()));
        for (BoundingBox box : targets) {
            if (aimedAt(eyeVector, look, box, minDot)) {
                return CheckResult.pass();
            }
        }

        return CheckResult.cancel(plugin.lang().text("check.reason.nolookhit"));
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

    private Vector lookDirection(PlayerData data) {
        double yaw = Math.toRadians(data.lastYaw());
        double pitch = Math.toRadians(data.lastPitch());
        double cosPitch = Math.cos(pitch);
        Vector look = new Vector(-cosPitch * Math.sin(yaw), -Math.sin(pitch),
                cosPitch * Math.cos(yaw));
        if (!Double.isFinite(look.getX()) || !Double.isFinite(look.getY())
                || !Double.isFinite(look.getZ()) || look.lengthSquared() < 1.0E-6D) {
            return null;
        }
        return look.normalize();
    }

    private List<BoundingBox> targetBoxes(Player online, int entityId, World world,
                                          Vector eye, double maxDistance) {
        List<BoundingBox> boxes = new ArrayList<>(MAX_HISTORY_BOXES + 1);
        double margin = plugin.config().noLookHitHitboxMargin();

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
