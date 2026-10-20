package me.everyone.oculusai.storage;


import me.everyone.oculusai.core.Service;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;

public final class HistoryStore implements Service {

    public record Event(long at, String type, String detail) {
    }

    /** Сколько раз с игроком что-то случилось за всё время.
     *
     *  Считается отдельно от ленты событий, а не выводится из неё: лента
     *  обрезана последними шестьюдесятью записями, и игрок, которого
     *  предупреждали триста раз, по ней выглядел бы как предупреждённый
     *  шестьдесят. Счётчик не обнуляется ничем, кроме /oai forget. */
    public record Tally(int alerts, int punishments, int quits, double peakProbability) {
    }

    private static final class Record {
        private volatile String name;
        private volatile long firstSeenAt;
        private volatile long lastSeenAt;
        private volatile double peakBuffer;
        private volatile double peakProbability;
        private volatile int windowsAnalysed;
        private volatile int alerts;
        private volatile int punishments;
        private volatile int quits;
        private volatile long lastAlertAt;
        private final Deque<PlayerData.Reading> readings = new ArrayDeque<>();
        private final Deque<Event> events = new ArrayDeque<>();
        private volatile boolean dirty;
        private long version;

        private Snapshot snapshot() {
            synchronized (this) {
                return new Snapshot(name, firstSeenAt, lastSeenAt, peakBuffer,
                        peakProbability, windowsAnalysed, alerts, punishments, quits,
                        lastAlertAt, List.copyOf(readings), List.copyOf(events), version);
            }
        }

        private void changed() {
            version++;
            dirty = true;
        }

        private void written(long snapshotVersion) {
            synchronized (this) {
                if (version == snapshotVersion) {
                    dirty = false;
                }
            }
        }
    }

    private record Snapshot(String name, long firstSeenAt, long lastSeenAt,
                            double peakBuffer, double peakProbability, int windowsAnalysed,
                            int alerts, int punishments, int quits, long lastAlertAt,
                            List<PlayerData.Reading> readings, List<Event> events,
                            long version) {
    }

    private final OculusAI plugin;
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSeen = new ConcurrentHashMap<>();
    private ScheduledFuture<?> flushTask;

    public HistoryStore(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        Path directory = directory();
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not create the history directory", failure);
            return;
        }
        loadIndex();

        // Retention is a directory scan; keep it off the enable path.
        plugin.worker().execute(this::purgeOldFiles);

