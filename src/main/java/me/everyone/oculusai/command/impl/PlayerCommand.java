package me.everyone.oculusai.command.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.data.PlayerData;
import me.everyone.oculusai.manager.JournalManager;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;

public final class PlayerCommand extends SubCommand {

    public PlayerCommand(OculusAI plugin) {
        super(plugin, "player", List.of("pl", "info"), "oculusai.alerts",
                "/oai player <nick>", "View a player's suspicion data.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            replyKey(sender, "player-cmd.usage", Map.of("value", "/oai player <nick>"));
            return;
        }

        PlayerData data = plugin.data().byName(args[0]);
        if (data == null) {
            replyKey(sender, "shared.player-offline");
            return;
        }

        double max = plugin.config().bufferMax();
        double fraction = max > 0.0D ? data.buffer() / max : 0.0D;

        // Three lines instead of a labelled block. Every label that was here -
        // "Buffer:", "Last probability:", "Windows analysed:" - repeated what
        // the number next to it already says to anyone who runs this command,
        // and pushed the numbers apart so none of them could be compared.
        replyKey(sender, "player-cmd.header", Map.of("player", data.name()));
        replyKey(sender, "player-cmd.reading", Map.of(
                "heat", Msg.heatColour(data.probability()),
                "probability", Msg.percent(data.probability()),
                "bar", Msg.bar(fraction, 10),
                "buffer", Msg.round(data.buffer(), 1),
                "max", Msg.round(max, 0)));

        String trend = Msg.spark(data.bufferHistory(), max, 12);
        String state = plugin.lang().text(data.inCombat(plugin.config().combatMs())
                ? "player-cmd.state-combat" : "player-cmd.state-idle");
        if (data.recording()) {
            state = state + plugin.lang().text("player-cmd.state-recording",
                    Map.of("value", Integer.toString(data.recordedCount())));
        }
        replyKey(sender, "player-cmd.state", Map.of(
                "trend", trend.isEmpty() ? "\u2014" : trend,
                "windows", Integer.toString(data.windowsAnalysed()),
                "state", state));

        JournalManager.Entry entry = findJournalEntry(data.uuid());
        if (entry != null) {
            StringBuilder history = new StringBuilder();
            long now = System.currentTimeMillis();
            for (JournalManager.Snapshot snap : entry.history()) {
                long age = (now - snap.capturedAt()) / 1000;
                String ageStr = age < 60
                        ? age + "s"
                        : (age < 3600 ? (age / 60) + "m" : (age / 3600) + "h");
                history.append("  <dark_gray>").append(ageStr)
                       .append(" <gray>").append(Msg.percent(snap.probability()));
            }
            replyKey(sender, "player-cmd.journal", Map.of(
                    "peak", Msg.round(entry.peakBuffer(), 1),
                    "history", history.toString()));
        }

        if (data.windowsAnalysed() == 0) {
            replyKey(sender, "player-cmd.no-data");
        }
    }

    private JournalManager.Entry findJournalEntry(UUID uuid) {
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
