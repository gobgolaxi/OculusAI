package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.analysis.FeatureExtractor;
import me.everyone.yuppyai.analysis.FeatureVector;
import me.everyone.yuppyai.analysis.RotationSample;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AnalysisManager implements Manager {

    private final YuppyAI plugin;
    private final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> inFlight = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AnalysisManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        long period = Math.max(2, plugin.config().windowTicks() / 2);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastAlert.clear();
        inFlight.clear();
    }

    public void forget(UUID uuid) {
        lastAlert.remove(uuid);
        inFlight.remove(uuid);
    }

    private void tick() {
        for (PlayerData data : plugin.data().all()) {
            Player player = data.player();
            if (!player.isOnline() || player.hasPermission("yuppyai.bypass")) {
                continue;
            }
            analyse(data);
        }
    }

    private void analyse(PlayerData data) {
        List<RotationSample> window = data.snapshotWindow(plugin.config().windowTicks());
        if (window == null || !data.inCombat(plugin.config().combatMs())) {
            return;
        }

        int attacks = 0;
        double rotation = 0.0D;
        for (int i = 0; i < window.size(); i++) {
            if (window.get(i).attacked()) {
                attacks++;
            }
            if (i > 0) {
                rotation += Math.abs(FeatureExtractor.wrapDegrees(
                        window.get(i).yaw() - window.get(i - 1).yaw()));
            }
        }
        if (attacks < plugin.config().minAttacks() || rotation < plugin.config().minRotation()) {
            return;
        }
        if (attackLinkedRotation(window) < plugin.config().minAttackRotation()) {
            return;
        }

        double scale = FeatureExtractor.scaleOf(window);
        FeatureVector vector = FeatureExtractor.extract(window, data.baselineScale());
        Map<String, Double> features = vector.toMap();

        if (data.recording() && !data.filtering()) {
            data.record(new ArrayList<>(features.keySet()), toArray(features));
            return;
        }
        if (!plugin.api().reachable()) {
            return;
        }
        if (inFlight.putIfAbsent(data.uuid(), Boolean.TRUE) != null) {
            return;
        }

        plugin.api().predict(features).thenAccept(probability -> {
            if (probability == null || Double.isNaN(probability)) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!data.recording() && probability < plugin.config().suspicion()) {
                    data.recordScale(scale);
                }
                accept(data, probability);
                sift(data, features, probability);
            });
        }).whenComplete((ignored, throwable) -> inFlight.remove(data.uuid()));
    }

    private double attackLinkedRotation(List<RotationSample> window) {
        double total = 0.0D;
        for (int i = 1; i < window.size(); i++) {
            RotationSample previous = window.get(i - 1);
            RotationSample current = window.get(i);
            if (!previous.attacked() && !current.attacked()) {
                continue;
            }
            double yaw = Math.abs(FeatureExtractor.wrapDegrees(current.yaw() - previous.yaw()));
            double pitch = Math.abs(current.pitch() - previous.pitch());
            total += Math.hypot(yaw, pitch);
        }
        return total;
    }

    private void sift(PlayerData data, Map<String, Double> features, double probability) {
        if (!data.filtering()) {
            return;
        }
        if (probability < data.recordingFloor()
                || data.buffer() < plugin.config().evidenceMinBuffer()) {
            data.discard();
            return;
        }
        data.record(new ArrayList<>(features.keySet()), toArray(features), probability, data.buffer());
    }

    private void accept(PlayerData data, double probability) {
        data.applyProbability(probability,
                plugin.config().suspicion(),
                plugin.config().gain(),
                plugin.config().decay(),
                plugin.config().bufferMax());
        plugin.journal().record(data);

        if (data.buffer() < plugin.config().alertAt()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastAlert.get(data.uuid());
        if (previous != null && now - previous < plugin.config().alertCooldownMs()) {
            return;
        }
        lastAlert.put(data.uuid(), now);
        alert(data);
        if (data.buffer() >= plugin.config().punishAt()) {
            plugin.punishments().handle(data);
        }
    }

    private void alert(PlayerData data) {
        var theme = plugin.theme().current();
        String prefix = Msg.parse(plugin.config().prefix());

        String line = Msg.parse(plugin.config().alertFormat(), Map.of(
                "player", data.name(),
                "probability", Msg.percent(data.probability()),
                "buffer", Msg.round(data.buffer(), 1),
                "max", Msg.round(plugin.config().bufferMax(), 0),
                "primary", theme.primary(),
                "secondary", theme.secondary()));

        String symbol = plugin.config().alertSymbol();
        String clickLabel = plugin.config().alertClick();
        String clickLine = clickLabel == null || clickLabel.isBlank()
                ? ""
                : Msg.parse(" " + clickLabel);
        String hoverText = Msg.parse(plugin.lang().text("alert.hover",
                Map.of("player", data.name())));

        String symbolText = symbol.isBlank()
                ? ""
                : Msg.parse("<" + theme.accent() + ">" + symbol + " <reset>");

        TextComponent main = new TextComponent(prefix + symbolText + line);
        if (!clickLine.isEmpty()) {
            TextComponent click = new TextComponent(clickLine);
            click.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/yai " + plugin.config().alertClickCommand() + " " + data.name()));
            click.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new Text(hoverText)));
            main.addExtra(click);
        }

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("yuppyai.alerts")) {
                online.spigot().sendMessage(main);
                online.playSound(online.getLocation(), plugin.config().alertSound(),
                        plugin.config().alertSoundVolume(), plugin.config().alertSoundPitch());
            }
        }
        plugin.getLogger().info(data.name() + " aim buffer "
                + Msg.round(data.buffer(), 2) + " (p=" + Msg.round(data.probability(), 3) + ")");
    }

    private double[] toArray(Map<String, Double> features) {
        double[] values = new double[features.size()];
        int index = 0;
        for (double value : features.values()) {
            values[index++] = value;
        }
        return values;
    }
}
