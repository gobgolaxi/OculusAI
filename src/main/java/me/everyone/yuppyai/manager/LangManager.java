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

    public String text(String key) {
        if (lang == null) {
            return key;
        }
        return Msg.parse(lang.getString(key, key));
    }

    public String text(String key, Map<String, String> placeholders) {
        if (lang == null) {
            return key;
        }
        return Msg.parse(lang.getString(key, key), placeholders);
    }

    public String textOr(String key, String fallback) {
        if (lang == null) {
            return Msg.parse(fallback);
        }
        return Msg.parse(lang.getString(key, fallback));
    }
}
