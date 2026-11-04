package me.everyone.oculusai.command.sub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.storage.DatasetStore;
import me.everyone.oculusai.util.Msg;
import org.bukkit.OfflinePlayer;
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
            reply(sender, "<white>Usage: <accent>/oai data add cheater|legit &lt;player&gt;");
            return;
        }
        PlayerData data = plugin.data().byName(args[2]);
        if (data == null) {
            reply(sender, "<white>That player is not online.");
            return;
        }

        String label = args[1].toLowerCase(Locale.ROOT);
        plugin.datasets().enrol(data, label);
        reply(sender, "<white>Signed up <accent>" + data.name() + "<white> as <accent>" + label
                + "<white>." + (plugin.datasets().collecting()
                ? " <white>Recording already running, they joined it."
                : " <white>Start with <accent>/oai data train<white>."));
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            reply(sender, "<white>Usage: <accent>/oai data rem &lt;player&gt;");
            return;
        }
        // The roster keeps a player after they log off, so the admin who wants
        // somebody off it is usually looking at somebody who is not online.
        DatasetStore.RosterEntry entry = rosterEntry(args[1]);
        if (entry == null) {
            reply(sender, "<white>Nobody called <accent>" + args[1] + "<white> is on the roster.");
            return;
        }
        reply(sender, plugin.datasets().unenrol(entry.uuid())
                ? "<white>Removed <accent>" + entry.name() + "<white> from the roster."
                : "<white>They were not on it.");
    }

    private void start(CommandSender sender, String[] args) {
        // Умолчание - обе схемы сразу. Схемы различаются не колонками, а тем,
        // что лежит под строкой, и одна запись годится для обеих: выбирать в
        // момент записи нечего, а выбрав одну, оператор узнаёт об этом через
        // полчаса боёв, когда моделям другого вида учиться уже не на чем.
        int schema = DatasetStore.SCHEMA_BOTH;
        if (args.length > 1) {
            schema = DatasetStore.parseSchema(args[1]);
            if (schema == -1) {
                reply(sender, "<white>Usage: <accent>/oai data train [both|old|new|auto]");
                return;
            }
        }
        if (plugin.datasets().roster().isEmpty()) {
            reply(sender, "<white>Nobody is signed up. <accent>/oai data add cheater|legit &lt;player&gt;");
            return;
        }
        plugin.datasets().setSchema(schema);
        int started = plugin.datasets().start(sender);
        int resolved = plugin.datasets().effectiveSchema();
        String shown = schema == DatasetStore.SCHEMA_AUTO
                ? DatasetStore.schemaName(resolved) + " (auto)"
                : DatasetStore.schemaName(schema);
        reply(sender, "<white>Recording <accent>" + started + "<white> player(s) as <accent>"
                + shown
                + "<white>. Windows are only taken while they fight - "
                + "<accent>/oai data stop<white> saves everything.");
        if (schema == DatasetStore.SCHEMA_BOTH) {
            reply(sender, "<white>  One capture, two sessions: <accent>old<white> trains"
                    + " the column models, <accent>new<white> the trajectory one.");
        }
    }

    private void stop(CommandSender sender) {
        Map<String, CompletableFuture<Integer>> results = plugin.datasets().stop();
        if (results.isEmpty()) {
            reply(sender, "<white>Stopped. Nothing captured - windows are only taken"
                    + " while the players are actually fighting.");
            return;
        }

        reply(sender, "<white>Stopped, sending <accent>" + results.size() + "<white> session(s)...");
        results.forEach((name, future) -> future.thenAccept(stored -> reply(sender, stored == null
                ? "<accent>" + name + "<white>: the service refused the session."
                : "<accent>" + name + "<white>: <accent>" + stored + "<white> windows saved.")));
    }

    private void list(CommandSender sender) {
        var roster = plugin.datasets().roster();
        if (roster.isEmpty()) {
            reply(sender, "<white>Roster is empty.");
            return;
        }
        reply(sender, "<white>Roster " + (plugin.datasets().collecting()
                ? "(recording)" : "(idle)") + ":");
        for (DatasetStore.RosterEntry entry : roster) {
            PlayerData data = plugin.data().get(entry.uuid());
            String captured = data == null ? "offline" : data.recordedCount() + " windows";
            sender.sendMessage(Msg.parse(accented(
                    "<white> - <accent>" + entry.name()
                            + "<white> - " + entry.label()
                            + " - " + captured)));
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (args.length < 3 || !DatasetStore.isLabel(args[1])) {
            reply(sender, "<white>Usage: <accent>/oai data delete cheater|legit &lt;player&gt;");
            return;
        }
        String label = args[1].toLowerCase(Locale.ROOT);
        // Stored data outlives the session, so the player whose data is being
        // dropped is often not online any more.
        DatasetStore.RosterEntry entry = rosterEntry(args[2]);
        if (entry != null) {
            deleteStored(sender, entry.uuid(), entry.name(), label);
            return;
        }
        UUID uuid = uuidOf(args[2]);
        if (uuid == null) {
            reply(sender, "<white>Nobody called <accent>" + args[2] + "<white> is known to this server.");
            return;
        }
        deleteStored(sender, uuid, args[2], label);
    }

    private void deleteStored(CommandSender sender, UUID uuid, String name, String label) {
        plugin.datasets().remove(uuid.toString(), label).thenAccept(removed ->
                reply(sender, removed == null
                        ? "<white>The service did not answer or refused the change."
                        : "<white>Deleted <accent>" + removed + "<white> stored <accent>" + label
                        + "<white> windows for <accent>" + name + "<white>."));
    }

    /** The roster entry a name belongs to, online or not.
     *
     * <p>The name is looked for on the roster first, then among the players
     * the server still knows about: that way a rename does not strand the
     * entry, and a player who already left can still be taken off the
     * roster. */
    private DatasetStore.RosterEntry rosterEntry(String name) {
        DatasetStore.RosterEntry entry = plugin.datasets().entryByName(name);
        if (entry != null) {
            return entry;
        }
        UUID uuid = uuidOf(name);
        return uuid == null ? null : plugin.datasets().entry(uuid);
    }

    /** The UUID of a player, online or not.
     *
     * <p>Only the cached name is asked for: a fresh lookup would go out to the
     * Mojang API and hold the server thread while it waits. */
    private UUID uuidOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        PlayerData data = plugin.data().byName(name);
        if (data != null) {
            return data.uuid();
        }
        try {
            OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(name);
            return cached == null ? null : cached.getUniqueId();
        } catch (IllegalArgumentException refused) {
            // The server turns down names it cannot look up, a name that is
            // empty or too long for a profile for instance.
            return null;
        }
    }

    /** The names on the roster that start with what has been typed. */
    private List<String> rosterNames(String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (DatasetStore.RosterEntry entry : plugin.datasets().roster()) {
            if (entry.name() != null && entry.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(entry.name());
            }
        }
        return names;
    }

    private void usage(CommandSender sender) {
        reply(sender, "<accent>1. <white>Sign the cast up:");
        reply(sender, "   <accent>/oai data add cheater &lt;player&gt;");
        reply(sender, "   <accent>/oai data add legit &lt;player&gt;");
        reply(sender, "<accent>2. <white>Run it: <accent>/oai data train <white>... they fight ... <accent>/oai data stop");
        reply(sender, "   <white>Stopping sends every capture to the service straight away.");
        reply(sender, "   <white>By default one capture becomes two sessions, one per schema:");
        reply(sender, "   <accent>old<white> is the window columns, which the column models read;");
        reply(sender, "   <accent>new<white> is those plus the raw ticks, which the trajectory model reads.");
        reply(sender, "   <white>Name one - <accent>/oai data train old<white> - only to record just it.");
        reply(sender, "   <white>Two or three short sessions per player beats one long one.");
        reply(sender, "<white>Also: <accent>list<white>, <accent>rem &lt;player&gt;<white> (off the roster),");
        reply(sender, "<white><accent>delete cheater|legit &lt;player&gt;<white> (drop stored data).");
        reply(sender, "<white>Train the model in <accent>/oai dashboard<white>.");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("add", "rem", "train", "stop", "list", "delete"));
            options.removeIf(option -> !option.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return options;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("train") || args[0].equalsIgnoreCase("start"))) {
            List<String> versions = new ArrayList<>(List.of("both", "old", "new", "auto"));
            versions.removeIf(version -> !version.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return versions;
        }

        boolean needsLabel = args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("delete");
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("rem") || args[0].equalsIgnoreCase("remove")) {
                // Only the roster, and not the players who happen to be
                // online: rem answers "nobody called that is on the roster"
                // for anybody else, so offering those names was offering
                // rejections. The roster keeps a player after they log off,
                // which is the usual case for taking somebody off it.
                return rosterNames(args[1]);
            }
            if (!needsLabel) {
                return knownNames(args[1]);
            }
            List<String> labels = new ArrayList<>(List.of(DatasetStore.CHEATER, DatasetStore.LEGIT));
            labels.removeIf(label -> !label.startsWith(args[1].toLowerCase(Locale.ROOT)));
            return labels;
        }
        if (args.length == 3 && needsLabel) {
            // Signing up needs a player who is here; dropping stored data
            // does not.
            return args[0].equalsIgnoreCase("delete") ? knownNames(args[2]) : onlineNames(args[2]);
        }
        return List.of();
    }

    /** The names that can be picked: the players online and the ones the
     *  server still knows about, the roster included. */
    private List<String> knownNames(String partial) {
        List<String> names = new ArrayList<>(rosterNames(partial));
        for (String name : onlineNames(partial)) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }
}
