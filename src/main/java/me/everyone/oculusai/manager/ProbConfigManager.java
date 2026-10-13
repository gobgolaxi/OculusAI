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
                "<gray>%player% <%heat%>%probability% %bar%");
        hologramFormat = config.getStringList("hologram.format");
        if (hologramFormat.isEmpty()) {
            hologramFormat = List.of(
                    "<%heat%>%probability% %bar%",
                    "<dark_gray>%trend%  <gray>%windows%");
        }
        hologramLines = Math.max(1, config.getInt("hologram.lines", 2));
        // One line by default, and that line carries only what the screen does
        // not already have: the player's name floats over his head anyway, and
        // labels like "aim" and "buffer" repeat in every frame without saying
        // anything. What is left is the reading and how full the buffer is.
        hologramCompact = config.getBoolean("hologram.compact", true);
        hologramCompactFormat = config.getString("hologram.compact-format",
                "<%heat%>%probability% %bar%");
        // A window lands about every half second during a fight, so three
        // seconds of silence is a stop rather than a gap.
        staleSeconds = Math.max(1, config.getInt("hologram.stale-seconds", 3));
        staleFormat = config.getString("hologram.stale-format", "<dark_gray>\u2014");
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
