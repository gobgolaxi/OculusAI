package me.everyone.oculusai.punishment;


import me.everyone.oculusai.core.Service;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.entity.Player;

/** Executes the configured punishment after a confirmed detection.
 *
 * <p>There is deliberately no recording window here. A separate capture made
 * the plugin keep recording a suspect after the decision had already been
 * made, delayed the punishment, and produced a second data path whose label
 * could disagree with the alert. The dataset tools in {@code /oai data} are
 * the only way to record training windows now. */
public final class Punishments implements Service {

    private final OculusAI plugin;
    private final Map<UUID, Long> lastPunished = new ConcurrentHashMap<>();

    public Punishments(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        lastPunished.clear();
    }

    public void handle(PlayerData data) {
        if (!plugin.config().analysisEnabled()
                || !plugin.config().punishmentEnabled()
                || !data.player().isOnline()
                || plugin.bypass().isBypassed(data.player())) {
            return;
        }
        // A capture that ends in a kick is a capture cut short: the windows
        // after the threshold are the ones worth having, and the recorded
        // player is on the roster on purpose - they are meant to be cheating.
        // Analysis, readings and alerts carry on; only the punishment waits
        // until the recording is committed.
        if (data.recording() && plugin.config().punishmentSkipWhileRecording()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = plugin.config().punishmentCooldownMs();
        Long previous = lastPunished.get(data.uuid());
        if (previous != null && now - previous < cooldown) {
            return;
        }
        lastPunished.put(data.uuid(), now);
        execute(data);
    }

    public void forget(UUID uuid) {
        lastPunished.remove(uuid);
    }

    private void execute(PlayerData data) {
        Player player = data.player();
        if (!player.isOnline()) {
            return;
        }
        Map<String, String> placeholders = Map.of(
                "player", data.name(),
                "probability", Msg.percent(data.probability()),
                "buffer", Msg.round(data.buffer(), 1));

        plugin.history().note(data.uuid(), data.name(), "punish",
                "buffer " + Msg.round(data.buffer(), 1) + ", p " + Msg.percent(data.probability()));

        String command = Msg.fill(plugin.config().punishmentCommand(), placeholders).trim();
        if (command.isBlank()) {
            // On Folia a player may only be kicked from the thread that owns
            // them, and detection runs on the global one.
            plugin.platform().onEntity(player, () -> player.kickPlayer(Msg.parse("Cheat detected")));
            plugin.getLogger().info("Kicked " + data.name());
        } else {
            String commandWithoutSlash = command.startsWith("/")
                    ? command.substring(1) : command;
            if (!commandWithoutSlash.isBlank()) {
                plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(), commandWithoutSlash);
                plugin.getLogger().info("Executed punishment command for " + data.name()
                        + ": " + commandWithoutSlash);
            }
        }

        String broadcast = plugin.config().punishmentBroadcast();
        if (!broadcast.isBlank()) {
            plugin.alerts().announce(Msg.parse(plugin.config().prefix() + broadcast, placeholders));
        }

        plugin.api().reportKick(data.name(), data.uuid().toString(),
                data.probability(), data.buffer(),
                command.isBlank() ? "kick" : "command");
    }
}
