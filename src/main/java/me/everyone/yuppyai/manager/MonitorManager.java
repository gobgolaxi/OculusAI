package me.everyone.yuppyai.manager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class MonitorManager implements Manager {

    public static final int PERIOD_TICKS = 20;

    private static final int HEADER_EVERY = 15;

    private static final int MAX_ROWS = 12;
    private static final int BAR_WIDTH = 10;

    private static final String CONSOLE = "console";

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
                + " <gray>" + pad(Msg.percent(data.probability()), 4)
                + " " + Msg.bar(fraction, BAR_WIDTH);
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
