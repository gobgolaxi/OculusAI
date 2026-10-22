package me.everyone.oculusai.config;


import me.everyone.oculusai.core.Service;
import java.util.Locale;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.punishment.Animations;
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
    private int updateRemindSeconds = 15;

    private double suspicion = 0.5D;
    private double gain = 1.0D;
    private double decay = 1.4D;
    private double bufferLeak = 0.4D;
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
    private long punishmentCooldownMs = 60_000L;
    private boolean punishmentSkipWhileRecording = true;
    private boolean punishmentStrip = false;
    private int punishmentBanAfter = 0;
    private String punishmentBanCommand = "";
    private String punishmentAnimation = "lightning";

    private int progressSeconds = 15;
    private int monitorPvpSeconds = 5;
    private boolean monitorEnabled = false;
    private String actionBarFormat = "";
    private int hologramLines = 6;
    private int hologramSeconds = 30;
    private double hologramOffset = 1.1D;

    private int historyKeep = 2000;
    private int historyFlushSeconds = 30;
    private int historyMaxPlayers = 5000;
    private int historyRetentionDays = 30;

    private boolean testServerEnabled = false;
    private String testServerWorld = "oculusai_test";
    private int testServerScoreboardTicks = 100;
    private int testServerModelRefreshSeconds = 30;

    private boolean analysisEnabled = true;
    private boolean checksEnabled = true;
    private boolean checkTestMode = true;
    private long checkAlertCooldownMs = 5000L;
    private int checkConfirmations = 2;
    private boolean wallCheckEnabled = true;
    private double wallCheckMaxDistance = 6.0D;
    private long checkWallCooldownMs = 5000L;
    private boolean checkWallTestOnly = false;
    private boolean hitboxCheckEnabled = true;
    private double hitboxMaxDistance = 6.0D;
    private double hitboxMargin = 0.1D;
    private double hitboxMaxAngle = 25.0D;
    private boolean hitboxDebug = false;
    private long checkHitboxCooldownMs = 5000L;
    private boolean checkHitboxTestOnly = false;
    private boolean checkAimEnabled = true;
    private int checkAimSmoothWindows = 3;
    private int checkAimConfirmations = 1;
    private long checkAimCooldownMs = 10000L;
    private boolean checkAimTestOnly = false;
    private boolean checkAimResetAfterCombat = true;

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
        apiUrl = sanitizeUrl(url == null || url.isBlank() ? site : url);
        apiTimeoutMs = Math.max(250, Math.min(60_000,
                config.getInt("api.timeout-ms", 1500)));
        apiKey = config.getString("api.key", "");
        datasetKey = config.getString("api.dataset-key", "");

        analysisEnabled = config.getBoolean("analysis.enabled", true);
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
        // Нулём выключается только повтор: о новой версии всё равно сообщается
        // один раз за проверку.
        updateRemindSeconds = Math.max(0, config.getInt("update.remind-seconds", 15));

        suspicion = clamp(config.getDouble("buffer.suspicion", 0.5D), 0.0D, 1.0D);
        gain = Math.max(0.01D, config.getDouble("buffer.gain", 1.0D));
        decay = Math.max(0.01D, config.getDouble("buffer.decay", 1.4D));
        bufferLeak = clamp(config.getDouble("buffer.leak", 0.4D), 0.0D, 0.95D);
        bufferMax = Math.max(0.1D, config.getDouble("buffer.max", 10.0D));
        alertAt = Math.max(0.0D, config.getDouble("buffer.alert-at", 1.7D));
        punishAt = Math.max(alertAt, config.getDouble("buffer.punish-at", 5.0D));
        alertCooldownMs = Math.max(0L, config.getLong("buffer.alert-cooldown-ms", 10000L));
        alertSound = config.getString("buffer.alert-sound", "BLOCK_NOTE_BLOCK_BELL");
        alertSoundVolume = (float) config.getDouble("buffer.alert-sound-volume", 1.0D);
        alertSoundPitch = (float) config.getDouble("buffer.alert-sound-pitch", 1.2D);
        alertSymbol = config.getString("display.alert-symbol", "⚠");

        punishmentEnabled = config.getBoolean("punishment.enabled", false);
        punishmentCommand = config.getString("punishment.command", "");
        punishmentBroadcast = config.getString("punishment.broadcast", "");
        punishmentCooldownMs = Math.max(0L,
                config.getLong("punishment.cooldown-ms", 60_000L));
        punishmentSkipWhileRecording =
                config.getBoolean("punishment.skip-while-recording", true);
        punishmentStrip = config.getBoolean("punishment.strip", false);
        punishmentBanAfter = Math.max(0, config.getInt("punishment.ban-after", 0));
        punishmentBanCommand = config.getString("punishment.ban-command", "");
        punishmentAnimation = Animations.normalise(config.getString("punishment.animation", "lightning"));

        progressSeconds = config.getInt("recording.progress-seconds", 15);
        monitorPvpSeconds = Math.max(1, config.getInt("monitor.pvp-seconds", 5));
        monitorEnabled = config.getBoolean("monitor.enabled", false);
        actionBarFormat = config.getString("display.actionbar", "");
        hologramLines = config.getInt("display.hologram-lines", 6);
        hologramSeconds = config.getInt("display.hologram-seconds", 30);
        hologramOffset = config.getDouble("display.hologram-offset", 1.1D);

        historyKeep = Math.max(1, config.getInt("history.keep", 2000));
        historyFlushSeconds = Math.max(1, config.getInt("history.flush-seconds", 30));
        historyMaxPlayers = Math.max(0, config.getInt("history.max-players", 5000));
        historyRetentionDays = Math.max(0, config.getInt("history.retention-days", 30));

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
        hitboxCheckEnabled = config.getBoolean("checks.hitbox.enabled", true);
        hitboxMaxDistance = Math.max(0.5D,
                config.getDouble("checks.hitbox.max-distance", 6.0D));
        hitboxMargin = Math.max(0.0D, Math.min(0.5D,
                config.getDouble("checks.hitbox.hitbox-margin", 0.1D)));
        // The attack packet has no rotation of its own, so a legitimate flick
        // can leave the server one look behind the client. Keep this small:
        // it is a timing tolerance, not a replacement for aiming at the box.
        hitboxMaxAngle = Math.max(0.0D, Math.min(45.0D,
                config.getDouble("checks.hitbox.max-angle", 25.0D)));
        hitboxDebug = config.getBoolean("checks.hitbox.debug", false);
        checkHitboxCooldownMs = Math.max(0L, config.getLong(
                "checks.hitbox.cooldown-ms", checkAlertCooldownMs));
        checkHitboxTestOnly = config.getBoolean("checks.hitbox.test-only", false);
        checkAimEnabled = config.getBoolean("checks.aim.enabled", true);
        // The old key was global even though only the aim check used it. Keep
        // reading it as a fallback so an existing config.yml keeps working.
        checkAimSmoothWindows = Math.max(1,
                config.getInt("checks.aim.smooth-windows", smoothWindows));
        checkAimConfirmations = Math.max(1, config.getInt("checks.aim.confirmations", 1));
        checkAimCooldownMs = Math.max(0L, config.getLong(
                "checks.aim.cooldown-ms", alertCooldownMs));
        checkAimTestOnly = config.getBoolean("checks.aim.test-only", false);
        checkAimResetAfterCombat = config.getBoolean("checks.aim.reset-after-combat", true);
    }

    private String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** A malformed URL in config must not throw from every request later. */
    private String sanitizeUrl(String value) {
        String candidate = stripTrailingSlash(value == null ? "" : value.trim());
        try {
            java.net.URI uri = java.net.URI.create(candidate);
            String scheme = uri.getScheme();
            if (uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return candidate;
            }
        } catch (IllegalArgumentException ignored) {
        }
        plugin.getLogger().warning("api.url is not a valid http(s) URL; using "
                + "https://oculusai.space");
        return "https://oculusai.space";
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
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

    public int updateRemindSeconds() {
        return updateRemindSeconds;
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

    public double bufferLeak() {
        return bufferLeak;
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

    public long punishmentCooldownMs() {
        return punishmentCooldownMs;
    }

    /** Whether a player being recorded into a dataset is exempt from the
     *  punishment. Analysis, readings and alerts are unaffected. */
    public boolean punishmentSkipWhileRecording() {
        return punishmentSkipWhileRecording;
    }

    /** What an empty {@code punishment.command} does: a plain kick, or drop
     *  the inventory on the ground, kill, then kick. Ignored when a command
     *  is set - that command is then the whole punishment. */
    public boolean punishmentStrip() {
        return punishmentStrip;
    }

    /** How many punishments a player may collect before the next one bans
     *  them instead. 0 disables banning entirely. */
    public int punishmentBanAfter() {
        return punishmentBanAfter;
    }

    /** The command that bans. Empty means Minecraft's own {@code /ban}. */
    public String punishmentBanCommand() {
        return punishmentBanCommand;
    }

    /** The send-off played when the plugin punishes on its own. One of
     *  {@link Animations#ALL}. */
    public String punishmentAnimation() {
        return punishmentAnimation;
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

    /** Whether /oai monitor watches everybody after a restart. */
    public boolean monitorEnabled() {
        return monitorEnabled;
    }

    public int historyKeep() {
        return historyKeep;
    }

    public int historyFlushSeconds() {
        return historyFlushSeconds;
    }

    public int historyMaxPlayers() {
        return historyMaxPlayers;
    }

    public int historyRetentionDays() {
        return historyRetentionDays;
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

    public boolean analysisEnabled() {
        return analysisEnabled;
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

    public boolean hitboxCheckEnabled() {
        return hitboxCheckEnabled;
    }

    public double hitboxMaxDistance() {
        return hitboxMaxDistance;
    }

    public double hitboxMargin() {
        return hitboxMargin;
    }

    public double hitboxMaxAngle() {
        return hitboxMaxAngle;
    }

    public boolean hitboxDebug() {
        return hitboxDebug;
    }

    public long checkHitboxCooldownMs() {
        return checkHitboxCooldownMs;
    }

    public boolean checkHitboxTestOnly() {
        return checkHitboxTestOnly;
    }

    public boolean checkAimEnabled() {
        return checkAimEnabled;
    }

    public int checkAimSmoothWindows() {
        return checkAimSmoothWindows;
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

    public boolean checkAimResetAfterCombat() {
        return checkAimResetAfterCombat;
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
