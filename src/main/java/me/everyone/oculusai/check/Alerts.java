package me.everyone.oculusai.check;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import me.everyone.oculusai.util.Values;
import org.bukkit.entity.Player;

/** One place for everything that happens after a check fails.
 *
 * <p>Confirmations, the per-check cooldown, the chat line, the sound, the
 * history entry and the call to the service all live here. {@link Checks}
 * only decides which check is tested; a new check gets the same behaviour as
 * {@code wall} and {@code aim} without writing any of this again. */
public final class Alerts implements Service {

    public enum Outcome {
        /** Not confirmed yet: nothing is shown and the check does not act. */
        NONE,
        /** Confirmed, but this player already had an alert recently. */
        COOLDOWN,
        /** Confirmed and delivered. */
        SENT
    }

    private static final String PERMISSION = "oculusai.alerts";
    private static final long CONFIRM_WINDOW_MS = 5000L;

    private final OculusAI plugin;
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public Alerts(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        clear();
    }

    @Override
    public void reload() {
        clear();
    }

    private void clear() {
        lastAlert.clear();
        failures.clear();
    }

    public void forget(UUID uuid) {
        String prefix = uuid + "|";
        lastAlert.keySet().removeIf(key -> key.startsWith(prefix));
        failures.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Clears the confirmation chain of one check for one player. Called when
     *  a check passes, so separate failures are not counted as a streak. */
    public void reset(UUID uuid, String checkId) {
        failures.remove(key(uuid, checkId));
    }

    public Outcome raise(PlayerData data, Check check, CheckResult result, boolean test) {
        long now = System.currentTimeMillis();
        String key = key(data.uuid(), check.id());

        int required = test || check.immediate() ? 1 : confirmations(check);
        if (required > 1 && !confirmed(key, required, now)) {
            return Outcome.NONE;
        }

        long cooldown = cooldownMs(check);
        if (cooldown > 0L) {
            Long previous = lastAlert.get(key);
            if (previous != null && now - previous < cooldown) {
                failures.remove(key);
                return Outcome.COOLDOWN;
            }
        }

        lastAlert.put(key, now);
        deliver(data, check, result, test);
        return Outcome.SENT;
    }

    /** A message that belongs to the alert channel but not to a check - the
     *  punishment lines use the same permission and the same audience as
     *  check alerts. */
    public void announce(String message) {
        send(message, false);
    }

    private void deliver(PlayerData data, Check check, CheckResult result, boolean test) {
        String detail = Msg.strip(result.detail());
        String checkName = plugin.lang().textOr("check.name." + check.id(), check.id());
        String symbol = plugin.config().alertSymbol();
        boolean metrics = check.showsMetrics();
        String message = plugin.config().prefix() + plugin.lang().text(
                test ? "check.test-line" : "check.alert-line",
                Map.of(
                        "player", data.name(),
                        "check", checkName,
                        "detail", detail,
                        "accent", plugin.theme().current().accent(),
                        "symbol", symbol == null ? "" : symbol,
                        // Old language files used these placeholders directly
                        // in the alert line. Only the neural check fills them;
                        // Wall and HitBox must not print values they never
                        // produced.
                        "probability", metrics
                                ? Values.probability(plugin.config(), data.probability()) : "",
                        "buffer", metrics
                                ? Values.buffer(plugin.config(), data.buffer()) : "",
                        "max", metrics ? Msg.round(plugin.config().bufferMax(), 1) : "",
                        "metrics", metrics
                                ? Values.inline(plugin.config(), data.probability(), data.buffer())
                                : ""));
        if (!metrics) {
            message = withoutTrailingSeparator(message);
        }

        send(message, true);
        plugin.history().note(data.uuid(), data.name(), "check-" + check.id(), detail);
        plugin.getLogger().warning((test ? "Test check " : "Check ") + check.id()
                + " failed for " + data.name() + ": " + detail);
        if (check.reportsToService()) {
            plugin.api().reportAlert(data.name(), data.uuid().toString(),
                    data.probability(), data.buffer());
        }
    }

    /** An empty %metrics% leaves the separator before it behind in the old
     *  alert layout. Remove it so Wall and HitBox end cleanly after the
     *  detail instead of showing a dangling middle dot. */
    private String withoutTrailingSeparator(String message) {
        String trimmed = message.replaceAll("\\s+$", "");
        trimmed = trimmed.replaceAll("(?s)§[0-9a-fk-or]\\s*[·|]\\s*$", "");
        trimmed = trimmed.replaceAll("(?s)\\s*[·|]\\s*$", "");
        return trimmed.replaceAll("\\s+$", "");
    }

    private void send(String message, boolean sound) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.hasPermission(PERMISSION)) {
                continue;
            }
            online.sendMessage(message);
            if (sound) {
                online.playSound(online.getLocation(), plugin.config().alertSound(),
                        plugin.config().alertSoundVolume(), plugin.config().alertSoundPitch());
            }
        }
    }

    private int confirmations(Check check) {
        int own = check.confirmations();
        return own > 0 ? own : plugin.config().checkConfirmations();
    }

    private long cooldownMs(Check check) {
        long own = check.cooldownMs();
        return own >= 0L ? own : plugin.config().checkAlertCooldownMs();
    }

    private boolean confirmed(String key, int required, long now) {
        Deque<Long> times = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            long cutoff = now - CONFIRM_WINDOW_MS;
            while (!times.isEmpty() && times.peekFirst() < cutoff) {
                times.removeFirst();
            }
            times.addLast(now);
            if (times.size() < required) {
                return false;
            }
            failures.remove(key);
            return true;
        }
    }

    private String key(UUID uuid, String checkId) {
        return uuid + "|" + checkId;
    }
}
