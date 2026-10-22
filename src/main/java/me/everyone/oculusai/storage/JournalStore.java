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
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class JournalStore implements Service {

    private record StaffState(GameMode gameMode, Location location) {
    }

    public record Snapshot(long capturedAt, double probability, double buffer) {
    }

    public static final double MODERATION_BUFFER = 2.0D;

    /** Сколько последних показаний держать при записи. Столько же, сколько
     *  показывает меню журнала: хранить больше незачем, меньше - значит
     *  терять график после перезапуска. */
    private static final int HISTORY_KEEP = 8;

    /** Как часто журнал пишется на диск. Падение сервера теряет не больше
     *  этого, а запись мелкая - несколько десятков строк. */
    private static final long FLUSH_SECONDS = 30L;

    public static final class Entry {
        private final UUID uuid;
        private volatile String name;
        private final long firstSeenAt;
        private volatile long lastSeenAt;
        private volatile double peakBuffer;
        private volatile double lastBuffer;
        private volatile double lastProbability;
        private volatile int windowsAnalysed;
        private final Deque<Snapshot> history = new ArrayDeque<>();

        /** Запись, прочитанная с диска. Игрока может не быть на сервере
         *  вовсе - журнал переживает и перезапуск, и его выход. */
        private Entry(UUID uuid, String name, long firstSeenAt, long lastSeenAt,
                      double peakBuffer, double lastBuffer, double lastProbability,
                      int windowsAnalysed, List<Snapshot> snapshots) {
            this.uuid = uuid;
            this.name = name;
            this.firstSeenAt = firstSeenAt;
            this.lastSeenAt = lastSeenAt;
            this.peakBuffer = peakBuffer;
            this.lastBuffer = lastBuffer;
            this.lastProbability = lastProbability;
            this.windowsAnalysed = windowsAnalysed;
            history.addAll(snapshots);
            while (history.size() > HISTORY_KEEP) {
                history.removeFirst();
            }
        }

        private Entry(PlayerData data) {
            long now = System.currentTimeMillis();
            this.uuid = data.uuid();
            this.name = data.name();
            this.firstSeenAt = now;
            this.lastSeenAt = now;
            this.peakBuffer = data.buffer();
            this.lastBuffer = data.buffer();
            this.lastProbability = data.probability();
            this.windowsAnalysed = data.windowsAnalysed();
            history.addLast(new Snapshot(now, data.probability(), data.buffer()));
        }

        private synchronized void update(PlayerData data) {
            long now = System.currentTimeMillis();
            name = data.name();
            lastSeenAt = now;
            lastBuffer = data.buffer();
            lastProbability = data.probability();
            windowsAnalysed = data.windowsAnalysed();
            if (data.buffer() > peakBuffer) {
                peakBuffer = data.buffer();
            }
            history.addLast(new Snapshot(now, data.probability(), data.buffer()));
            while (history.size() > HISTORY_KEEP) {
                history.removeFirst();
            }
        }

        public UUID uuid() {
            return uuid;
        }

        public String name() {
            return name;
        }

        public long firstSeenAt() {
            return firstSeenAt;
        }

        public long lastSeenAt() {
            return lastSeenAt;
        }

        public double peakBuffer() {
            return peakBuffer;
        }

        public double lastBuffer() {
            return lastBuffer;
        }

        public double lastProbability() {
            return lastProbability;
        }

        public int windowsAnalysed() {
            return windowsAnalysed;
        }

        public synchronized List<Snapshot> history() {
            return List.copyOf(history);
        }
    }

    private final OculusAI plugin;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, StaffState> vanished = new ConcurrentHashMap<>();
    /** Incremented on every mutation. The writer records the value it encoded
     *  and only clears the "clean up to here" marker if nothing changed while
     *  it was writing; otherwise the next pass writes the newer state too. */
    private final java.util.concurrent.atomic.AtomicLong changes =
            new java.util.concurrent.atomic.AtomicLong();
    private long written;
    private ScheduledFuture<?> flushTask;

    public JournalStore(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        read();
        long period = FLUSH_SECONDS * 1000L;
        flushTask = plugin.worker().repeat(this::write, period, period, TimeUnit.MILLISECONDS);
    }

    public void record(PlayerData data) {
        if (data.buffer() < MODERATION_BUFFER) {
            return;
        }
        entries.compute(data.uuid(), (uuid, existing) -> {
            if (existing == null) {
                return new Entry(data);
            }
            existing.update(data);
            return existing;
        });
        changes.incrementAndGet();
    }

    /** Убирает игрока из журнала - то, что делает /oai forget. */
    public void forget(UUID uuid) {
        if (entries.remove(uuid) != null) {
            changes.incrementAndGet();
            plugin.worker().execute(this::write);
        }
    }

    // -- сохранение -------------------------------------------------------
    //
    // Журнал это список тех, за кем стоит присмотреть, и перезапуск сервера
    // не повод присматривать заново: раньше список жил только в памяти, и
    // /stop стирал его целиком вместе с тем, кого набрали за вечер.

    private Path file() {
        return plugin.getDataFolder().toPath().resolve("journal.json");
    }

    /** Журнал в JSON. Отдельно от записи на диск, потому что формат - это то,
     *  что должно проверяться тестом, а файловая система в тесте не нужна. */
    static JsonObject encode(Collection<Entry> entries) {
        JsonArray rows = new JsonArray();
        for (Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", entry.uuid().toString());
            item.addProperty("name", entry.name() == null ? "" : entry.name());
            item.addProperty("first_seen_at", entry.firstSeenAt());
            item.addProperty("last_seen_at", entry.lastSeenAt());
            item.addProperty("peak_buffer", entry.peakBuffer());
            item.addProperty("last_buffer", entry.lastBuffer());
            item.addProperty("last_probability", entry.lastProbability());
            item.addProperty("windows", entry.windowsAnalysed());
            JsonArray history = new JsonArray();
            for (Snapshot snapshot : entry.history()) {
                JsonObject point = new JsonObject();
                point.addProperty("t", snapshot.capturedAt());
                point.addProperty("p", snapshot.probability());
                point.addProperty("b", snapshot.buffer());
                history.add(point);
            }
            item.add("history", history);
            rows.add(item);
        }
        JsonObject root = new JsonObject();
        root.add("entries", rows);
        return root;
    }

    /** Обратная сторона {@link #encode}. Пропускает запись, которую не смогла
     *  прочитать: одна битая строка не повод потерять весь журнал. */
    static Map<UUID, Entry> decode(JsonObject root) {
        Map<UUID, Entry> out = new LinkedHashMap<>();
        if (root == null || !root.has("entries")) {
            return out;
        }
        for (var element : root.getAsJsonArray("entries")) {
            try {
                JsonObject item = element.getAsJsonObject();
                UUID uuid = UUID.fromString(item.get("uuid").getAsString());
                List<Snapshot> history = new ArrayList<>();
                if (item.has("history")) {
                    for (var point : item.getAsJsonArray("history")) {
                        JsonObject snapshot = point.getAsJsonObject();
                        history.add(new Snapshot(snapshot.get("t").getAsLong(),
                                snapshot.get("p").getAsDouble(),
                                snapshot.get("b").getAsDouble()));
                    }
                }
                out.put(uuid, new Entry(uuid,
                        item.has("name") ? item.get("name").getAsString() : uuid.toString(),
                        item.get("first_seen_at").getAsLong(),
                        item.get("last_seen_at").getAsLong(),
                        item.get("peak_buffer").getAsDouble(),
                        item.has("last_buffer") ? item.get("last_buffer").getAsDouble() : 0.0D,
                        item.has("last_probability")
                                ? item.get("last_probability").getAsDouble() : 0.0D,
                        item.has("windows") ? item.get("windows").getAsInt() : 0,
                        history));
            } catch (RuntimeException ignored) {
                // Следующая запись может быть целой.
            }
        }
        return out;
    }

    private synchronized void write() {
        long snapshot = changes.get();
        if (snapshot == written) {
            return;
        }
        JsonObject root = encode(entries.values());

        Path path = file();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(root.toString());
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            written = snapshot;
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not write the journal", failure);
        }
    }

    private void read() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            entries.putAll(decode(new JsonParser().parse(reader).getAsJsonObject()));
        } catch (IOException | RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read the journal; starting an empty one", failure);
        }
    }

    public List<Entry> entries() {
        List<Entry> ordered = new ArrayList<>(entries.values());
        ordered.sort(Comparator.comparingLong(Entry::lastSeenAt).reversed()
                .thenComparing(Comparator.comparingDouble(Entry::peakBuffer).reversed()));
        return ordered;
    }

    public int size() {
        return entries.size();
    }

    /** Puts a watcher into spectator mode on a suspect.
     *
     *  @return false if this server cannot do it, so the caller can say so
     *          instead of reporting a spectate that never started. */
    public boolean observe(Player watcher, UUID targetId) {
        PlayerData data = plugin.data().get(targetId);
        if (data == null || !data.player().isOnline()) {
            return false;
        }
        // Spectating reads the target's position and teleports the watcher to
        // it. On Folia those are two entities two region threads may own, and
        // the read happens on the watcher's thread, so it is not ours to make.
        if (plugin.platform().folia()) {
            return false;
        }
        enterVanish(watcher);
        Player target = data.player();
        watcher.setGameMode(GameMode.SPECTATOR);
        watcher.teleport(target.getLocation());
        watcher.setSpectatorTarget(target);
        plugin.displays().watch(watcher, data);
        return true;
    }

    public boolean enterVanish(Player watcher) {
        if (vanished.containsKey(watcher.getUniqueId())) {
            return false;
        }
        vanished.put(watcher.getUniqueId(),
                new StaffState(watcher.getGameMode(), watcher.getLocation().clone()));
        hideFromOthers(watcher);
        watcher.setGameMode(GameMode.SPECTATOR);
        plugin.displays().stop(watcher);
        return true;
    }

    public boolean leaveVanish(Player watcher) {
        StaffState state = vanished.remove(watcher.getUniqueId());
        if (state == null) {
            return false;
        }
        if (watcher.getGameMode() == GameMode.SPECTATOR) {
            watcher.setSpectatorTarget(null);
        }
        watcher.teleport(state.location());
        watcher.setGameMode(state.gameMode());
        showToOthers(watcher);
        plugin.displays().stop(watcher);
        return true;
    }

    public boolean isVanished(UUID uuid) {
        return vanished.containsKey(uuid);
    }

    public void refreshFor(Player viewer) {
        for (UUID vanishedId : vanished.keySet()) {
            Player hidden = plugin.getServer().getPlayer(vanishedId);
            if (hidden != null && !Objects.equals(hidden.getUniqueId(), viewer.getUniqueId())) {
                viewer.hidePlayer(plugin, hidden);
            }
        }
    }

    @Override
    public void disable() {
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        // The worker is still alive; shutdown waits for it, so the final
        // journal write does not have to block the server thread.
        plugin.worker().execute(this::write);
        List<UUID> active = new ArrayList<>(vanished.keySet());
        for (UUID uuid : active) {
            Player watcher = plugin.getServer().getPlayer(uuid);
            if (watcher != null) {
                leaveVanish(watcher);
            }
        }
    }

    public void forgetWatcher(UUID uuid) {
        vanished.remove(uuid);
    }

    private void hideFromOthers(Player watcher) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getUniqueId().equals(watcher.getUniqueId())) {
                continue;
            }
            online.hidePlayer(plugin, watcher);
        }
    }

    private void showToOthers(Player watcher) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getUniqueId().equals(watcher.getUniqueId())) {
                continue;
            }
            online.showPlayer(plugin, watcher);
        }
    }
}
