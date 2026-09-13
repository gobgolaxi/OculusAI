package me.everyone.yuppyai.manager;

import com.google.gson.JsonObject;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class DatasetManager implements Manager {

    public static final String CHEATER = "cheater";
    public static final String LEGIT = "legit";

    public static final int SCHEMA_V2 = 2;
    public static final int SCHEMA_V3 = 3;
    public static final int SCHEMA_BOTH = 0;

    // The v2 schema is the first V2_WIDTH columns of the v3 vector; the crit
    // features (crit_ratio, crit_position, crit_height_*, crit_aim_error) are
    // appended after them, so slicing to this width drops exactly the v3 bits.
    private static final int V2_WIDTH = 17;

    public record RosterEntry(UUID uuid, String name, String label, String person) {
    }

    private final YuppyAI plugin;
    private final Map<UUID, RosterEntry> roster = new ConcurrentHashMap<>();
    private volatile boolean collecting;
    private volatile long collectingSinceMs;
    private volatile CommandSender starter;
    private volatile int schema = SCHEMA_V3;
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


    public void enrol(PlayerData data, String label, String person) {
        roster.put(data.uuid(), new RosterEntry(data.uuid(), data.name(), label,
                person == null || person.isBlank() ? data.uuid().toString() : person.trim()));
        if (collecting) {
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
                data.recording(true, entry.label());
                started++;
            }
        }
        startProgress();
        return started;
    }

    public void setSchema(int schema) {
        this.schema = schema;
    }

    public int schema() {
        return schema;
    }

    public static boolean isSchema(String value) {
        return "v2".equalsIgnoreCase(value)
                || "v3".equalsIgnoreCase(value)
                || "both".equalsIgnoreCase(value);
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
            int[] versions = schema == SCHEMA_BOTH
                    ? new int[]{SCHEMA_V2, SCHEMA_V3}
                    : new int[]{schema};
            try {
                results.put(entry.name(), commitVersions(data, entry.label(), entry.person(), versions));
            } catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
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
        int version = schema == SCHEMA_BOTH ? SCHEMA_V3 : schema;
        return commit(data, label, enabled, source, person, version);
    }

    public CompletableFuture<Integer> commit(PlayerData data, String label,
                                             boolean enabled, String source, String person,
                                             int featureVersion) {
        List<double[]> rows = data.recordedSamples();
        List<String> names = data.recordedFeatureNames();
        if (rows.isEmpty() || names.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        int width = featureVersion == SCHEMA_V2 ? V2_WIDTH : names.size();
        List<String> slicedNames = names.subList(0, Math.min(width, names.size()));
        List<double[]> slicedRows = new ArrayList<>(rows.size());
        for (double[] row : rows) {
            slicedRows.add(java.util.Arrays.copyOf(row, Math.min(width, row.length)));
        }

        return plugin.api()
                .addSamples(label, data.name(), data.uuid().toString(), slicedNames, slicedRows,
                        enabled, source, person, data.recordedContext(), featureVersion)
                .thenApply(response -> {
                    if (response == null) {
                        return null;
                    }
                    data.clearRecorded();
                    return response.has("stored") ? response.get("stored").getAsInt() : slicedRows.size();
                });
    }

    private CompletableFuture<Integer> commitVersions(PlayerData data, String label,
                                                      String person, int[] versions) {
        // Snapshot once and clear now, so "both" can send two requests off the
        // same rows without the second request seeing an empty buffer.
        List<double[]> rows = data.recordedSamples();
        List<String> names = data.recordedFeatureNames();
        List<double[]> context = data.recordedContext();
        data.clearRecorded();

        if (rows.isEmpty() || names.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        List<CompletableFuture<Integer>> futures = new ArrayList<>(versions.length);
        for (int version : versions) {
            int width = version == SCHEMA_V2 ? V2_WIDTH : names.size();
            List<String> slicedNames = names.subList(0, Math.min(width, names.size()));
            List<double[]> slicedRows = new ArrayList<>(rows.size());
            for (double[] row : rows) {
                slicedRows.add(java.util.Arrays.copyOf(row, Math.min(width, row.length)));
            }
            futures.add(plugin.api()
                    .addSamples(label, data.name(), data.uuid().toString(), slicedNames, slicedRows,
                            true, "manual", person, context, version)
                    .thenApply(response -> response == null
                            ? null
                            : (response.has("stored") ? response.get("stored").getAsInt() : slicedRows.size())));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> {
                    int total = 0;
                    for (CompletableFuture<Integer> future : futures) {
                        Integer stored = future.join();
                        if (stored != null) {
                            total += stored;
                        }
                    }
                    return total;
                });
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
