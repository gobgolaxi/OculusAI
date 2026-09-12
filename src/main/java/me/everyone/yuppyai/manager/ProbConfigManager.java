package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

public final class ProbConfigManager implements Manager {

    private final YuppyAI plugin;

    private String actionBarFormat = "";
    private List<String> hologramFormat = List.of();
    private int hologramLines = 6;
    private int hologramSeconds = 300;
    private double hologramOffset = 1.1D;

    public ProbConfigManager(YuppyAI plugin) {
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
                    "<dark_gray>◆◆◆ <gradient:%primary%:%secondary%><bold>%player%</bold></gradient> <dark_gray>◆◆◆",
                    "<gray>⚡ <white>%probability% <dark_gray>| <gray>❤ buffer <white>%buffer%<dark_gray>/<white>%max_buffer%",
                    "<gray>♫ %bar%",
                    "<dark_gray>♬ trend <gray>%trend%",
                    "<dark_gray>☹ %state%",
                    "<dark_gray>♠ windows <white>%windows%");
        }
        hologramLines = Math.max(1, config.getInt("hologram.lines", 6));
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

    public int hologramSeconds() {
        return hologramSeconds;
    }

    public double hologramOffset() {
        return hologramOffset;
    }
}
