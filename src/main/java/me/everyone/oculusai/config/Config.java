package me.everyone.oculusai.config;


import me.everyone.oculusai.core.Service;
import java.util.Locale;
import me.everyone.oculusai.OculusAI;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;

public final class Config implements Service {

    private final OculusAI plugin;

    private String prefix = "";
    private String language = "ru";
    private String apiUrl = "https://oculusai.space";
    private int apiTimeoutMs = 1500;
    private String apiKey = "";
    private String site = "https://oculusai.space";
    private String datasetKey = "";

    private int windowTicks = 20;
    private int minAttacks = 1;
    private int combatTicks = 40;
    private double minRotation = 5.0D;
    private double minAttackRotation = 2.5D;
    private int runningHorizon = 15;
    private int smoothWindows = 3;
    private boolean updateEnabled = true;
    private boolean updateDownload = true;
    private int updateCheckHours = 6;

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

    private boolean punishmentEnabled = false;
    private String punishmentCommand = "";
    private String punishmentBroadcast = "";
    private boolean evidenceEnabled = true;
    private int evidenceSeconds = 180;
    private String evidenceLabel = "cheater";
    private double evidenceFloor = 0.7D;
    private double evidenceMinBuffer = 3.0D;
    private double cancelBelow = 4.0D;

    private int progressSeconds = 15;
    private int monitorPvpSeconds = 5;
    private String actionBarFormat = "";
    private int hologramLines = 6;
    private int hologramSeconds = 30;
    private double hologramOffset = 1.1D;

    private int historyKeep = 2000;
    private int historyFlushSeconds = 30;

    private boolean testServerEnabled = false;
    private String testServerWorld = "oculusai_test";
    private int testServerScoreboardTicks = 100;
    private int testServerModelRefreshSeconds = 30;

    private boolean checksEnabled = true;
    private boolean checkTestMode = true;
    private long checkAlertCooldownMs = 5000L;
    private int checkConfirmations = 2;
    private boolean wallCheckEnabled = true;
    private double wallCheckMaxDistance = 6.0D;
    private long checkWallCooldownMs = 5000L;
    private boolean checkWallTestOnly = false;
    private boolean checkAimEnabled = true;
    private int checkAimConfirmations = 1;
    private long checkAimCooldownMs = 10000L;
    private boolean checkAimTestOnly = false;

