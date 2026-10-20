package me.everyone.oculusai.check;

import java.util.ArrayList;
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

    private final OculusAI plugin;
    private final List<CombatCheck> checks = new ArrayList<>();
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();

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
    }

    @Override
    public void reload() {
        lastAlert.clear();
    }

    public void onAttack(Player attacker, PlayerData data, int targetEntityId) {
        if (!plugin.config().checksEnabled() || attacker.hasPermission("oculusai.bypass")) {
            return;
        }

        long now = System.currentTimeMillis();
        for (CombatCheck check : checks) {
            CheckResult result;
            try {
                result = check.test(attacker, data, targetEntityId);
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.SEVERE,
                        "Check " + check.id() + " failed while reading an attack", failure);
                continue;
            }
            if (!result.failed()) {
                continue;
            }

            Long previous = lastAlert.get(data.uuid());
            if (previous != null && now - previous < plugin.config().checkAlertCooldownMs()) {
                return;
            }
            lastAlert.put(data.uuid(), now);
            report(data, check, result);
            return;
        }
    }

    private void report(PlayerData data, CombatCheck check, CheckResult result) {
        String detail = Msg.strip(result.detail());
        String message = plugin.config().prefix() + plugin.lang().text(
                plugin.config().checkTestMode() ? "check.test-alert" : "check.alert",
                Map.of(
                        "player", data.name(),
                        "check", check.id(),
                        "detail", result.detail()));

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("oculusai.alerts")) {
                online.sendMessage(message);
                online.playSound(online.getLocation(), plugin.config().alertSound(),
                        plugin.config().alertSoundVolume(), plugin.config().alertSoundPitch());
            }
        }

        String mode = plugin.config().checkTestMode() ? "Test check" : "Check";
        plugin.getLogger().warning(mode + " " + check.id() + " failed for "
                + data.name() + ": " + detail);
        plugin.history().note(data.uuid(), data.name(), "check-" + check.id(), detail);
    }

    public void forget(UUID uuid) {
        lastAlert.remove(uuid);
    }
}
