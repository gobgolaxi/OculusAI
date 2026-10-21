package me.everyone.oculusai.config;


import me.everyone.oculusai.core.Service;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import org.bukkit.configuration.file.YamlConfiguration;

public final class HudConfig implements Service {

    /** The passenger sits at this height above the player's feet. */
    private static final double HOLOGRAM_BASE = 1.15D;
    /** Never put the text inside the player's head. */
    private static final double MIN_HOLOGRAM_HEIGHT = 2.25D;
    /** High enough to clear any fight, low enough not to look detached. */
    private static final double MAX_HOLOGRAM_HEIGHT = 5.15D;

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

    public HudConfig(OculusAI plugin) {
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
        // A triangle for where the buffer is heading, then the name, then the
        // two numbers. The name stays here - unlike the hologram, this line
        // does not hang over the player it is about.
        actionBarFormat = config.getString("actionbar",
                "%arrow% <white>%player% <dark_gray>| <gray>%probability% "
                + "<dark_gray>| <white>%buffer%<dark_gray>/<white>%max_buffer%");
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
        // nameplate is right under it. No labels and no buffer figure either -
        // the reading and the length of the bar say the same thing shorter.
        hologramCompactFormat = config.getString("hologram.compact-format",
                "<white>%probability% %bar%");
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

    /** The actual Y of the top hologram line above the target's feet. The
     *  clamp is what keeps a low offset from putting text in the player. */
    public double hologramHeight() {
        return Math.max(MIN_HOLOGRAM_HEIGHT, hologramOffset + HOLOGRAM_BASE);
    }

    public double minHologramHeight() {
        return MIN_HOLOGRAM_HEIGHT;
    }

    public double maxHologramHeight() {
        return MAX_HOLOGRAM_HEIGHT;
    }

    /** Moves the hologram up or down and saves the new offset to probcfg.yml.
     *  Returns false when the file could not be written. */
    public boolean adjustHologramHeight(double delta) {
        return setHologramHeight(hologramHeight() + delta);
    }

    public boolean setHologramHeight(double height) {
        double clamped = Math.max(MIN_HOLOGRAM_HEIGHT,
                Math.min(MAX_HOLOGRAM_HEIGHT, round2(height)));
        double offset = round2(clamped - HOLOGRAM_BASE);

        File file = new File(plugin.getDataFolder(), "probcfg.yml");
        if (!file.isFile()) {
            plugin.saveResource("probcfg.yml", false);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read " + file.getAbsolutePath() + " to change the hologram height",
                    exception);
            return false;
        }

        String value = Double.toString(offset);
        int hologramLine = -1;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.equals("hologram:")) {
                hologramLine = i;
                continue;
            }
            if (hologramLine < 0) {
                continue;
            }
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            if (trimmed.startsWith("offset:")) {
                int at = line.indexOf("offset:");
                lines.set(i, line.substring(0, at) + "offset: " + value);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            if (hologramLine < 0) {
                lines.add("");
                lines.add("hologram:");
                hologramLine = lines.size() - 1;
            }
            lines.add(hologramLine + 1, "  offset: " + value);
        }

        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not save the hologram height in " + file.getAbsolutePath(), exception);
            return false;
        }
        hologramOffset = offset;
        return true;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
