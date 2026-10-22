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
import java.util.Set;
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
    /** The pvp feed carries a name, two numbers and a bar on one chat line, so
     *  its bar is shorter than the one in the padded list. */
    private static final int PVP_BAR_WIDTH = 8;

    private static final String CONSOLE = "console";
    /** A tiny state file rather than config.yml: saving Bukkit's config on
     *  every toggle rewrites the file and can drop its comments. */
    private static final String STATE_FILE = "monitor.state";

    private static final class Session {
        private final String id;
        private final CommandSender sender;
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
    /** Everybody who asked for the watch over all of pvp, by player UUID or
     *  {@link #CONSOLE}. Each of them gets the frames and nobody else does:
     *  /oai monitor is a private feed per operator, not a staff broadcast and
     *  not a single feed two admins have to fight over. */
    private final Set<String> globalWatchers = ConcurrentHashMap.newKeySet();
    /** Ticks since the watch was switched on - used to say "nobody" now and then. */
    private int globalTicks;

    public Monitor(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        loadGlobalWatchers();
        globalTicks = 0;
        task = plugin.ticks().every(PERIOD_TICKS, this::tick);
    }

    @Override
    public void reload() {
        // /oai reload keeps the watch the operator turned on; the state file
        // is the source of truth, config.yml is the first-run default.
        loadGlobalWatchers();
        globalTicks = 0;
    }

    /** Reads who was watching when the server last stopped: one recipient per
     *  line, either {@link #CONSOLE} or a player UUID. A line that is neither
     *  is skipped - older versions wrote a plain "on", which names nobody, and
     *  turning that into a console feed would spam a console that never asked
     *  for one. */
    private void loadGlobalWatchers() {
        globalWatchers.clear();
        Path state = plugin.getDataFolder().toPath().resolve(STATE_FILE);
        try {
            if (Files.isRegularFile(state)) {
                for (String line : Files.readAllLines(state, StandardCharsets.UTF_8)) {
                    String value = line.trim();
                    if (value.isEmpty() || value.equals("off")) {
                        continue;
                    }
                    if (value.equals(CONSOLE)) {
                        globalWatchers.add(CONSOLE);
                        continue;
                    }
                    try {
                        globalWatchers.add(UUID.fromString(value).toString());
                    } catch (IllegalArgumentException notAWatcher) {
                        // An "on" from an older state file, or a hand-edit.
                    }
                }
                return;
            }
        } catch (IOException failure) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not read " + state + "; using monitor.enabled", failure);
        }
        if (plugin.config().monitorEnabled()) {
            globalWatchers.add(CONSOLE);
        }
    }

    private void saveGlobal() {
        Path state = plugin.getDataFolder().toPath().resolve(STATE_FILE);
        try {
            Files.createDirectories(state.getParent());
            String value = globalWatchers.isEmpty()
                    ? "off" : String.join("\n", new ArrayList<>(globalWatchers));
            Files.writeString(state, value + "\n", StandardCharsets.UTF_8);
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
        globalWatchers.clear();
    }

    /** Whether anybody at all is on the watch over everybody. */
    public boolean globalWatching() {
        return !globalWatchers.isEmpty();
    }

    /** Whether this sender is. */
    public boolean globalWatching(CommandSender sender) {
        return globalWatchers.contains(idOf(sender));
    }

    /** Switches the watch over everybody on or off for this sender, and only
     *  for them. Returns their new state.
     *
     *  <p>Two admins watching at once is the normal case on a staffed server,
     *  so this is a per-operator subscription: one of them turning it off
     *  cannot turn it off for the other, and turning it on cannot take the
     *  feed away from them either. The list is saved, so a restart - or the
     *  operator reconnecting - does not quietly stop the watch. */
    public boolean toggleGlobal(CommandSender sender) {
        String id = idOf(sender);
        boolean on = !globalWatchers.remove(id);
        if (on) {
            globalWatchers.add(id);
        }
        globalTicks = 0;
        saveGlobal();
        return on;
    }

    /** The console, an online player, or null when that id is nobody who can
     *  be written to right now. */
    private CommandSender senderOf(String id) {
        if (id == null) {
            return null;
        }
        if (id.equals(CONSOLE)) {
            return plugin.getServer().getConsoleSender();
        }
        Player player;
        try {
            player = plugin.getServer().getPlayer(UUID.fromString(id));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return player != null && player.isOnline() ? player : null;
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

    public void start(CommandSender sender, List<String> names) {
        Session session = new Session(idOf(sender), sender, List.copyOf(names));
        sessions.put(session.id, session);
    }

    public boolean stop(CommandSender sender) {
        return sessions.remove(idOf(sender)) != null;
    }

    public void forget(UUID uuid) {
        sessions.remove(uuid.toString());
    }

    private void tick() {
        if (!globalWatchers.isEmpty()) {
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
        // A watcher may be offline - they logged off with the watch on, and it
        // is kept so it comes back with them. With none of them reachable
        // there is nobody to write to, and sorting every fighter once a second
        // for a discarded string is work for nothing.
        List<CommandSender> targets = new ArrayList<>();
        for (String id : globalWatchers) {
            CommandSender watcher = senderOf(id);
            if (watcher != null) {
                targets.add(watcher);
            }
        }
        if (targets.isEmpty()) {
            return;
        }

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

        // This is a private chat watch: only the operators who turned it on
        // get the frames. The console is among them only when the console
        // itself asked for the watch with /oai monitor.
        for (CommandSender watcher : targets) {
            watcher.sendMessage(frame);
        }
    }

    /** The coloured dot both rows start with: grey below the alert line,
     *  gold over it, red once the buffer is at punishing level. */
    private String dot(PlayerData data) {
        if (data.buffer() >= plugin.config().punishAt()) {
            return "<red>\u25cf";
        }
        return data.buffer() >= plugin.config().alertAt() ? "<gold>\u25cf" : "<dark_gray>\u25cf";
    }

    /** How full the buffer is, 0..1, for the bar. */
    private double fraction(PlayerData data) {
        double max = plugin.config().bufferMax();
        return max > 0.0D ? data.buffer() / max : 0.0D;
    }

    private String pvpRow(PlayerData data) {
        return " " + dot(data) + " <white><bold>" + data.name()
                + " " + Values.inline(plugin.config(), shownProbability(data), data.buffer())
                + " " + Msg.bar(fraction(data), PVP_BAR_WIDTH);
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
            if (out.length() > 0) {
                out.append('\n');
            }
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
        return " " + dot(data) + " <white>" + pad(data.name(), 16)
                + " " + Values.probability(plugin.config(), shownProbability(data))
                + " <gray>buf " + Values.buffer(plugin.config(), data.buffer())
                + "<dark_gray>/<gray>" + Msg.round(plugin.config().bufferMax(), 1)
                + " " + Msg.bar(fraction(data), BAR_WIDTH);
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
