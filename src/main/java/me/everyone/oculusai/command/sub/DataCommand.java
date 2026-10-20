package me.everyone.oculusai.command.sub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.storage.DatasetStore;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;

public final class DataCommand extends SubCommand {

    public DataCommand(OculusAI plugin) {
        super(plugin, "data", List.of("dataset"), "oculusai.data",
                "/oai data add|rem|train|stop|list ...", "Record and label training data.", false);
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
            case "train", "start" -> start(sender, args);
            case "stop" -> stop(sender);
            case "list" -> list(sender);
            case "delete" -> delete(sender, args);
            default -> usage(sender);
        }
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 3 || !DatasetStore.isLabel(args[1])) {
            reply(sender, "<red>Usage: <white>/oai data add cheater|legit <player>");
            return;
        }
        PlayerData data = plugin.data().byName(args[2]);
        if (data == null) {
            reply(sender, "<red>That player is not online.");
            return;
        }

        String label = args[1].toLowerCase(Locale.ROOT);
        plugin.datasets().enrol(data, label);
        reply(sender, "<gray>Signed up <white>" + data.name() + "<gray> as <white>" + label
                + "<gray>." + (plugin.datasets().collecting()
                ? " <green>Recording already running, they joined it."
                : " <gray>Start with <white>/oai data train<gray>."));
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "<red>Usage: <white>/oai data rem <player>");
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

    private void start(CommandSender sender, String[] args) {
        // Default is auto: record into the newest schema the service also
        // knows, so a plugin that updated itself ahead of the service does not
        // fail the commit after the capture was already made.
        int schema = DatasetStore.SCHEMA_AUTO;
        if (args.length > 1) {
            schema = DatasetStore.parseSchema(args[1]);
            if (schema == -1) {
                reply(sender, "<red>Usage: <white>/oai data train [auto|v2|v3|v4|v5|v6|both]");
                return;
            }
        }
        if (plugin.datasets().roster().isEmpty()) {
            reply(sender, "<red>Nobody is signed up. <white>/oai data add cheater|legit <player>");
            return;
        }
        plugin.datasets().setSchema(schema);
        int started = plugin.datasets().start(sender);
        int resolved = plugin.datasets().effectiveSchema();
        String shown = schema == DatasetStore.SCHEMA_AUTO
                ? DatasetStore.schemaName(resolved) + " (auto)"
                : DatasetStore.schemaName(schema);
        reply(sender, "<green>Recording <white>" + started + "<green> player(s) as <white>"
                + shown
                + "<green>. Windows are only taken while they fight - "
                + "<white>/oai data stop<green> saves everything.");
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
        for (DatasetStore.RosterEntry entry : roster) {
            PlayerData data = plugin.data().get(entry.uuid());
            String captured = data == null ? "offline" : data.recordedCount() + " windows";
            sender.sendMessage(Msg.parse(
                    "<dark_gray> - " + (DatasetStore.CHEATER.equals(entry.label())
                            ? "<red>" : "<green>") + entry.name()
                            + " <dark_gray>- <gray>" + entry.label()
                            + " <dark_gray>- <gray>" + captured));
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 3 || !DatasetStore.isLabel(args[1])) {
            reply(sender, "<red>Usage: <white>/oai data delete cheater|legit <player>");
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
        reply(sender, "   <white>/oai data add cheater <player>");
        reply(sender, "   <white>/oai data add legit <player>");
        reply(sender, "<white>2. <gray>Run it: <white>/oai data train [v2|v3|v4|v5|both] <gray>... they fight ... <white>/oai data stop");
        reply(sender, "   <gray>Stopping sends every capture to the service straight away.");
        reply(sender, "   <gray>v5 (default) adds the hit itself - crits, their timing, the rhythm;");
        reply(sender, "   <gray>v4 is aim plus the session average; <white>both<gray> saves v4 and v5 at once.");
        reply(sender, "   <gray>Two or three short sessions per player beats one long one.");
        reply(sender, "<gray>Also: <white>list<gray>, <white>rem <player><gray> (off the roster),");
        reply(sender, "<gray><white>delete cheater|legit <player><gray> (drop stored data).");
        reply(sender, "<gray>Train the model in <white>/oai dashboard<gray>.");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("add", "rem", "train", "stop", "list", "delete"));
            options.removeIf(option -> !option.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return options;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("train") || args[0].equalsIgnoreCase("start"))) {
            List<String> versions = new ArrayList<>(List.of("v2", "v3", "v4", "v5", "both"));
            versions.removeIf(version -> !version.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return versions;
        }

        boolean needsLabel = args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("delete");
        if (args.length == 2) {
            if (!needsLabel) {
                return onlineNames(args[1]);
            }
            List<String> labels = new ArrayList<>(List.of(DatasetStore.CHEATER, DatasetStore.LEGIT));
            labels.removeIf(label -> !label.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return labels;
        }
        if (args.length == 3 && needsLabel) {
            return onlineNames(args[2]);
        }
        return List.of();
    }
}
