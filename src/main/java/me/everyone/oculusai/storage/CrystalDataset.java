package me.everyone.oculusai.storage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.detection.crystal.CrystalSchema;
import me.everyone.oculusai.network.UploadQueue;
import me.everyone.oculusai.player.PlayerData;

/** Запись кристалльного датасета (схема 8) - /oai datacrystal.
 *
 * <p>Свой список игроков, отдельный от /oai data: аимовый и кристалльный
 * пулы пишутся независимо и могут писаться одновременно. Окна копит
 * {@link me.everyone.oculusai.detection.crystal.CrystalTimeline} игрока, здесь -
 * только кто записывается и отправка.
 */
public final class CrystalDataset implements Service {

    public record Entry(UUID uuid, String name, String label) {
    }

    private final OculusAI plugin;
    private final Map<UUID, Entry> roster = new ConcurrentHashMap<>();
    private volatile boolean collecting;

    public CrystalDataset(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void disable() {
        // Незакрытая запись не теряется молча: окна остаются у игроков до
        // следующего stop, а при выключении сервера - пропадают, как и
        // аимовые. Здесь просто перестаём копить.
        for (Entry entry : roster.values()) {
            PlayerData data = plugin.data().get(entry.uuid());
            if (data != null) {
                data.crystal().recording(false);
            }
        }
        collecting = false;
    }

    public void enrol(PlayerData data, String label) {
        roster.put(data.uuid(), new Entry(data.uuid(), data.name(), label));
        if (collecting) {
            data.crystal().recording(true);
        }
    }

    public boolean unenrol(UUID uuid) {
        Entry removed = roster.remove(uuid);
        PlayerData data = plugin.data().get(uuid);
        if (data != null) {
            data.crystal().recording(false);
        }
        return removed != null;
    }

    public Entry byName(String name) {
        for (Entry entry : roster.values()) {
            if (entry.name().equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    public Collection<Entry> roster() {
        return roster.values();
    }

    public boolean collecting() {
        return collecting;
    }

    /** Окон записано у этого игрока прямо сейчас; -1 - не в сети. */
    public int recorded(UUID uuid) {
        PlayerData data = plugin.data().get(uuid);
        return data == null ? -1 : data.crystal().recorded();
    }

    /** Начинает запись у всех из списка, кто в сети. */
    public int start() {
        collecting = true;
        int started = 0;
        for (Entry entry : roster.values()) {
            PlayerData data = plugin.data().get(entry.uuid());
            if (data != null) {
                data.crystal().recording(true);
                started++;
            }
        }
        return started;
    }

    /** Сервис ещё не знает схему 8 - отправлять нечего и некуда. */
    public boolean serviceAccepts() {
        Set<Integer> offered = plugin.api().serviceSchemas();
        return offered.isEmpty() || offered.contains(CrystalSchema.VERSION);
    }

    /** Останавливает запись и отправляет окна; имя -> сколько окон сохранено
     *  (null - сервис не ответил, окна ушли в очередь повторов). */
    public Map<String, CompletableFuture<Integer>> stop() {
        collecting = false;
        Map<String, CompletableFuture<Integer>> results = new LinkedHashMap<>();
        for (Entry entry : roster.values()) {
            PlayerData data = plugin.data().get(entry.uuid());
            if (data == null) {
                continue;
            }
            data.crystal().recording(false);
            List<List<double[]>> drained = data.crystal().drain();
            List<double[]> rows = drained.get(0);
            List<double[]> ticks = drained.get(1);
            if (rows.isEmpty()) {
                continue;
            }
            UploadQueue.Batch batch = new UploadQueue.Batch(entry.label(), entry.name(),
                    entry.uuid().toString(), CrystalSchema.COLUMNS, rows, true, "manual",
                    entry.uuid().toString(), List.of(), CrystalSchema.VERSION,
                    ticks, CrystalSchema.TICK_CHANNELS);
            results.put(entry.name(), plugin.api()
                    .addSamples(batch.label(), batch.player(), batch.uuid(),
                            batch.featureNames(), batch.rows(), batch.enabled(),
                            batch.source(), batch.person(), batch.context(),
                            batch.featureVersion(), batch.ticks(), batch.tickChannels())
                    .thenApply(response -> {
                        if (response == null) {
                            plugin.uploads().offer(batch);
                            return null;
                        }
                        return response.has("stored") ? response.get("stored").getAsInt() : rows.size();
                    }));
        }
        return results;
    }
}
