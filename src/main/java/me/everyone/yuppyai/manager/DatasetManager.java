package me.everyone.yuppyai.manager;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.analysis.FeatureSchemas;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

public final class DatasetManager implements Manager {

    public static final String CHEATER = "cheater";
    public static final String LEGIT = "legit";

    public static final int SCHEMA_V2 = FeatureSchemas.V2;
    public static final int SCHEMA_V3 = FeatureSchemas.V3;
    public static final int SCHEMA_V4 = FeatureSchemas.V4;
    public static final int SCHEMA_V5 = FeatureSchemas.V5;
    public static final int SCHEMA_BOTH = 0;

    public static final int FEATURE_VERSION = SCHEMA_V5;

    private static final int[] BOTH_SCHEMAS = {SCHEMA_V4, SCHEMA_V5};

    public record RosterEntry(UUID uuid, String name, String label, String person) {
    }

    private final YuppyAI plugin;
    private final Map<UUID, RosterEntry> roster = new ConcurrentHashMap<>();
    private volatile boolean collecting;
    private volatile long collectingSinceMs;
    private volatile CommandSender starter;
    private volatile int schema = FEATURE_VERSION;
    private BukkitTask progressTask;

    public DatasetManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        stopProgress();
        roster.clear();
        collecting = false;
        starter = null;
    }

    public static boolean isLabel(String value) {
        return CHEATER.equalsIgnoreCase(value) || LEGIT.equalsIgnoreCase(value);
    }

    public void enrol(PlayerData data, String label) {
        roster.put(data.uuid(), new RosterEntry(data.uuid(), data.name(), label,
                data.uuid().toString()));
        if (collecting) {
            data.running().reset();
            data.recording(true, label);
        }
    }

    public boolean unenrol(UUID uuid) {
        RosterEntry removed = roster.remove(uuid);
        PlayerData data = plugin.data().get(uuid);
        if (data != null) {
            data.recording(false, null);
            data.clearRecorded();
        }
        return removed != null;
    }

    public Collection<RosterEntry> roster() {
        return List.copyOf(roster.values());
    }

    public RosterEntry entry(UUID uuid) {
        return roster.get(uuid);
    }

    public boolean collecting() {
        return collecting;
    }

    public long collectingSinceMs() {
        return collectingSinceMs;
    }

    public int totalRecorded() {
        int total = 0;
        for (RosterEntry entry : roster.values()) {
            PlayerData data = plugin.data().get(entry.uuid());
            if (data != null) {
                total += data.recordedCount();
            }
        }
        return total;
    }

    public int start(CommandSender starter) {
        collecting = true;
        collectingSinceMs = System.currentTimeMillis();
        this.starter = starter;
        int started = 0;
        for (RosterEntry entry : roster.values()) {
            PlayerData data = plugin.data().get(entry.uuid());
            if (data != null && data.player().isOnline()) {
                data.running().reset();
                data.recording(true, entry.label());
                started++;
            }
        }
        startProgress();
        return started;
    }

    private void startProgress() {
        stopProgress();
        int seconds = plugin.config().progressSeconds();
        if (seconds <= 0 || starter == null) {
            return;
        }
        progressTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!collecting) {
                return;
            }
            StringBuilder line = new StringBuilder("<gray>Recording:");
            int total = 0;
            for (RosterEntry entry : roster.values()) {
                PlayerData data = plugin.data().get(entry.uuid());
                int captured = data == null ? 0 : data.recordedCount();
                total += captured;
                line.append(" <white>").append(entry.name())
                        .append(" <").append(captured > 0 ? "green" : "red").append(">")
                        .append(captured).append("<dark_gray>,");
            }
            if (line.charAt(line.length() - 1) == ',') {
                line.setLength(line.length() - 1);
            }
            if (total == 0) {
                line.append(" <dark_gray>— windows are only taken while they fight");
            }
            starter.sendMessage(Msg.parse(plugin.config().prefix() + line));
        }, seconds * 20L, seconds * 20L);
    }

    private void stopProgress() {
        if (progressTask != null) {
            progressTask.cancel();
            progressTask = null;
        }
    }

    public void setSchema(int schema) {
        this.schema = schema == SCHEMA_BOTH || FeatureSchemas.known(schema)
                ? schema : FEATURE_VERSION;
    }

    public int schema() {
        return schema;
    }

    public static String schemaName(int schema) {
        return switch (schema) {
            case SCHEMA_V2 -> "v2";
            case SCHEMA_V3 -> "v3";
            case SCHEMA_V4 -> "v4";
            case SCHEMA_BOTH -> "v4 + v5";
            default -> "v5";
        };
    }

    public static int parseSchema(String value) {
        if (value == null) {
            return -1;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "v2" -> SCHEMA_V2;
            case "v3" -> SCHEMA_V3;
            case "v4" -> SCHEMA_V4;
            case "v5" -> SCHEMA_V5;
            case "both" -> SCHEMA_BOTH;
            default -> -1;
        };
    }

    public Map<String, CompletableFuture<Integer>> stop() {
        collecting = false;
        stopProgress();
        starter = null;
        Map<String, CompletableFuture<Integer>> results = new LinkedHashMap<>();

        for (RosterEntry entry : roster.values()) {
            PlayerData data = plugin.data().get(entry.uuid());
            if (data == null) {
                continue;
            }
            data.recording(false, null);
            if (data.recordedCount() == 0) {
                continue;
            }
            try {
                results.put(entry.name(), commitSchema(data, entry.label(), entry.person()));
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not send the capture for " + entry.name()
                        + "; it is still held and /yai data stop can be run again", failure);
                results.put(entry.name(), CompletableFuture.completedFuture(null));
            }
        }
        return results;
    }

    public CompletableFuture<Integer> commit(PlayerData data, String label) {
        return commit(data, label, true, "manual", personOf(data));
    }

    public CompletableFuture<Integer> commit(PlayerData data, String label,
                                             boolean enabled, String source) {
        return commit(data, label, enabled, source, personOf(data));
    }

    private String personOf(PlayerData data) {
        RosterEntry entry = roster.get(data.uuid());
        return entry == null ? data.uuid().toString() : entry.person();
    }

    public CompletableFuture<Integer> commit(PlayerData data, String label,
                                             boolean enabled, String source, String person) {
        int version = schema == SCHEMA_BOTH ? FEATURE_VERSION : schema;
        List<double[]> rows = data.recordedSamples();
        List<String> names = data.recordedFeatureNames();
        if (rows.isEmpty() || names.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        return plugin.api()
                .addSamples(label, data.name(), data.uuid().toString(),
                        FeatureSchemas.names(version), sliced(names, rows, version),
                        enabled, source, person, data.recordedContext(), version)
                .thenApply(response -> {
                    if (response == null) {
                        return null;
                    }
                    data.clearRecorded();
                    return response.has("stored") ? response.get("stored").getAsInt() : rows.size();
                });
    }

    private CompletableFuture<Integer> commitSchema(PlayerData data, String label, String person) {
        if (schema != SCHEMA_BOTH) {
            return commit(data, label, true, "manual", person);
        }

        List<double[]> rows = data.recordedSamples();
        List<String> names = data.recordedFeatureNames();
        List<double[]> context = data.recordedContext();
        data.clearRecorded();
        if (rows.isEmpty() || names.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        List<CompletableFuture<Integer>> sends = new ArrayList<>(BOTH_SCHEMAS.length);
        for (int version : BOTH_SCHEMAS) {
            sends.add(plugin.api()
                    .addSamples(label, data.name(), data.uuid().toString(),
                            FeatureSchemas.names(version), sliced(names, rows, version),
                            true, "manual", person, context, version)
                    .thenApply(response -> response == null
                            ? null
                            : (response.has("stored") ? response.get("stored").getAsInt() : rows.size())));
        }

        return CompletableFuture.allOf(sends.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    int total = 0;
                    for (CompletableFuture<Integer> send : sends) {
                        Integer stored = send.join();
                        if (stored != null) {
                            total += stored;
                        }
                    }
                    return total;
                });
    }

    private static List<double[]> sliced(List<String> names, List<double[]> rows, int version) {
        List<String> wanted = FeatureSchemas.names(version);
        int[] positions = new int[wanted.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = names.indexOf(wanted.get(i));
        }

        List<double[]> cut = new ArrayList<>(rows.size());
        for (double[] row : rows) {
            double[] values = new double[positions.length];
            for (int i = 0; i < positions.length; i++) {
                int at = positions[i];
                values[i] = at >= 0 && at < row.length ? row[at] : 0.0D;
            }
            cut.add(values);
        }
        return cut;
    }

    public CompletableFuture<Integer> remove(String uuid, String label) {
        return plugin.api().removeSamples(label, uuid).thenApply(response ->
                response == null || !response.has("removed") ? null : response.get("removed").getAsInt());
    }

    public CompletableFuture<JsonObject> train() {
        return plugin.api().train();
    }

    public CompletableFuture<JsonObject> status() {
        return plugin.api().status();
    }
}
