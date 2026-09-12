package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Sound;

public final class ConfigManager implements Manager {

    private final YuppyAI plugin;

    private String prefix = "";
    private String language = "ru";
    private String apiUrl = "http://127.0.0.1:8000";
    private int apiTimeoutMs = 1500;
    private String apiKey = "";
    private String datasetKey = "";

    private int windowTicks = 20;
    private int minAttacks = 1;
    private int combatTicks = 40;
    private double minRotation = 5.0D;
    private double minAttackRotation = 2.5D;

    private double suspicion = 0.5D;
    private double gain = 1.0D;
    private double decay = 1.4D;
    private double bufferMax = 10.0D;
    private double alertAt = 1.7D;
    private double punishAt = 5.0D;
    private long alertCooldownMs = 10000L;
    private String alertSound = "BLOCK_NOTE_BLOCK_BELL";
    private float alertSoundVolume = 1.0F;
    private float alertSoundPitch = 1.2F;
    private String alertSymbol = "⚠";
    private String alertFormat = "<%primary%>%player%</%primary%> <gray>aim <white>%probability% <dark_gray>| <gray>buffer <white>%buffer%<dark_gray>/<white>%max%";
    private String alertClick = "<yellow>[Следить]";
    private String alertClickCommand = "prob";

    private boolean punishmentEnabled = false;
    private String punishmentCommand = "";
    private String punishmentBroadcast = "";
    private KickAnimation kickAnimation = KickAnimation.EXPLODE;
    private boolean evidenceEnabled = true;
    private int evidenceSeconds = 180;
    private String evidenceLabel = "cheater";
    private double evidenceFloor = 0.7D;
    private double evidenceMinBuffer = 3.0D;
    private double cancelBelow = 4.0D;

    private int progressSeconds = 15;
    private String actionBarFormat = "";
    private int hologramLines = 6;
    private int hologramSeconds = 30;
    private double hologramOffset = 1.1D;
    private boolean autoModEnabled = true;

    private boolean testServerEnabled = false;
    private String testServerWorld = "yuppyai_test";
    private int testServerScoreboardTicks = 100;
    private int testServerModelRefreshSeconds = 30;

    public ConfigManager(YuppyAI plugin) {
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
        FileConfiguration config = plugin.getConfig();

        prefix = config.getString("prefix", "");
        language = config.getString("language", "ru");
        String url = config.getString("api.url", "");
        apiUrl = stripTrailingSlash(url == null || url.isBlank() ? "http://127.0.0.1:8000" : url);
        apiTimeoutMs = config.getInt("api.timeout-ms", 1500);
        apiKey = config.getString("api.key", "");
        datasetKey = config.getString("api.dataset-key", "");

        windowTicks = Math.max(3, config.getInt("analysis.window-ticks", 20));
        minAttacks = config.getInt("analysis.min-attacks", 1);
        combatTicks = config.getInt("analysis.combat-ticks", 40);
        minRotation = config.getDouble("analysis.min-rotation", 5.0D);
        minAttackRotation = config.getDouble("analysis.min-attack-rotation", 2.5D);

        suspicion = config.getDouble("buffer.suspicion", 0.5D);
        gain = config.getDouble("buffer.gain", 1.0D);
        decay = config.getDouble("buffer.decay", 1.4D);
        bufferMax = config.getDouble("buffer.max", 10.0D);
        alertAt = config.getDouble("buffer.alert-at", 1.7D);
        punishAt = config.getDouble("buffer.punish-at", 5.0D);
        alertCooldownMs = config.getLong("buffer.alert-cooldown-ms", 10000L);
        alertSound = config.getString("buffer.alert-sound", "BLOCK_NOTE_BLOCK_BELL");
        alertSoundVolume = (float) config.getDouble("buffer.alert-sound-volume", 1.0D);
        alertSoundPitch = (float) config.getDouble("buffer.alert-sound-pitch", 1.2D);
        alertSymbol = config.getString("display.alert-symbol", "⚠");
        alertFormat = config.getString("display.alert-format",
                "<%primary%>%player%</%primary%> <gray>aim <white>%probability% <dark_gray>| <gray>buffer <white>%buffer%<dark_gray>/<white>%max%");
        alertClick = config.getString("display.alert-click", "<yellow>[Следить]");
        alertClickCommand = config.getString("display.alert-click-command", "prob");

        punishmentEnabled = config.getBoolean("punishment.enabled", false);
        punishmentCommand = config.getString("punishment.command", "");
        punishmentBroadcast = config.getString("punishment.broadcast", "");
        kickAnimation = KickAnimation.parse(config.getString("punishment.kick-animation", "EXPLODE"));
        evidenceEnabled = config.getBoolean("punishment.evidence.enabled", true);
        evidenceSeconds = Math.max(1, config.getInt("punishment.evidence.seconds", 180));
        evidenceLabel = config.getString("punishment.evidence.label", "cheater");
        evidenceFloor = config.getDouble("punishment.evidence.min-probability", 0.7D);
        evidenceMinBuffer = config.getDouble("punishment.evidence.min-buffer", 3.0D);
        cancelBelow = config.getDouble("punishment.evidence.cancel-below", 4.0D);

        progressSeconds = config.getInt("recording.progress-seconds", 15);
        actionBarFormat = config.getString("display.actionbar", "");
        hologramLines = config.getInt("display.hologram-lines", 6);
        hologramSeconds = config.getInt("display.hologram-seconds", 30);
        hologramOffset = config.getDouble("display.hologram-offset", 1.1D);
        autoModEnabled = config.getBoolean("automod.enabled", true);

        testServerEnabled = config.getBoolean("test-server.enabled", false);
        testServerWorld = config.getString("test-server.world", "yuppyai_test");
        testServerScoreboardTicks = Math.max(20, config.getInt("test-server.scoreboard-refresh-ticks", 100));
        testServerModelRefreshSeconds = Math.max(5, config.getInt("test-server.model-refresh-seconds", 30));
    }

