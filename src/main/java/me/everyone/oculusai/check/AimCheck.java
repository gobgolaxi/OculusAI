package me.everyone.oculusai.check;

import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import me.everyone.oculusai.util.Values;

/** The neural aim check.
 *
 * <p>The model and the buffer live in {@code detection}: this class is the
 * check that reads their result. A reading above the alert threshold fails
 * the check, and the rest - confirmations, cooldown, the alert line, history
 * and the service report - is handled by {@link Alerts} like for every other
 * check. */
public final class AimCheck implements PlayerCheck {

    private final OculusAI plugin;

    public AimCheck(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "aim";
    }

    @Override
    public boolean testOnly() {
        return plugin.config().checkAimTestOnly();
    }

    @Override
    public int confirmations() {
        return plugin.config().checkAimConfirmations();
    }

    @Override
    public long cooldownMs() {
        return plugin.config().checkAimCooldownMs();
    }

    @Override
    public boolean reportsToService() {
        return true;
    }

    @Override
    public boolean showsMetrics() {
        return true;
    }

    @Override
    public CheckResult test(PlayerData data) {
        if (!plugin.config().checkAimEnabled() || data.buffer() < plugin.config().alertAt()) {
            return CheckResult.pass();
        }
        return CheckResult.fail(plugin.lang().text("check.reason.aim", Map.of(
                "probability", Values.probability(plugin.config(), data.probability()),
                "buffer", Values.buffer(plugin.config(), data.buffer()),
                "max", Msg.round(plugin.config().bufferMax(), 1))));
    }
}