        long period = Math.max(20L, plugin.config().historyFlushSeconds() * 20L) * 50L;
        flushTask = plugin.worker().repeat(this::flushAll, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void disable() {
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        // The worker is still alive here (it is shut down after every service),
        // and shutdown waits for it. Writing the last dirty records there keeps
        // the server's stop from doing file I/O on the tick thread.
        plugin.worker().execute(() -> {
            flushAll();
            records.clear();
            byName.clear();
            names.clear();
            lastSeen.clear();
        });
    }

    public void record(PlayerData data) {
        PlayerData.Reading newest = data.latestReading();
        if (newest == null) {
            return;
        }
        Record record = load(data.uuid(), data.name());
        synchronized (record) {
            if (!record.readings.isEmpty()
                    && record.readings.peekLast().capturedAt() == newest.capturedAt()
                    && record.readings.peekLast().buffer() == newest.buffer()) {
                return;
            }
            record.name = data.name();
            record.lastSeenAt = newest.capturedAt();
            record.windowsAnalysed = Math.max(record.windowsAnalysed, data.windowsAnalysed());
            record.peakBuffer = Math.max(record.peakBuffer, newest.buffer());
            record.peakProbability = Math.max(record.peakProbability, newest.probability());
            record.readings.addLast(newest);
            trim(record);
            record.changed();
        }
        remember(data.uuid(), data.name(), newest.capturedAt());
    }

    public void note(UUID uuid, String name, String type, String detail) {
        Record record = load(uuid, name);
        long now = System.currentTimeMillis();
        synchronized (record) {
            record.name = name == null || name.isBlank() ? record.name : name;
            record.lastSeenAt = Math.max(record.lastSeenAt, now);
            switch (type) {
                case "alert" -> {
                    record.alerts++;
                    record.lastAlertAt = now;
                }
                case "punish" -> record.punishments++;
                case "quit", "kick" -> record.quits++;
                default -> {
                    if (type.startsWith("check-")) {
                        record.alerts++;
                        record.lastAlertAt = now;
                    }
                }
            }
            record.events.addLast(new Event(now, type,
                    detail == null ? "" : detail));
            while (record.events.size() > 60) {
                record.events.pollFirst();
            }
            record.changed();
        }
        remember(uuid, name, now);
        // No synchronous flush here: an alert on the main thread must not wait
        // for a JSON write. The worker flushes dirty records every period.
    }

    private void remember(UUID uuid, String name, long seenAt) {
        lastSeen.merge(uuid, seenAt, Math::max);
        if (name == null || name.isBlank()) {
            return;
        }
        names.put(uuid, name);
        byName.put(name.toLowerCase(Locale.ROOT), uuid);
    }

    private void trim(Record record) {
        int keep = plugin.config().historyKeep();
        while (record.readings.size() > keep) {
            record.readings.pollFirst();
        }
    }

    public List<PlayerData.Reading> readings(UUID uuid) {
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return List.of();
            }
            records.put(uuid, record);
        }
        synchronized (record) {
            return List.copyOf(record.readings);
        }
    }

    public List<Event> events(UUID uuid) {
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return List.of();
            }
            records.put(uuid, record);
        }
        synchronized (record) {
            return List.copyOf(record.events);
        }
    }

    public String nameOf(UUID uuid) {
        String cached = names.get(uuid);
        if (cached != null) {
            return cached;
        }
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return null;
            }
            records.put(uuid, record);
        }
        return record.name;
    }

    public double peakBuffer(UUID uuid) {
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return 0.0D;
            }
            records.put(uuid, record);
        }
        return record.peakBuffer;
    }

    /** Счётчики за всё время. Для игрока, о котором нечего сказать, нули. */
    public Tally tally(UUID uuid) {
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return new Tally(0, 0, 0, 0.0D);
            }
            records.put(uuid, record);
        }
        return new Tally(record.alerts, record.punishments, record.quits,
                record.peakProbability);
    }

    public long lastAlertAt(UUID uuid) {
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return 0L;
            }
            records.put(uuid, record);
        }
        return record.lastAlertAt;
    }

    public long firstSeenAt(UUID uuid) {
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return 0L;
            }
            records.put(uuid, record);
        }
        return record.firstSeenAt;
    }

    public long lastSeenAt(UUID uuid) {
        Long cached = lastSeen.get(uuid);
        if (cached != null) {
            return cached;
        }
        Record record = records.get(uuid);
        return record == null ? 0L : record.lastSeenAt;
    }

    public int windowsAnalysed(UUID uuid) {
        Record record = records.get(uuid);
        if (record == null) {
            record = read(uuid);
            if (record == null) {
                return 0;
            }
            records.put(uuid, record);
        }
        return record.windowsAnalysed;
    }

    public boolean has(UUID uuid) {
        return records.containsKey(uuid) || Files.exists(fileOf(uuid));
    }

    public UUID resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public List<UUID> tracked() {
        Set<UUID> unique = new LinkedHashSet<>(byName.values());
        List<UUID> all = new ArrayList<>(unique);
        all.sort(Comparator.comparingLong(
                id -> lastSeen.getOrDefault(id, 0L)).reversed());
        return all;
    }

    public void forget(UUID uuid) {
        records.remove(uuid);
        byName.entrySet().removeIf(entry -> entry.getValue().equals(uuid));
        names.remove(uuid);
        lastSeen.remove(uuid);
        try {
            Files.deleteIfExists(fileOf(uuid));
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not delete the history of " + uuid, failure);
        }
        plugin.worker().execute(this::writeIndex);
    }

    private Path directory() {
        return plugin.getDataFolder().toPath().resolve("history");
    }

    private Path fileOf(UUID uuid) {
        return directory().resolve(uuid + ".json");
    }

    private Record load(UUID uuid, String name) {
        Record loaded = records.computeIfAbsent(uuid, id -> {
            Record stored = read(id);
            if (stored != null) {
                return stored;
            }
            Record fresh = new Record();
            fresh.name = name;
            fresh.firstSeenAt = System.currentTimeMillis();
            fresh.lastSeenAt = fresh.firstSeenAt;
            return fresh;
        });
        if (name != null && !name.isBlank()) {
            names.putIfAbsent(uuid, name);
        }
        return loaded;
    }

    private Record read(UUID uuid) {
        Path path = fileOf(uuid);
        if (!Files.exists(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root =
                new JsonParser().parse(reader).getAsJsonObject();
            Record record = new Record();
            record.name = root.has("name") ? root.get("name").getAsString() : uuid.toString();
            record.firstSeenAt = root.has("first_seen_at") ? root.get("first_seen_at").getAsLong() : 0L;
            record.lastSeenAt = root.has("last_seen_at") ? root.get("last_seen_at").getAsLong() : 0L;
            record.peakBuffer = root.has("peak_buffer") ? root.get("peak_buffer").getAsDouble() : 0.0D;
            record.peakProbability = root.has("peak_probability")
                    ? root.get("peak_probability").getAsDouble() : 0.0D;
            record.windowsAnalysed = root.has("windows") ? root.get("windows").getAsInt() : 0;
            // Файл, записанный до появления счётчиков, их не содержит: ноль -
            // честный ответ, выводить их задним числом из обрезанной ленты
            // значило бы выдумать число и показать его как измеренное.
            record.alerts = root.has("alerts") ? root.get("alerts").getAsInt() : 0;
            record.punishments = root.has("punishments") ? root.get("punishments").getAsInt() : 0;
            record.quits = root.has("quits") ? root.get("quits").getAsInt() : 0;
            record.lastAlertAt = root.has("last_alert_at") ? root.get("last_alert_at").getAsLong() : 0L;
            if (root.has("readings")) {
                for (var element : root.getAsJsonArray("readings")) {
                    JsonObject item = element.getAsJsonObject();
                    record.readings.addLast(new PlayerData.Reading(
                            item.get("t").getAsLong(),
                            item.get("p").getAsDouble(),
                            item.get("b").getAsDouble()));
                }
            }
            if (root.has("events")) {
                for (var element : root.getAsJsonArray("events")) {
                    JsonObject item = element.getAsJsonObject();
                    record.events.addLast(new Event(
                            item.get("t").getAsLong(),
                            item.get("type").getAsString(),
                            item.has("detail") ? item.get("detail").getAsString() : ""));
                }
            }
            trim(record);
            names.put(uuid, record.name);
            lastSeen.merge(uuid, record.lastSeenAt, Math::max);
            return record;
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read the history of " + uuid + "; starting a new one", failure);
            return null;
        }
    }

    /** Queues a single record for the worker. Kept public because history
     *  mutations outside this class (none today) must not write on the caller. */
    public void flush(UUID uuid) {
        plugin.worker().execute(() -> {
            Record record = records.get(uuid);
            if (record != null) {
                writeIfDirty(uuid, record);
            }
        });
    }

    private void flushAll() {
        for (Map.Entry<UUID, Record> entry : records.entrySet()) {
            writeIfDirty(entry.getKey(), entry.getValue());
        }
        writeIndex();
        pruneMemory();
    }

    private void writeIfDirty(UUID uuid, Record record) {
        Snapshot snapshot = record.snapshot();
        if (!record.dirty) {
            return;
        }
        JsonObject root = new JsonObject();
        JsonArray readings = new JsonArray();
        JsonArray events = new JsonArray();
        root.addProperty("name", snapshot.name() == null ? uuid.toString() : snapshot.name());
        root.addProperty("first_seen_at", snapshot.firstSeenAt());
        root.addProperty("last_seen_at", snapshot.lastSeenAt());
        root.addProperty("peak_buffer", snapshot.peakBuffer());
        root.addProperty("peak_probability", snapshot.peakProbability());
        root.addProperty("windows", snapshot.windowsAnalysed());
        root.addProperty("alerts", snapshot.alerts());
        root.addProperty("punishments", snapshot.punishments());
        root.addProperty("quits", snapshot.quits());
        root.addProperty("last_alert_at", snapshot.lastAlertAt());
        for (PlayerData.Reading reading : snapshot.readings()) {
            JsonObject item = new JsonObject();
            item.addProperty("t", reading.capturedAt());
            item.addProperty("p", reading.probability());
            item.addProperty("b", reading.buffer());
            readings.add(item);
        }
        for (Event event : snapshot.events()) {
            JsonObject item = new JsonObject();
            item.addProperty("t", event.at());
            item.addProperty("type", event.type());
            item.addProperty("detail", event.detail());
            events.add(item);
        }
        root.add("readings", readings);
        root.add("events", events);

        Path path = fileOf(uuid);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            record.written(snapshot.version());
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not write the history of " + uuid, failure);
        }
    }

    /** Keeps the in-memory cache bounded. Only clean records are dropped: a
     *  dirty one is due to be written by the same pass, so dropping it here
     *  would lose data. A dropped record is re-read from disk next time. */
    private void pruneMemory() {
        int max = plugin.config().historyMaxPlayers();
        if (max <= 0 || records.size() <= max) {
            return;
        }
        List<Map.Entry<UUID, Record>> ordered = new ArrayList<>(records.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().lastSeenAt));
        for (Map.Entry<UUID, Record> entry : ordered) {
            if (records.size() <= max) {
                break;
            }
            if (!entry.getValue().dirty) {
                records.remove(entry.getKey());
            }
        }
    }

    /** Deletes history files older than the configured retention. Runs on the
     *  worker; records still loaded are left alone so an online player's
     *  in-memory data cannot be deleted from under them. */
    private void purgeOldFiles() {
        int days = plugin.config().historyRetentionDays();
        if (days <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
        try (Stream<Path> files = Files.list(directory())) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("index.json"))
                    .forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toMillis() >= cutoff) {
                                return;
                            }
                            String stem = path.getFileName().toString().replace(".json", "");
                            UUID uuid = UUID.fromString(stem);
                            if (records.containsKey(uuid)) {
                                return;
                            }
                            byName.entrySet().removeIf(entry -> entry.getValue().equals(uuid));
                            names.remove(uuid);
                            lastSeen.remove(uuid);
                            Files.deleteIfExists(path);
                        } catch (IOException | IllegalArgumentException ignored) {
                            // One unreadable name must not stop the sweep.
                        }
                    });
            writeIndex();
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not prune old history files", failure);
        }
    }

    private Path indexFile() {
        return directory().resolve("index.json");
    }

    private void writeIndex() {
        JsonObject root = new JsonObject();
        byName.forEach((name, uuid) -> {
            JsonObject row = new JsonObject();
            row.addProperty("uuid", uuid.toString());
            row.addProperty("name", names.getOrDefault(uuid, name));
            row.addProperty("last_seen_at", lastSeen.getOrDefault(uuid, 0L));
            root.add(name, row);
        });
        Path path = indexFile();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not write the history index", failure);
        }
    }

    private void loadIndex() {
        Path path = indexFile();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject root =
                new JsonParser().parse(reader).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                    String key = entry.getKey();
                    com.google.gson.JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive()) {
                        UUID uuid = UUID.fromString(value.getAsString());
                        byName.put(key, uuid);
                        names.putIfAbsent(uuid, key);
                        continue;
                    }
                    if (!value.isJsonObject()) {
                        continue;
                    }
                    JsonObject row = value.getAsJsonObject();
                    UUID uuid = UUID.fromString(row.get("uuid").getAsString());
                    byName.put(key, uuid);
                    if (row.has("name")) {
                        names.put(uuid, row.get("name").getAsString());
                    }
                    if (row.has("last_seen_at")) {
                        lastSeen.merge(uuid, row.get("last_seen_at").getAsLong(), Math::max);
                    }
                }
                return;
            } catch (IOException | RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not read the history index; rebuilding it from the files", failure);
            }
        }
        rebuildIndex();
    }

    private void rebuildIndex() {
        try (Stream<Path> files = Files.list(directory())) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("index.json"))
                    .forEach(path -> {
                        String stem = path.getFileName().toString().replace(".json", "");
                        try {
                            UUID uuid = UUID.fromString(stem);
                            Record record = read(uuid);
                            if (record != null && record.name != null) {
                                byName.put(record.name.toLowerCase(Locale.ROOT), uuid);
                                names.put(uuid, record.name);
                                lastSeen.merge(uuid, record.lastSeenAt, Math::max);
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not list stored histories", failure);
        }
        writeIndex();
    }
}
