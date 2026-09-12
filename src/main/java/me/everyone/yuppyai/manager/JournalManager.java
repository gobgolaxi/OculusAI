package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

public final class JournalManager implements Manager {

    private record StaffState(GameMode gameMode, Location location) {
    }

    public record Snapshot(long capturedAt, double probability, double buffer) {
    }

    public static final double MODERATION_BUFFER = 2.0D;

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

        private void update(PlayerData data) {
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
            while (history.size() > 8) {
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

        public List<Snapshot> history() {
            return List.copyOf(history);
        }
    }

    private final YuppyAI plugin;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, StaffState> vanished = new ConcurrentHashMap<>();

    public JournalManager(YuppyAI plugin) {
        this.plugin = plugin;
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

    public void observe(Player watcher, UUID targetId) {
        PlayerData data = plugin.data().get(targetId);
        if (data == null || !data.player().isOnline()) {
            return;
        }
        enterVanish(watcher);
        Player target = data.player();
        watcher.setGameMode(org.bukkit.GameMode.SPECTATOR);
        watcher.teleport(target.getLocation());
        watcher.setSpectatorTarget(target);
        plugin.displays().watch(watcher, data);
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
