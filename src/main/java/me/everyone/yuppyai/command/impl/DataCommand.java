package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.manager.DatasetManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class DataCommand extends SubCommand {

    public DataCommand(YuppyAI plugin) {
        super(plugin, "data", List.of("dataset"), "yuppyai.data",
                "/yai data add|rem|train|stop|list ...", "Record and label training data.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, args);
            case "rem", "remove" -> remove(sender, args);
            case "train", "start" -> start(sender);
            case "stop" -> stop(sender);
            case "list" -> list(sender);
            case "delete" -> delete(sender, args);
            default -> usage(sender);
        }
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 3 || !DatasetManager.isLabel(args[1])) {
            reply(sender, "<red>Usage: <white>/yai data add cheater|legit <player> [person]");
            return;
        }
        PlayerData data = plugin.data().byName(args[2]);
        if (data == null) {
            reply(sender, "<red>That player is not online.");
            return;
        }

        String label = args[1].toLowerCase(Locale.ROOT);
        String person = args.length > 3 ? args[3] : null;
        plugin.datasets().enrol(data, label, person);
        reply(sender, "<gray>Signed up <white>" + data.name() + "<gray> as <white>" + label
                + (person == null ? "" : "<gray>, played by <white>" + person)
                + "<gray>." + (plugin.datasets().collecting()
                ? " <green>Recording already running, they joined it."
                : " <gray>Start with <white>/yai data train<gray>."));
        if (person == null) {
            reply(sender, "<dark_gray>Using several accounts yourself? Add a person tag so they"
                    + " count as one: <white>/yai data add " + label + " " + data.name() + " me");
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "<red>Usage: <white>/yai data rem <player>");
            return;
        }
        PlayerData data = plugin.data().byName(args[1]);
        if (data == null) {
            reply(sender, "<red>That player is not online.");
            return;
        }
        reply(sender, plugin.datasets().unenrol(data.uuid())
                ? "<gray>Removed <white>" + data.name() + "<gray> from the roster."
                : "<red>They were not on it.");
    }

    private void start(CommandSender sender) {
        if (plugin.datasets().roster().isEmpty()) {
            reply(sender, "<red>Nobody is signed up. <white>/yai data add cheater|legit <player>");
            return;
        }
        int started = plugin.datasets().start(sender);
        reply(sender, "<green>Recording <white>" + started + "<green> player(s)<gray>. "
                + "Windows are only taken while they fight - "
                + "<white>/yai data stop<gray> saves everything.");
    }

    private void stop(CommandSender sender) {
        Map<String, CompletableFuture<Integer>> results = plugin.datasets().stop();
        if (results.isEmpty()) {
            reply(sender, "<gray>Stopped. <red>Nothing captured<gray> - windows are only taken"
                    + " while the players are actually fighting.");
            return;
        }

        reply(sender, "<gray>Stopped, sending <white>" + results.size() + "<gray> session(s)...");
        results.forEach((name, future) -> future.thenAccept(stored -> reply(sender, stored == null
                ? "<red>" + name + "<red>: the service refused the session."
                : "<gray>" + name + ": <white>" + stored + "<gray> windows saved.")));
    }

    private void list(CommandSender sender) {
        var roster = plugin.datasets().roster();
        if (roster.isEmpty()) {
            reply(sender, "<gray>Roster is empty.");
            return;
        }
        reply(sender, "<gray>Roster " + (plugin.datasets().collecting()
                ? "<green>(recording)" : "<dark_gray>(idle)") + "<gray>:");
        for (DatasetManager.RosterEntry entry : roster) {
            PlayerData data = plugin.data().get(entry.uuid());
            String captured = data == null ? "offline" : data.recordedCount() + " windows";
            boolean tagged = !entry.person().equals(entry.uuid().toString());
            sender.sendMessage(me.everyone.yuppyai.util.Msg.parse(
                    "<dark_gray> - " + (DatasetManager.CHEATER.equals(entry.label())
                            ? "<red>" : "<green>") + entry.name()
                            + " <dark_gray>- <gray>" + entry.label()
                            + (tagged ? " <dark_gray>- <aqua>" + entry.person() : "")
                            + " <dark_gray>- <gray>" + captured));
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 3 || !DatasetManager.isLabel(args[1])) {
            reply(sender, "<red>Usage: <white>/yai data delete cheater|legit <player>");
            return;
        }
        PlayerData data = plugin.data().byName(args[2]);
        if (data == null) {
            reply(sender, "<red>That player is not online.");
            return;
        }
        String label = args[1].toLowerCase(Locale.ROOT);
        deleteStored(sender, data, label);
    }

    private void deleteStored(CommandSender sender, PlayerData data, String label) {
        plugin.datasets().remove(data.uuid().toString(), label).thenAccept(removed ->
                reply(sender, removed == null
                        ? "<red>The service did not answer or refused the change."
                        : "<gray>Deleted <white>" + removed + "<gray> stored <white>" + label
                        + "<gray> windows for <white>" + data.name() + "<gray>."));
    }

    private void usage(CommandSender sender) {
        reply(sender, "<white>1. <gray>Sign the cast up:");
        reply(sender, "   <white>/yai data add cheater <player>");
        reply(sender, "   <white>/yai data add legit <player>");
        reply(sender, "<white>2. <gray>Run it: <white>/yai data train <gray>... they fight ... <white>/yai data stop");
        reply(sender, "   <gray>Stopping sends every capture to the service straight away.");
        reply(sender, "<gray>Also: <white>list<gray>, <white>rem <player><gray> (off the roster),");
        reply(sender, "<gray><white>delete cheater|legit <player><gray> (drop stored data).");
        reply(sender, "<gray>Train the model in <white>/yai dashboard<gray>.");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("add", "rem", "train", "stop", "list", "delete"));
            options.removeIf(option -> !option.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return options;
        }

        boolean needsLabel = args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("delete");
        if (args.length == 2) {
            if (!needsLabel) {
                return onlineNames(args[1]);
            }
            List<String> labels = new ArrayList<>(List.of(DatasetManager.CHEATER, DatasetManager.LEGIT));
            labels.removeIf(label -> !label.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return labels;
        }
        if (args.length == 3 && needsLabel) {
            return onlineNames(args[2]);
        }
        return List.of();
    }
}