    private String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String prefix() {
        return plugin.theme() == null ? prefix : plugin.theme().prefix(prefix);
    }

    public String language() {
        return language;
    }

    public String apiUrl() {
        return apiUrl;
    }

    public int apiTimeoutMs() {
        return apiTimeoutMs;
    }

    public String apiKey() {
        return apiKey;
    }

    public String datasetKey() {
        return datasetKey.isBlank() ? apiKey : datasetKey;
    }

    public int windowTicks() {
        return windowTicks;
    }

    public int minAttacks() {
        return minAttacks;
    }

    public long combatMs() {
        return combatTicks * 50L;
    }

    public double minRotation() {
        return minRotation;
    }

    public double minAttackRotation() {
        return minAttackRotation;
    }

    public double suspicion() {
        return suspicion;
    }

    public double gain() {
        return gain;
    }

    public double decay() {
        return decay;
    }

    public double bufferMax() {
        return bufferMax;
    }

    public double alertAt() {
        return alertAt;
    }

    public double punishAt() {
        return punishAt;
    }

    public long alertCooldownMs() {
        return alertCooldownMs;
    }

    public String alertSoundName() {
        return alertSound;
    }

    public Sound alertSound() {
        return parseSound(alertSound, Sound.BLOCK_NOTE_BLOCK_BELL);
    }

    public float alertSoundVolume() {
        return alertSoundVolume;
    }

    public float alertSoundPitch() {
        return alertSoundPitch;
    }

    public String alertSymbol() {
        return alertSymbol;
    }

    public String alertFormat() {
        return alertFormat;
    }

    public String alertClick() {
        return alertClick;
    }

    public String alertClickCommand() {
        return alertClickCommand;
    }

    public boolean punishmentEnabled() {
        return punishmentEnabled;
    }

    public String punishmentCommand() {
        return punishmentCommand;
    }

    public String punishmentBroadcast() {
        return punishmentBroadcast;
    }

    public KickAnimation kickAnimation() {
        return kickAnimation;
    }

    public boolean evidenceEnabled() {
        return evidenceEnabled;
    }

    public int evidenceSeconds() {
        return evidenceSeconds;
    }

    public String evidenceLabel() {
        return evidenceLabel;
    }

    public double evidenceFloor() {
        return evidenceFloor;
    }

    public double evidenceMinBuffer() {
        return evidenceMinBuffer;
    }

    public double cancelBelow() {
        return cancelBelow;
    }

    public String actionBarFormat() {
        return actionBarFormat;
    }

    public int hologramLines() {
        return hologramLines;
    }

    public int hologramSeconds() {
        return hologramSeconds;
    }

    public double hologramOffset() {
        return hologramOffset;
    }

    public int progressSeconds() {
        return progressSeconds;
    }

    public boolean autoModEnabled() {
        return autoModEnabled;
    }

    public boolean testServerEnabled() {
        return testServerEnabled;
    }

    public String testServerWorld() {
        return testServerWorld;
    }

    public int testServerScoreboardTicks() {
        return testServerScoreboardTicks;
    }

    public int testServerModelRefreshSeconds() {
        return testServerModelRefreshSeconds;
    }

    private Sound parseSound(String value, Sound fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Sound.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
