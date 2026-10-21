package me.everyone.oculusai.ui;


import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import me.everyone.oculusai.util.Values;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class Monitor implements Service {

    public static final int PERIOD_TICKS = 20;

    private static final int HEADER_EVERY = 15;

    private static final int MAX_ROWS = 12;
    private static final int BAR_WIDTH = 10;

    private static final String CONSOLE = "console";
    /** A tiny state file rather than config.yml: saving Bukkit's config on
     *  every toggle rewrites the file and can drop its comments. */
    private static final String STATE_FILE = "monitor.state";

    private static final class Session {
        private final String id;
        private CommandSender sender;
        private final List<String> names;
        private int lines;

        private Session(String id, CommandSender sender, List<String> names) {
            this.id = id;
            this.sender = sender;
            this.names = names;
        }
    }

    private final OculusAI plugin;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private TickLoop.Handle task;
    /** The watch over everybody, switched on by /oai monitor. */
    private volatile boolean global;
    /** Ticks since the watch was switched on - used to say "nobody" now and then. */
    private int globalTicks;

    public Monitor(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        global = loadGlobal();
        globalTicks = 0;
        task = plugin.ticks().every(PERIOD_TICKS, this::tick);
    }

    @Override
    public void reload() {
        // /oai reload keeps the watch the operator turned on; the state file
        // is the source of truth, config.yml is the first-run default.
        global = loadGlobal();
        globalTicks = 0;
    }

    private boolean loadGlobal() {
        Path state = plugin.getDataFolder().toPath().resolve(STATE_FILE);
        try {
            if (Files.isRegularFile(state)) {
                String value = Files.readString(state, StandardCharsets.UTF_8).trim();
                return value.equalsIgnoreCase("on") || value.equals("1")
                        || value.equalsIgnoreCase("true");
            }
        } catch (IOException failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not read " + state + "; using monitor.enabled", failure);
        }
        return plugin.config().monitorEnabled();
    }

    private void saveGlobal() {
        Path state = plugin.getDataFolder().toPath().resolve(STATE_FILE);
        try {
            Files.createDirectories(state.getParent());
            Files.writeString(state, global ? "on\n" : "off\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not save the monitor state to " + state, failure);
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        sessions.clear();
        global = false;
    }

    /** Whether the watch over everybody is on. */
    public boolean globalWatching() {
        return global;
    }

    /** Switches the watch over everybody on or off. Returns the new state.
     *  The choice is saved so a restart does not quietly turn the watch off. */
    public boolean toggleGlobal() {
        global = !global;
        globalTicks = 0;
        saveGlobal();
        return global;
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

    public Session start(CommandSender sender, List<String> names) {
        Session session = new Session(idOf(sender), sender, List.copyOf(names));
        sessions.put(session.id, session);
        return session;
    }

    public boolean stop(CommandSender sender) {
        return sessions.remove(idOf(sender)) != null;
    }

    public void forget(UUID uuid) {
        sessions.remove(uuid.toString());
    }

    private void tick() {
        if (global) {
            broadcastPvp();
        }
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

    /** The watch over everybody: who is fighting right now, and how they read.
     *
     * <p>Only hits on other players count - the plugin does not record a swing
     * at a mob as combat at all - so this is the list of people actually in a
     * fight, not the list of people swinging at things. */
    private void broadcastPvp() {
        List<PlayerData> rows = new ArrayList<>();
        long window = plugin.config().monitorPvpMs();
        for (PlayerData data : plugin.data().all()) {
            if (data.inCombat(window)) {
                rows.add(data);
            }
        }
        rows.sort(Comparator.comparingDouble(PlayerData::buffer).reversed());

        String frame;
        if (rows.isEmpty()) {
            // Nothing happens most of the time. Saying so every second would
            // bury the chat, so it is said once in a while - enough to show
            // that the watch is alive.
            if (globalTicks % 5 != 0) {
                globalTicks++;
                return;
            }
            frame = Msg.parse(plugin.config().prefix() + plugin.lang().text("monitor-cmd.pvp-none"));
        } else {
            // Без заголовка: в чате нужны сами строки, а не счётчик над ними.
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < rows.size(); i++) {
                if (i > 0) {
                    out.append('\n');
                }
                out.append(pvpRow(rows.get(i)));
            }
            frame = Msg.parse(out.toString());
        }
        globalTicks++;

        // This is a chat watch for staff. The console is not a chat window:
        // sending every frame there buried real warnings in a row per second.
        // A console session still works through tick() when an operator asks
        // for it explicitly with /oai monitor list.
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("oculusai.alerts")) {
                online.sendMessage(frame);
            }
        }
    }

    private String pvpRow(PlayerData data) {
        double max = plugin.config().bufferMax();
        double fraction = max > 0.0D ? data.buffer() / max : 0.0D;

        String dot = data.buffer() >= plugin.config().punishAt() ? "<red>\u25cf"
                : (data.buffer() >= plugin.config().alertAt() ? "<gold>\u25cf" : "<dark_gray>\u25cf");

        return " " + dot + " <white>" + data.name()
                + " " + Values.inline(plugin.config(), shownProbability(data), data.buffer())
                + " " + Msg.bar(fraction, 8);
    }

    private String frame(Session session) {
        List<PlayerData> rows = new ArrayList<>();
        if (session.names.isEmpty()) {
            for (PlayerData data : plugin.data().all()) {
                if (data.windowsAnalysed() > 0 || data.buffer() > 0.0D) {
                    rows.add(data);
                }
            }
        } else {
            for (String name : session.names) {
                PlayerData data = plugin.data().byName(name);
                if (data != null) {
                    rows.add(data);
                }
            }
        }
        rows.sort(Comparator.comparingDouble(PlayerData::buffer).reversed());

        StringBuilder out = new StringBuilder();
        if (session.lines % HEADER_EVERY == 0) {
            out.append(Msg.parse(plugin.config().prefix()
                    + "<gray>Monitor <dark_gray>" + rows.size()));
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

    private String row(PlayerData data) {
        double max = plugin.config().bufferMax();
        double fraction = max > 0.0D ? data.buffer() / max : 0.0D;

        String dot = data.buffer() >= plugin.config().punishAt() ? "<red>\u25cf"
                : (data.buffer() >= plugin.config().alertAt() ? "<gold>\u25cf" : "<dark_gray>\u25cf");

        return " " + dot + " <white>" + pad(data.name(), 16)
                + " " + Values.probability(plugin.config(), shownProbability(data))
                + " <gray>buf " + Values.buffer(plugin.config(), data.buffer())
                + "<dark_gray>/<gray>" + Msg.round(max, 1)
                + " " + Msg.bar(fraction, BAR_WIDTH);
    }

    private double shownProbability(PlayerData data) {
        return data.displayProbability(!plugin.detection().mathEnabled());
    }

    private static String pad(String value, int width) {
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

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
