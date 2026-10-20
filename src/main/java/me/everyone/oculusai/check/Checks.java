package me.everyone.oculusai.check;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.entity.Player;

public final class Checks implements Service {

    private static final String ALERTS_PERMISSION = "oculusai.alerts";
    private static final long CONFIRM_WINDOW_MS = 5000L;

    private final OculusAI plugin;
    private final List<CombatCheck> checks = new ArrayList<>();
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Deque<Long>>> failures = new ConcurrentHashMap<>();

    public Checks(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        checks.clear();
        checks.add(new WallCheck(plugin));
    }

    @Override
    public void disable() {
        checks.clear();
        lastAlert.clear();
        failures.clear();
    }

    @Override
    public void reload() {
        lastAlert.clear();
        failures.clear();
    }

    public boolean onAttack(Player attacker, PlayerData data, int targetEntityId) {
        if (!plugin.config().checksEnabled() || attacker.hasPermission("oculusai.bypass")) {
            return false;
        }

        long now = System.currentTimeMillis();
        for (CombatCheck check : checks) {
            CheckResult result;
            try {
                result = check.test(attacker, data, targetEntityId);
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.SEVERE,
                        "Check " + check.id() + " failed while reading an attack", failure);
                clearFailures(data.uuid(), check.id());
                continue;
            }
            if (!result.failed()) {
                clearFailures(data.uuid(), check.id());
                continue;
            }

            boolean test = check.testOnly() && plugin.config().checkTestMode();
            int required = test || check.immediate() ? 1 : plugin.config().checkConfirmations();
            if (required > 1 && !confirmed(data.uuid(), check.id(), required, now)) {
                continue;
            }

            if (!check.immediate()) {
                Long previous = lastAlert.get(data.uuid());
                if (previous != null && now - previous < plugin.config().checkAlertCooldownMs()) {
                    clearFailures(data.uuid(), check.id());
                    return result.cancel();
                }
            }
            lastAlert.put(data.uuid(), now);
            try {
                report(data, check, result, test);
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not report check " + check.id(), failure);
            }
            return result.cancel();
        }
        return false;
    }

    private boolean confirmed(UUID uuid, String checkId, int required, long now) {
        Map<String, Deque<Long>> byCheck = failures.computeIfAbsent(
                uuid, key -> new ConcurrentHashMap<>());
        Deque<Long> times = byCheck.computeIfAbsent(checkId, key -> new ArrayDeque<>());
        synchronized (times) {
            long cutoff = now - CONFIRM_WINDOW_MS;
            while (!times.isEmpty() && times.peekFirst() < cutoff) {
                times.removeFirst();
            }
            times.addLast(now);
            if (times.size() < required) {
                return false;
            }
            times.clear();
            return true;
        }
    }

    private void clearFailures(UUID uuid, String checkId) {
        Map<String, Deque<Long>> byCheck = failures.get(uuid);
        if (byCheck == null) {
            return;
        }
        byCheck.remove(checkId);
        if (byCheck.isEmpty()) {
            failures.remove(uuid, byCheck);
        }
    }

    private void report(PlayerData data, CombatCheck check, CheckResult result, boolean test) {
        String detail = Msg.strip(result.detail());
        broadcast(data, check.id(), result.detail(), test);

        String mode = test ? "Test check" : "Check";
        plugin.getLogger().warning(mode + " " + check.id() + " failed for "
                + data.name() + ": " + detail);
        plugin.history().note(data.uuid(), data.name(), "check-" + check.id(), detail);
    }

    /** Aim failures come from the model rather than the packet path, so they
     *  are reported through the same line as every other check. */
    public void alertAim(PlayerData data) {
        String detail = plugin.lang().text("check.reason.aim",
                Map.of("probability", Msg.percent(data.probability())));
        broadcast(data, "aim", detail, false);
    }

    private void broadcast(PlayerData data, String checkId, String detail, boolean test) {
        String checkName = plugin.lang().textOr("check.name." + checkId, checkId);
        String symbol = plugin.config().alertSymbol();
        String message = plugin.config().prefix() + plugin.lang().text(
                test ? "check.test-line" : "check.alert-line",
                Map.of(
                        "player", data.name(),
                        "check", checkName,
                        "detail", detail,
                        "accent", plugin.theme().current().accent(),
                        "symbol", symbol == null ? "" : symbol));

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission(ALERTS_PERMISSION)) {
                online.sendMessage(message);
                online.playSound(online.getLocation(), plugin.config().alertSound(),
                        plugin.config().alertSoundVolume(), plugin.config().alertSoundPitch());
            }
        }
    }

    public void forget(UUID uuid) {
        lastAlert.remove(uuid);
        failures.remove(uuid);
    }
}
