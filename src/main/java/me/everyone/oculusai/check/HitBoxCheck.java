package me.everyone.oculusai.check;

import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

/** Cancels hits on a target whose server-side hitbox is larger than allowed.
 *
 * <p>The dimensions come from the live player when possible, because a pose or
 * scale change can happen after the tracker's last tick. NPC dummies fall back
 * to the dimensions recorded by {@link EntityTracker}. */
public final class HitBoxCheck implements CombatCheck {

    private static final double EPSILON = 1.0E-3D;
    private static final long MAX_TRACK_AGE_MS = 1000L;

    private final OculusAI plugin;

    public HitBoxCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "hitbox";
    }

    @Override
    public boolean immediate() {
        return true;
    }

    @Override
    public boolean testOnly() {
        return plugin.config().checkHitboxTestOnly();
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

        double width;
        double height;
        Player target = plugin.tracker().player(targetEntityId);
        if (target != null) {
            if (target.isDead() || target.getGameMode() == GameMode.SPECTATOR
                    || !target.getWorld().equals(attacker.getWorld())) {
                return CheckResult.pass();
            }
            BoundingBox bounds = target.getBoundingBox();
            width = Math.max(bounds.getWidthX(), bounds.getWidthZ());
            height = bounds.getHeight();
        } else {
            EntityTracker.Tracked tracked = plugin.tracker().get(targetEntityId);
            if (tracked == null
                    || System.currentTimeMillis() - tracked.at() > MAX_TRACK_AGE_MS
                    || tracked.worldId() == null
                    || !tracked.worldId().equals(attacker.getWorld().getUID())) {
                return CheckResult.pass();
            }
            width = tracked.width();
            height = tracked.height();
        }

        if (!Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0.0D || height <= 0.0D) {
            return CheckResult.pass();
        }

        double maxWidth = plugin.config().hitboxCheckMaxWidth();
        double maxHeight = plugin.config().hitboxCheckMaxHeight();
        if (width <= maxWidth + EPSILON && height <= maxHeight + EPSILON) {
            return CheckResult.pass();
        }

        return CheckResult.cancel(plugin.lang().text("check.reason.hitbox", Map.of(
                "width", Msg.round(width, 2),
                "height", Msg.round(height, 2),
                "max-width", Msg.round(maxWidth, 2),
                "max-height", Msg.round(maxHeight, 2))));
    }
}
