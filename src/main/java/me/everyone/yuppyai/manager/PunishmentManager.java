package me.everyone.yuppyai.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class PunishmentManager implements Manager {

    private record Case(long openedAt, double buffer, BukkitTask task) {
    }

    private final YuppyAI plugin;
    private final Map<UUID, Case> open = new ConcurrentHashMap<>();

    public PunishmentManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        for (Case pending : open.values()) {
            pending.task().cancel();
        }
        open.clear();
    }

    public void handle(PlayerData data) {
        if (!plugin.config().punishmentEnabled() || open.containsKey(data.uuid())) {
            return;
        }
        if (!plugin.config().evidenceEnabled()) {
            punish(data);
            return;
        }
        if (data.recording()) {
            punish(data);
            return;
        }

        data.recording(true, plugin.config().evidenceLabel(), plugin.config().evidenceFloor());
        int seconds = plugin.config().evidenceSeconds();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> close(data), seconds * 20L);
        open.put(data.uuid(), new Case(System.currentTimeMillis(), data.buffer(), task));

        announce(Msg.parse(plugin.config().prefix() + "<gold>Collecting evidence on <white>"
                + data.name() + "<gold> for " + seconds + "s <dark_gray>(buffer "
                + Msg.round(data.buffer(), 1) + ")"));
    }

    private void close(PlayerData data) {
        open.remove(data.uuid());
        String label = plugin.config().evidenceLabel();
        data.recording(false, null);

        int captured = data.recordedCount();
        int dropped = data.discardedCount();
        boolean held = data.buffer() >= plugin.config().cancelBelow();

        if (captured > 0) {
            plugin.datasets().commit(data, label, false, held ? "evidence" : "cancelled");
        }

        if (!data.player().isOnline()) {
            return;
        }
        String kept = "<gray>kept <white>" + captured + "<gray> windows, dropped <white>"
                + dropped + "<gray> that were not convincing enough";

        if (!held) {
            announce(Msg.parse(plugin.config().prefix() + "<gray>No action against <white>"
                    + data.name() + "<gray>: buffer fell to <white>" + Msg.round(data.buffer(), 1)
                    + "<gray>, " + kept + " <dark_gray>(filed as cancelled, not as cheating)"));
            return;
        }
        announce(Msg.parse(plugin.config().prefix() + "<gray>Evidence on <white>" + data.name()
                + "<gray>: " + kept));
        punish(data);
    }

    private void punish(PlayerData data) {
        Player player = data.player();
        if (!player.isOnline()) {
            return;
        }

        executeKick(data);
    }

    private void executeKick(PlayerData data) {
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
            player.kickPlayer(Msg.parse("Cheat detected"));
            plugin.getLogger().info("Kicked " + data.name());
        } else {
            String commandWithoutSlash = command.startsWith("/")
                    ? command.substring(1) : command;
            plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(), commandWithoutSlash);
            plugin.getLogger().info("Executed punishment command for " + data.name()
                    + ": " + commandWithoutSlash);
        }

        String broadcast = plugin.config().punishmentBroadcast();
        if (!broadcast.isBlank()) {
            announce(Msg.parse(plugin.config().prefix() + broadcast, placeholders));
        }
    }

    private void announce(String message) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("yuppyai.alerts")) {
                online.sendMessage(message);
            }
        }
    }

    public boolean pending(UUID uuid) {
        return open.containsKey(uuid);
    }

    public long remainingSeconds(UUID uuid) {
        Case pending = open.get(uuid);
        if (pending == null) {
            return 0L;
        }
        long elapsed = (System.currentTimeMillis() - pending.openedAt()) / 1000L;
        return Math.max(0L, plugin.config().evidenceSeconds() - elapsed);
    }

    public void forget(UUID uuid) {
        Case pending = open.remove(uuid);
        if (pending != null) {
            pending.task().cancel();
        }
    }
}
