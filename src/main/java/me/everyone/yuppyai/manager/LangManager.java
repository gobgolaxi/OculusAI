package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class LangManager implements Manager {

    private final YuppyAI plugin;
    private YamlConfiguration lang;
    private YamlConfiguration defaults;

    public LangManager(YuppyAI plugin) {
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
            plugin.getLogger().log(java.util.logging.Level.WARNING,
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

    /**
     * Reads the bundled defaults that ship inside the jar, used both as a
     * fallback for keys the operator's file is missing and as the source of keys
     * to write into that file on load.
     */
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

    /**
     * Copies keys the bundled language has and the operator's file does not into
     * that file.
     *
     * <p>Without this a lang file written by an older version keeps exactly the
     * keys it was created with, so every string added later shows up in game as
     * its raw key. Existing values are never touched, so anything the operator
     * translated or reworded stays theirs.
     */
    private void adoptNewKeys(File file) {
        if (defaults == null || lang == null || !file.isFile()) {
            return;
        }

        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || lang.contains(key)) {
                continue;
            }
            String parent = key.contains(".") ? key.substring(0, key.lastIndexOf('.')) : "";
            if (!parent.isEmpty() && lang.contains(parent) && !lang.isConfigurationSection(parent)) {
                // The operator turned that section into a plain value on purpose.
                continue;
            }
            lang.set(key, defaults.get(key));
            changed = true;
        }

        if (!changed) {
            return;
        }
        try {
            lang.save(file);
        } catch (IOException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Could not save new language keys into " + file.getAbsolutePath()
                            + "; they still resolve from the bundled defaults", exception);
        }
    }

    public String text(String key) {
        return Msg.parse(raw(key, key));
    }

    public String text(String key, Map<String, String> placeholders) {
        return Msg.parse(raw(key, key), placeholders);
    }

    public String textOr(String key, String fallback) {
        return Msg.parse(raw(key, fallback));
    }

    /** The operator's value, the bundled one when they have none, else the given fallback. */
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
