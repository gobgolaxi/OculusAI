package me.everyone.oculusai.manager;

import java.io.File;
import java.util.List;
import me.everyone.oculusai.OculusAI;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ProbConfigManager implements Manager {

    private final OculusAI plugin;

    private String actionBarFormat = "";
    private List<String> hologramFormat = List.of();
    private int hologramLines = 6;
    private boolean hologramCompact = true;
    private String hologramCompactFormat = "";
    private int staleSeconds = 3;
    private String staleFormat = "";
    private int hologramSeconds = 300;
    private double hologramOffset = 1.1D;

    public ProbConfigManager(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        load();
    }

    @Override
    public void reload() {
        load();
    }

    private void load() {
        File file = new File(plugin.getDataFolder(), "probcfg.yml");
        if (!file.isFile()) {
            plugin.saveResource("probcfg.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        actionBarFormat = config.getString("actionbar",
                "<%accent%>⚠ <white>%bar% <dark_gray>| <white>%player% <dark_gray>| <gray>%probability%");
        hologramFormat = config.getStringList("hologram.format");
        if (hologramFormat.isEmpty()) {
            hologramFormat = List.of(
                    "<gray>⚡ <white>%probability% <dark_gray>| <gray>❤ buffer <white>%buffer%<dark_gray>/<white>%max_buffer%",
                    "<gray>♫ %bar%",
                    "<dark_gray>♬ trend <gray>%trend%",
                    "<dark_gray>☹ %state%",
                    "<dark_gray>♠ windows <white>%windows%");
        }
        hologramLines = Math.max(1, config.getInt("hologram.lines", 5));
        // One line by default. Six lines of hologram over a player's head in a
        // fight is a wall between the watcher and what they are watching, and
        // the two numbers that decide anything - the reading and the buffer -
        // fit on one line with room to spare. The multi-line `format` above is
        // still there for anyone who sets compact to false.
        hologramCompact = config.getBoolean("hologram.compact", true);
        // No name on the hologram: it floats over the player, whose own
        // nameplate is right under it.
        hologramCompactFormat = config.getString("hologram.compact-format",
                "<gray>aim <white>%probability% "
                + "<dark_gray>| <gray>buf <white>%buffer%<dark_gray>/<white>%max_buffer% "
                + "<dark_gray>| %bar%");
        // A window lands about every half second during a fight, so three
        // seconds of silence is a stop rather than a gap.
        staleSeconds = Math.max(1, config.getInt("hologram.stale-seconds", 3));
        staleFormat = config.getString("hologram.stale-format", "&cno analysing");
        hologramSeconds = Math.max(1, config.getInt("hologram.seconds", 300));
        hologramOffset = config.getDouble("hologram.offset", 1.1D);
    }

    public String actionBarFormat() {
        return actionBarFormat;
    }

    public int hologramLines() {
        return hologramLines;
    }

    public List<String> hologramFormat() {
        return hologramFormat;
    }

    public boolean hologramCompact() {
        return hologramCompact;
    }

    public String hologramCompactFormat() {
        return hologramCompactFormat;
    }

    public long staleMs() {
        return staleSeconds * 1000L;
    }

    public String staleFormat() {
        return staleFormat;
    }

    public int hologramSeconds() {
        return hologramSeconds;
    }

    public double hologramOffset() {
        return hologramOffset;
    }
}
