package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.manager.JournalManager;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public final class PlayerCommand extends SubCommand {

    public PlayerCommand(YuppyAI plugin) {
        super(plugin, "player", List.of("pl", "info"), "yuppyai.alerts",
                "/yai player <nick>", "View a player's suspicion data.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            replyKey(sender, "player-cmd.usage", Map.of("value", "/yai player <nick>"));
            return;
        }

        PlayerData data = plugin.data().byName(args[0]);
        if (data == null) {
            replyKey(sender, "shared.player-offline");
            return;
        }

        double max = plugin.config().bufferMax();

        replyKey(sender, "player-cmd.header", Map.of("player", data.name()));
        replyKey(sender, "player-cmd.buffer", Map.of(
                "value", Msg.round(data.buffer(), 1),
                "max", Msg.round(max, 0)));
        replyKey(sender, "player-cmd.probability", Map.of(
                "value", Msg.percent(data.probability())));
        replyKey(sender, "player-cmd.windows", Map.of(
                "value", Integer.toString(data.windowsAnalysed())));

        if (data.inCombat(plugin.config().combatMs())) {
            replyKey(sender, "player-cmd.in-combat");
        } else {
            replyKey(sender, "player-cmd.not-in-combat");
        }

        if (data.recording()) {
            replyKey(sender, "player-cmd.recording", Map.of(
                    "value", Integer.toString(data.recordedCount())));
        }

        List<Double> history = data.bufferHistory();
        if (!history.isEmpty()) {
            replyKey(sender, "player-cmd.buffer-history");
            StringBuilder sb = new StringBuilder("<dark_gray>");
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) sb.append("<dark_gray>, ");
                sb.append("<white>").append(Msg.round(history.get(i), 1));
            }
            reply(sender, sb.toString());
        }

        JournalManager.Entry entry = findJournalEntry(data.uuid());
        if (entry != null) {
            replyKey(sender, "player-cmd.journal-header");
            replyKey(sender, "player-cmd.peak-buffer", Map.of(
                    "value", Msg.round(entry.peakBuffer(), 1)));

            List<JournalManager.Snapshot> snapshots = entry.history();
            if (!snapshots.isEmpty()) {
                replyKey(sender, "player-cmd.prob-history");
                long now = System.currentTimeMillis();
                for (JournalManager.Snapshot snap : snapshots) {
                    long age = (now - snap.capturedAt()) / 1000;
                    String ageStr = age < 60
                            ? age + "s"
                            : (age < 3600 ? (age / 60) + "m" : (age / 3600) + "h");
                    reply(sender, "<dark_gray>- <gray>" + ageStr
                            + " <dark_gray>| <white>" + Msg.percent(snap.probability())
                            + " <dark_gray>| <gray>buf <white>" + Msg.round(snap.buffer(), 1));
                }
            }
        }

        if (data.windowsAnalysed() == 0) {
            replyKey(sender, "player-cmd.no-data");
        }
    }

    private JournalManager.Entry findJournalEntry(java.util.UUID uuid) {
        for (JournalManager.Entry e : plugin.journal().entries()) {
            if (e.uuid().equals(uuid)) return e;
        }
        return null;
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        return onlineNames(args[0]);
    }
}
