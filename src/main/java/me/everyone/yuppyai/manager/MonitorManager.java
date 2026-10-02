package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A live readout of detection percentages, refreshed once a second.
 *
 * <p>{@code /yai monitor Steve Alex} pins a private feed to the person who ran
 * it. The row data is exactly what the alert path uses, so a name sitting past
 * the alert threshold here is a name that is about to fire.
 *
 * <p>One repeating task drives every watcher rather than a task per watcher,
 * so a console full of operators costs one scheduler slot instead of one each.
 * A watcher that goes offline is dropped on the next tick, which covers both a
 * clean quit and a kick without either needing to register an event handler.
 */
public final class MonitorManager implements Manager {

    /** One second. Slow enough to read, fast enough to feel current. */
    public static final int PERIOD_TICKS = 20;

    /** A header every fifteen lines, so a long feed stays legible in the log. */
    private static final int HEADER_EVERY = 15;

    private static final int MAX_ROWS = 12;
    private static final int BAR_WIDTH = 10;

    private static final String CONSOLE = "console";

    private static final class Session {
        private final String id;
        private CommandSender sender;
        /** Explicitly named players; empty means everyone being tracked. */
        private final List<String> names;
        private final Set<String> absent = new LinkedHashSet<>();
        private int lines;

        private Session(String id, CommandSender sender, List<String> names) {
            this.id = id;
            this.sender = sender;
            this.names = names;
        }
    }

    private final YuppyAI plugin;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private BukkitTask task;

    public MonitorManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        sessions.clear();
    }

    public static String idOf(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId().toString();
        }
        return CONSOLE;
    }

    public boolean watching(CommandSender sender) {
        return sessions.containsKey(idOf(sender));
    }

    public int count() {
        return sessions.size();
    }

    /** Starts a feed, replacing whatever this sender was already watching. */
    public Session start(CommandSender sender, List<String> names) {
        Session session = new Session(idOf(sender), sender, List.copyOf(names));
        sessions.put(session.id, session);
        return session;
    }

    public boolean stop(CommandSender sender) {
        return sessions.remove(idOf(sender)) != null;
    }

    /** Called when a player leaves, so a feed does not outlive them. */
    public void forget(UUID uuid) {
        sessions.remove(uuid.toString());
    }

    private void tick() {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.sender instanceof Player player && !player.isOnline()) {
                sessions.remove(session.id);
                continue;
            }
            String frame = frame(session);
            if (frame != null) {
                session.sender.sendMessage(frame);
                session.lines++;
            }
        }
    }

    /** One chat message: a header every so often, then a row per player. */
    private String frame(Session session) {
        List<PlayerData> rows = new ArrayList<>();
        if (session.names.isEmpty()) {
            for (PlayerData data : plugin.data().all()) {
                if (data.windowsAnalysed() > 0 || data.buffer() > 0.0D) {
                    rows.add(data);
                }
            }
        } else {
            // Re-resolved every tick, so a player who joins later shows up and
            // one who leaves stops costing a line.
            session.absent.clear();
            for (String name : session.names) {
                PlayerData data = plugin.data().byName(name);
                if (data == null) {
                    session.absent.add(name);
                } else {
                    rows.add(data);
                }
            }
        }
        rows.sort(Comparator.comparingDouble(PlayerData::buffer).reversed());

        StringBuilder out = new StringBuilder();
        if (session.lines % HEADER_EVERY == 0) {
            out.append(Msg.parse(plugin.config().prefix() + "<gray><st>        <reset>"
                    + " <white>Monitor <gray><st>        "
                    + "<dark_gray>alert <white>" + Msg.round(plugin.config().alertAt(), 1)
                    + "<dark_gray> punish <white>" + Msg.round(plugin.config().punishAt(), 1)
                    + " <dark_gray>| <gray>" + rows.size() + " shown"));
            if (!session.absent.isEmpty()) {
                out.append(" <dark_gray>| offline <red>")
                        .append(String.join(", ", session.absent));
            }
        }

        if (rows.isEmpty()) {
            out.append(plugin.lang().text("monitor-cmd.empty"));
            return Msg.parse(out.toString());
        }

        int shown = 0;
        for (PlayerData data : rows) {
            if (shown >= MAX_ROWS) {
                break;
            }
            out.append('\n').append(row(data));
            shown++;
        }
        if (shown < rows.size()) {
            out.append("\n").append(Msg.parse(plugin.lang().text("monitor-cmd.more",
                    Map.of("value", Integer.toString(rows.size() - shown)))));
        }
        return Msg.parse(out.toString());
    }

    /** One row: name, probability, and a buffer bar coloured by how close it is to alerting. */
    private String row(PlayerData data) {
        double max = plugin.config().bufferMax();
        double fraction = max > 0.0D ? data.buffer() / max : 0.0D;

        String flag = data.buffer() >= plugin.config().punishAt() ? "<red>!"
                : (data.buffer() >= plugin.config().alertAt() ? "<gold>!" : "<dark_gray>·");
        String state = data.inCombat(plugin.config().combatMs())
                ? "<green>fight"
                : "<dark_gray>idle ";
        if (data.recording()) {
            state = "<aqua>rec  ";
        }

        return " " + flag + " <white>" + data.name()
                + " <dark_gray>| <gray>p <white>" + pad(Msg.percent(data.probability()), 4)
                + " <dark_gray>| <gray>buf <white>" + pad(Msg.round(data.buffer(), 1), 4)
                + " " + Msg.bar(fraction, BAR_WIDTH)
                + " <dark_gray>| " + state
                + " <dark_gray>" + data.windowsAnalysed() + " win";
    }

    private static String pad(String value, int width) {
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /** Names for tab completion, matched loosely the way players type them. */
    public List<String> onlineNames(String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(player.getName());
            }
        }
        return names;
    }
}
