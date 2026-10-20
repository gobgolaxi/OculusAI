package me.everyone.oculusai.config;


import me.everyone.oculusai.core.Service;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.util.Msg;
import org.bukkit.configuration.file.YamlConfiguration;

public final class Language implements Service {

    /** The old aim reason printed both placeholders literally because the
     *  check used to return it without a placeholder map. Existing language
     *  files keep custom translations, but this known bundled value is safe
     *  to replace with the current line. */
    private static final String LEGACY_AIM_REASON = "prob %probability%, buffer %buffer%";
    private static final String LEGACY_STATUS_EN =
            "<gray>Analysis %analysis% <dark_gray>| <gray>Attacks %checks% <dark_gray>| "
                    + "<gray>Wall %wall% <dark_gray>| <gray>Aim %aim% <dark_gray>| "
                    + "<gray>Punishment %punish%";
    private static final String LEGACY_STATUS_RU =
            "<gray>Анализ %analysis% <dark_gray>| <gray>Удары %checks% <dark_gray>| "
                    + "<gray>Wall %wall% <dark_gray>| <gray>Aim %aim% <dark_gray>| "
                    + "<gray>Наказание %punish%";

    private final OculusAI plugin;
    private YamlConfiguration lang;
    private YamlConfiguration defaults;

    public Language(OculusAI plugin) {
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
        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create lang folder at " + folder.getAbsolutePath());
        }
        ensureBundledFile("lang/en.yml");
        ensureBundledFile("lang/ru.yml");

        String selected = plugin.config().language();
        File file = new File(folder, selected + ".yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("Language " + selected + " not found, falling back to en.yml");
            file = new File(folder, "en.yml");
        }
        lang = loadConfiguration(file, "lang/" + file.getName());
        defaults = loadBundled("lang/" + file.getName());
        adoptNewKeys(file);
    }

    private void ensureBundledFile(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (file.isFile()) {
            return;
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create parent folder for " + file.getAbsolutePath());
            return;
        }

        try (InputStream input = plugin.getResource(path)) {
            if (input == null) {
                plugin.getLogger().warning("Bundled resource " + path + " is missing from the plugin jar");
                return;
            }
            Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not write bundled resource " + path + " to " + file.getAbsolutePath(), exception);
        }
    }

    private YamlConfiguration loadConfiguration(File file, String bundledPath) {
        if (file.isFile()) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                YamlConfiguration configuration = new YamlConfiguration();
                configuration.load(reader);
                return configuration;
            } catch (Exception exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Language file " + file.getAbsolutePath() + " is invalid; backing it up and restoring " + bundledPath, exception);
                backupBrokenFile(file);
                ensureBundledFile(bundledPath);
            }
        }

        try (InputStream input = plugin.getResource(bundledPath)) {
            if (input == null) {
                plugin.getLogger().warning("Bundled language " + bundledPath + " is missing; using empty configuration");
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not read bundled language " + bundledPath + "; using empty configuration", exception);
            return new YamlConfiguration();
        }
    }

    private void backupBrokenFile(File file) {
        if (!file.isFile()) {
            return;
        }

        File backup = new File(file.getParentFile(), file.getName() + ".broken");
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(file.toPath());
        } catch (IOException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not back up broken language file " + file.getAbsolutePath(), exception);
        }
    }

    private YamlConfiguration loadBundled(String bundledPath) {
        try (InputStream input = plugin.getResource(bundledPath)) {
            if (input == null) {
                plugin.getLogger().warning("Bundled language " + bundledPath + " is missing; using empty defaults");
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not read bundled language " + bundledPath + "; using empty defaults", exception);
            return new YamlConfiguration();
        }
    }

    private void adoptNewKeys(File file) {
        if (defaults == null || lang == null || !file.isFile()) {
            return;
        }

        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || lang.contains(key)) {
                continue;
            }
            String parent = key.contains(".") ? key.substring(0, key.lastIndexOf('.')) : "";
            if (!parent.isEmpty() && lang.contains(parent) && !lang.isConfigurationSection(parent)) {
                continue;
            }
            lang.set(key, defaults.get(key));
            added++;
        }

        int migrated = 0;
        String currentAimReason = lang.getString("check.reason.aim");
        String bundledAimReason = defaults.getString("check.reason.aim");
        if (LEGACY_AIM_REASON.equals(currentAimReason)
                && bundledAimReason != null && !bundledAimReason.equals(currentAimReason)) {
            lang.set("check.reason.aim", bundledAimReason);
            migrated++;
        }

        String currentStatus = lang.getString("checks.status");
        String bundledStatus = defaults.getString("checks.status");
        if (bundledStatus != null && !bundledStatus.equals(currentStatus)
                && (LEGACY_STATUS_EN.equals(currentStatus)
                || LEGACY_STATUS_RU.equals(currentStatus))) {
            lang.set("checks.status", bundledStatus);
            migrated++;
        }

        // Old alert lines only printed %detail%. Keep a custom translation,
        // but append the coloured probability/buffer block when it has no
        // metrics at all. New bundled lines already contain %metrics%.
        migrated += ensureMetrics("check.test-line");
        migrated += ensureMetrics("check.alert-line");

        if (added == 0 && migrated == 0) {
            return;
        }
        try {
            lang.save(file);
            plugin.getLogger().info("Language " + file.getName() + " updated: "
                    + added + " new keys, " + migrated + " migrated values");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not save new language keys into " + file.getAbsolutePath()
                            + "; they still resolve from the bundled defaults", exception);
        }
    }

    private int ensureMetrics(String key) {
        String value = lang.getString(key);
        if (value == null || value.isBlank()
                || value.contains("%metrics%")
                || value.contains("%probability%")
                || value.contains("%buffer%")) {
            return 0;
        }
        lang.set(key, value + " <dark_gray>· %metrics%");
        return 1;
    }

    public String text(String key) {
        return Msg.parse(raw(key, key));
    }

    public String text(String key, Map<String, String> placeholders) {
        return Msg.parse(raw(key, key), placeholders);
    }

    public String plain(String key) {
        return Msg.strip(raw(key, key));
    }

    public String textOr(String key, String fallback) {
        return Msg.parse(raw(key, fallback));
    }

    private String raw(String key, String fallback) {
        if (lang != null && lang.contains(key)) {
            return lang.getString(key, fallback);
        }
        if (defaults != null && defaults.contains(key)) {
            return defaults.getString(key, fallback);
        }
        return fallback;
    }
}
