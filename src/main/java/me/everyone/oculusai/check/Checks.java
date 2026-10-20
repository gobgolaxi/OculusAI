package me.everyone.oculusai.check;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.entity.Player;

/** The list of checks and the two moments they are tested at.
 *
 * <p>Packet checks run on an attack; player checks run on every reading the
 * model produces. Both hand a failed result to {@link Alerts}, which owns the
 * confirmations, cooldown, message, sound and history. */
public final class Checks implements Service {

    private final OculusAI plugin;
    private final List<CombatCheck> combatChecks = new ArrayList<>();
    private final List<PlayerCheck> playerChecks = new ArrayList<>();

    public Checks(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        reload();
    }

    @Override
    public void disable() {
        combatChecks.clear();
        playerChecks.clear();
    }

    @Override
    public void reload() {
        combatChecks.clear();
        playerChecks.clear();
        combatChecks.add(new HitBoxCheck(plugin));
        combatChecks.add(new WallCheck(plugin));
        playerChecks.add(new AimCheck(plugin));
    }

    /** Every registered check, in display order. Menus use this instead of
     *  keeping a second hard-coded list of ids. */
    public List<Check> all() {
        List<Check> result = new ArrayList<>(combatChecks);
        result.addAll(playerChecks);
        return List.copyOf(result);
    }

    /** Looks a check up by id, or returns null when it is not registered. */
    public Check find(String id) {
        for (Check check : all()) {
            if (check.id().equalsIgnoreCase(id)) {
                return check;
            }
        }
        return null;
    }

    /** Tests every packet check for an attack. Returns whether the hit should
     *  be cancelled. */
    public boolean onAttack(Player attacker, PlayerData data, int targetEntityId) {
        if (!plugin.config().analysisEnabled() || !plugin.config().checksEnabled()
                || attacker.hasPermission("oculusai.bypass")) {
            return false;
        }

        for (CombatCheck check : combatChecks) {
            CheckResult result = test(check, attacker, data, targetEntityId);
            if (result == null || !result.failed()) {
                if (result != null) {
                    plugin.alerts().reset(data.uuid(), check.id());
                }
                continue;
            }

            boolean test = check.testOnly() && plugin.config().checkTestMode();
            if (plugin.alerts().raise(data, check, result, test) == Alerts.Outcome.NONE) {
                continue;
            }
            return !test && result.cancel();
        }
        return false;
    }

    /** Tests the checks that read the model's output. Returns whether a real
     *  alert was sent, so the caller can decide about punishment without
     *  repeating the confirmation and cooldown rules.
     *
     *  <p>{@code checks.enabled} is the packet-check switch, as it was before
     *  the aim check moved here: turning off wall checks must not quietly turn
     *  off the neural one. Aim has its own switch. */
    public boolean onPrediction(PlayerData data) {
        Player player = data.player();
        if (!plugin.config().analysisEnabled() || !player.isOnline()
                || player.hasPermission("oculusai.bypass")) {
            return false;
        }

        boolean alerted = false;
        for (PlayerCheck check : playerChecks) {
            CheckResult result = test(check, data);
            if (result == null || !result.failed()) {
                if (result != null) {
                    plugin.alerts().reset(data.uuid(), check.id());
                }
                continue;
            }

            boolean test = check.testOnly() && plugin.config().checkTestMode();
            if (plugin.alerts().raise(data, check, result, test) == Alerts.Outcome.SENT) {
                alerted |= !test;
            }
        }
        return alerted;
    }

    private CheckResult test(CombatCheck check, Player attacker, PlayerData data, int targetEntityId) {
        try {
            return check.test(attacker, data, targetEntityId);
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.SEVERE,
                    "Check " + check.id() + " failed while reading an attack", failure);
            plugin.alerts().reset(data.uuid(), check.id());
            return null;
        }
    }

    private CheckResult test(PlayerCheck check, PlayerData data) {
        try {
            return check.test(data);
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.SEVERE,
                    "Check " + check.id() + " failed while reading a prediction", failure);
            plugin.alerts().reset(data.uuid(), check.id());
            return null;
        }
    }

    public void forget(UUID uuid) {
        plugin.alerts().forget(uuid);
    }
}