    public Config(OculusAI plugin) {
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
        // Read before api.url: an empty url falls back to this, so the site has
        // to be the one from this config and not the one from the last load.
        site = stripTrailingSlash(config.getString("api.site", "https://oculusai.space"));
        String url = config.getString("api.url", "");
        // Empty means "the public service": a fresh install should reach the
        // real API without editing anything, and a local instance is the case
        // that sets the field.
        apiUrl = stripTrailingSlash(url == null || url.isBlank() ? site : url);
        apiTimeoutMs = config.getInt("api.timeout-ms", 1500);
        apiKey = config.getString("api.key", "");
        datasetKey = config.getString("api.dataset-key", "");

        windowTicks = Math.max(3, config.getInt("analysis.window-ticks", 20));
        minAttacks = config.getInt("analysis.min-attacks", 1);
        combatTicks = config.getInt("analysis.combat-ticks", 40);
        minRotation = config.getDouble("analysis.min-rotation", 5.0D);
        minAttackRotation = config.getDouble("analysis.min-attack-rotation", 2.5D);
        runningHorizon = Math.max(0, config.getInt("analysis.running-horizon", 15));
        smoothWindows = Math.max(1, config.getInt("buffer.smooth-windows", 3));
        updateEnabled = config.getBoolean("update.enabled", true);
        updateDownload = config.getBoolean("update.download", true);
        updateCheckHours = Math.max(1, config.getInt("update.check-hours", 6));

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

        punishmentEnabled = config.getBoolean("punishment.enabled", false);
        punishmentCommand = config.getString("punishment.command", "");
        punishmentBroadcast = config.getString("punishment.broadcast", "");
        evidenceEnabled = config.getBoolean("punishment.evidence.enabled", true);
        evidenceSeconds = Math.max(1, config.getInt("punishment.evidence.seconds", 180));
        evidenceLabel = config.getString("punishment.evidence.label", "cheater");
        evidenceFloor = config.getDouble("punishment.evidence.min-probability", 0.7D);
        evidenceMinBuffer = config.getDouble("punishment.evidence.min-buffer", 3.0D);
        cancelBelow = config.getDouble("punishment.evidence.cancel-below", 4.0D);

        progressSeconds = config.getInt("recording.progress-seconds", 15);
        monitorPvpSeconds = Math.max(1, config.getInt("monitor.pvp-seconds", 5));
        actionBarFormat = config.getString("display.actionbar", "");
        hologramLines = config.getInt("display.hologram-lines", 6);
        hologramSeconds = config.getInt("display.hologram-seconds", 30);
        hologramOffset = config.getDouble("display.hologram-offset", 1.1D);

        historyKeep = Math.max(1, config.getInt("history.keep", 2000));
        historyFlushSeconds = Math.max(1, config.getInt("history.flush-seconds", 30));

        testServerEnabled = config.getBoolean("test-server.enabled", false);
        testServerWorld = config.getString("test-server.world", "oculusai_test");
        testServerScoreboardTicks = Math.max(20, config.getInt("test-server.scoreboard-refresh-ticks", 100));
        testServerModelRefreshSeconds = Math.max(5, config.getInt("test-server.model-refresh-seconds", 30));

        checksEnabled = config.getBoolean("checks.enabled", true);
        checkTestMode = config.getBoolean("checks.test-mode", true);
        checkAlertCooldownMs = Math.max(0L, config.getLong("checks.alert-cooldown-ms", 5000L));
        checkConfirmations = Math.max(1, config.getInt("checks.confirmations", 2));
        wallCheckEnabled = config.getBoolean("checks.wall.enabled", true);
        wallCheckMaxDistance = Math.max(0.5D, config.getDouble("checks.wall.max-distance", 6.0D));
        checkWallCooldownMs = Math.max(0L, config.getLong(
                "checks.wall.cooldown-ms", checkAlertCooldownMs));
        checkWallTestOnly = config.getBoolean("checks.wall.test-only", false);
        checkAimEnabled = config.getBoolean("checks.aim.enabled", true);
        checkAimConfirmations = Math.max(1, config.getInt("checks.aim.confirmations", 1));
        checkAimCooldownMs = Math.max(0L, config.getLong(
                "checks.aim.cooldown-ms", alertCooldownMs));
        checkAimTestOnly = config.getBoolean("checks.aim.test-only", false);
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

    public String site() {
        return site;
    }

    public boolean connected() {
        return !apiKey.isBlank();
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

    public int runningHorizon() {
        return runningHorizon;
    }

    public int smoothWindows() {
        return smoothWindows;
    }

    public boolean updateEnabled() {
        return updateEnabled;
    }

    public boolean updateDownload() {
        return updateDownload;
    }

    public int updateCheckHours() {
        return updateCheckHours;
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

    public boolean punishmentEnabled() {
        return punishmentEnabled;
    }

    public String punishmentCommand() {
        return punishmentCommand;
    }

    public String punishmentBroadcast() {
        return punishmentBroadcast;
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

    /** How long a player counts as "in PvP" after the last hit on a player. */
    public long monitorPvpMs() {
        return monitorPvpSeconds * 1000L;
    }

    public int historyKeep() {
        return historyKeep;
    }

    public int historyFlushSeconds() {
        return historyFlushSeconds;
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

    public boolean checksEnabled() {
        return checksEnabled;
    }

    public boolean checkTestMode() {
        return checkTestMode;
    }

    public long checkAlertCooldownMs() {
        return checkAlertCooldownMs;
    }

    public int checkConfirmations() {
        return checkConfirmations;
    }

    public boolean wallCheckEnabled() {
        return wallCheckEnabled;
    }

    public double wallCheckMaxDistance() {
        return wallCheckMaxDistance;
    }

    public long checkWallCooldownMs() {
        return checkWallCooldownMs;
    }

    public boolean checkWallTestOnly() {
        return checkWallTestOnly;
    }

    public boolean checkAimEnabled() {
        return checkAimEnabled;
    }

    public int checkAimConfirmations() {
        return checkAimConfirmations;
    }

    public long checkAimCooldownMs() {
        return checkAimCooldownMs;
    }

    public boolean checkAimTestOnly() {
        return checkAimTestOnly;
    }

    private Sound parseSound(String value, Sound fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Sound.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
