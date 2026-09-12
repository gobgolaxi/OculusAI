package me.everyone.yuppyai.manager;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedWatchableObject;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.lang.reflect.Type;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

public final class DisplayManager implements Manager {
    private static final int DISPLAY_UPDATE_TICKS = 2;
    private static final int ALWAYS_PROB_SYNC_TICKS = 10;

    private record Watch(UUID target, long expiresAt, List<Line> holograms, boolean compact, boolean permanent) {
    }

    private record Line(int entityId, UUID uuid) {
    }

    private static final byte ENTITY_FLAG_INVISIBLE = 0x20;
    private static final byte ARMOR_STAND_FLAG_SMALL = 0x01;
    private static final byte ARMOR_STAND_FLAG_MARKER = 0x10;
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_000_000);

    private final YuppyAI plugin;
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private final Map<UUID, Map<UUID, Watch>> watching = new ConcurrentHashMap<>();
    private final Set<UUID> alwaysProbViewers = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    private int ticks;

    public DisplayManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        ticks = 0;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, DISPLAY_UPDATE_TICKS);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Map.Entry<UUID, Map<UUID, Watch>> entry : watching.entrySet()) {
            Player watcher = plugin.getServer().getPlayer(entry.getKey());
            if (watcher != null) {
                for (Watch watch : entry.getValue().values()) {
                    destroyHolograms(watcher, watch);
                }
            }
        }
        watching.clear();
        alwaysProbViewers.clear();
    }

    public void watch(Player watcher, PlayerData target) {
        watch(watcher, target, false, false);
    }

    public void enableAlwaysProb(Player viewer) {
        if (!viewer.hasPermission("yuppyai.alwaysprob")) {
            return;
        }
        alwaysProbViewers.add(viewer.getUniqueId());
        syncAlwaysProb(viewer);
    }

    public void disableAlwaysProb(Player viewer) {
        alwaysProbViewers.remove(viewer.getUniqueId());
        Map<UUID, Watch> watches = watching.get(viewer.getUniqueId());
        if (watches == null) {
            return;
        }
        watches.entrySet().removeIf(entry -> {
            Watch watch = entry.getValue();
            if (!watch.permanent()) {
                return false;
            }
            destroyHolograms(viewer, watch);
            return true;
        });
        if (watches.isEmpty()) {
            watching.remove(viewer.getUniqueId());
        }
    }

    public boolean isAlwaysProb(UUID viewer) {
        return alwaysProbViewers.contains(viewer);
    }

    private void watch(Player watcher, PlayerData target, boolean compact, boolean permanent) {
        Map<UUID, Watch> watches = watching.computeIfAbsent(watcher.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        Watch previous = watches.get(target.uuid());
        if (previous != null && previous.compact() == compact && previous.permanent() == permanent) {
            return;
        }
        previous = watches.remove(target.uuid());
        if (previous != null) {
            destroyHolograms(watcher, previous);
        }
        List<Line> holograms = createLines(compact ? 1 : Math.max(1, plugin.probConfig().hologramLines()));
        Watch watch = new Watch(
                target.uuid(),
                permanent ? Long.MAX_VALUE : System.currentTimeMillis() + plugin.probConfig().hologramSeconds() * 1000L,
                holograms,
                compact,
                permanent);
        watches.put(target.uuid(), watch);
        spawnHolograms(watcher, target, watch);
    }

    public void stop(Player watcher) {
        Map<UUID, Watch> removed = watching.remove(watcher.getUniqueId());
        if (removed != null) {
            for (Watch watch : removed.values()) {
                destroyHolograms(watcher, watch);
            }
        }
    }

    public boolean isWatching(UUID watcher) {
        Map<UUID, Watch> watches = watching.get(watcher);
        return watches != null && !watches.isEmpty();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        ticks += DISPLAY_UPDATE_TICKS;
        if (ticks >= ALWAYS_PROB_SYNC_TICKS) {
            ticks = 0;
            syncAlwaysProb();
        }
        watching.entrySet().removeIf(entry -> {
            Player watcher = plugin.getServer().getPlayer(entry.getKey());
            Map<UUID, Watch> watches = entry.getValue();
            if (watcher == null || !watcher.isOnline()) {
                for (Watch watch : watches.values()) {
                    destroyHolograms(watcher, watch);
                }
                return true;
            }

            watches.entrySet().removeIf(watchEntry -> {
                Watch watch = watchEntry.getValue();
                PlayerData target = plugin.data().get(watch.target());
                if (target == null
                        || !target.player().isOnline()
                        || !canKeepWatch(watcher, target.player(), watch.permanent())
                        || (!watch.permanent() && now > watch.expiresAt())) {
                    destroyHolograms(watcher, watch);
                    return true;
                }
                updateHolograms(watcher, target, watch);
                return false;
            });

            if (watches.isEmpty()) {
                watcher.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(""));
                return true;
            }

            watcher.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(actionBar(watches)));
            return false;
        });
    }

    private String actionBar(Map<UUID, Watch> watches) {
        List<Watch> temporary = new ArrayList<>();
        for (Watch watch : watches.values()) {
            if (!watch.permanent()) {
                temporary.add(watch);
            }
        }
        if (temporary.isEmpty()) {
            return "";
        }
        if (temporary.size() == 1) {
            Watch watch = temporary.get(0);
            PlayerData target = plugin.data().get(watch.target());
            return target == null ? "" : (watch.compact() ? compactActionBarForTarget(target) : actionBarForTarget(target));
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (Watch watch : temporary) {
            PlayerData target = plugin.data().get(watch.target());
            if (target == null) {
                continue;
            }
            if (!first) {
                builder.append("<dark_gray> | ");
            }
            builder.append("<white>").append(target.name()).append("<gray>: ");
            builder.append(Msg.bar(target.buffer() / plugin.config().bufferMax(), 8));
            first = false;
        }
        return Msg.parse(builder.toString());
    }

    private String actionBarForTarget(PlayerData target) {
        double fraction = target.buffer() / plugin.config().bufferMax();
        return Msg.parse(plugin.probConfig().actionBarFormat(), placeholders(target, fraction, 10, null));
    }

    private String compactActionBarForTarget(PlayerData target) {
        double fraction = target.buffer() / plugin.config().bufferMax();
        return Msg.parse("<%accent%>⚠ <white>%bar% <dark_gray>| <white>%player% <dark_gray>| <gray>%probability%",
                placeholders(target, fraction, 10, Map.of("trend", compactHistory(target.bufferHistory()))));
    }

    private List<Line> createLines() {
        return createLines(Math.max(1, plugin.probConfig().hologramLines()));
    }

    private List<Line> createLines(int count) {
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(new Line(NEXT_ENTITY_ID.getAndIncrement(), UUID.randomUUID()));
        }
        return lines;
    }

    private void spawnHolograms(Player watcher, PlayerData target, Watch watch) {
        List<String> lines = hologramLines(target, watch.compact());
        for (int i = 0; i < watch.holograms().size(); i++) {
            Line line = watch.holograms().get(i);
            send(watcher, spawnPacket(line.entityId(), line.uuid(), packetLocation(target, i, lines.size())));
            send(watcher, metadataPacket(line.entityId(), i < lines.size() ? lines.get(i) : ""));
        }
    }

    private void updateHolograms(Player watcher, PlayerData target, Watch watch) {
        List<String> lines = hologramLines(target, watch.compact());
        for (int i = 0; i < watch.holograms().size(); i++) {
            Line line = watch.holograms().get(i);
            send(watcher, teleportPacket(line.entityId(), packetLocation(target, i, lines.size())));
            send(watcher, metadataPacket(line.entityId(), i < lines.size() ? lines.get(i) : ""));
        }
    }

    private Location packetLocation(PlayerData target, int index, int lineCount) {
        org.bukkit.Location base = target.player().getLocation();
        double top = Math.max(2.25D, plugin.probConfig().hologramOffset() + 1.15D);
        double step = 0.28D;
        double y = base.getY() + top + (lineCount - index - 1) * step;
        return new Location(base.getWorld(), base.getX(), y, base.getZ(), 0.0F, 0.0F);
    }

    private PacketContainer spawnPacket(int entityId, UUID uuid, org.bukkit.Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY, true);
        packet.getIntegers().write(0, entityId);
        packet.getUUIDs().write(0, uuid);
        packet.getEntityTypeModifier().write(0, EntityType.ARMOR_STAND);
        packet.getDoubles().write(0, location.getX());
        packet.getDoubles().write(1, location.getY());
        packet.getDoubles().write(2, location.getZ());
        return packet;
    }

    private PacketContainer teleportPacket(int entityId, org.bukkit.Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT, true);
        packet.getIntegers().write(0, entityId);
        packet.getDoubles().write(0, location.getX());
        packet.getDoubles().write(1, location.getY());
        packet.getDoubles().write(2, location.getZ());
        packet.getBytes().write(0, (byte) 0);
        packet.getBytes().write(1, (byte) 0);
        packet.getBooleans().write(0, false);
        return packet;
    }

    private PacketContainer metadataPacket(int entityId, String line) {
        List<WrappedWatchableObject> metadata = new ArrayList<>(5);
        metadata.add(new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(
                0, WrappedDataWatcher.Registry.get((Type) Byte.class)), ENTITY_FLAG_INVISIBLE));
        metadata.add(new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(
                2, WrappedDataWatcher.Registry.getChatComponentSerializer(true)),
                Optional.of(chatComponent(line))));
        metadata.add(new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(
                3, WrappedDataWatcher.Registry.get((Type) Boolean.class)), true));
        metadata.add(new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(
                5, WrappedDataWatcher.Registry.get((Type) Boolean.class)), true));
        metadata.add(new WrappedWatchableObject(new WrappedDataWatcher.WrappedDataWatcherObject(
                14, WrappedDataWatcher.Registry.get((Type) Byte.class)),
                (byte) (ARMOR_STAND_FLAG_SMALL | ARMOR_STAND_FLAG_MARKER)));

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA, true);
        packet.getIntegers().write(0, entityId);
        packet.getWatchableCollectionModifier().write(0, metadata);
        return packet;
    }

    private WrappedChatComponent chatComponent(String line) {
        WrappedChatComponent[] components = WrappedChatComponent.fromChatMessage(line == null ? "" : line);
        if (components.length == 0) {
            return WrappedChatComponent.fromText("");
        }
        return components[0];
    }

    private List<String> hologramLines(PlayerData target, boolean compact) {
        if (compact) {
            return List.of(compactLine(target));
        }
        double fraction = target.buffer() / plugin.config().bufferMax();
        String trend = historyDigits(target.bufferHistory(), 5, false);
        if (trend.isEmpty()) {
            trend = "-";
        }
        String state = target.windowsAnalysed() == 0 ? "waiting for combat" : "live reading";

        int max = Math.max(1, plugin.probConfig().hologramLines());
        List<String> lines = new ArrayList<>(max);
        for (String format : plugin.probConfig().hologramFormat()) {
            if (lines.size() >= max) {
                break;
            }
            lines.add(Msg.parse(format, placeholders(target, fraction, 12, Map.of(
                    "trend", trend,
                    "windows", Integer.toString(target.windowsAnalysed()),
                    "state", state))));
        }
        while (lines.size() < max) {
            lines.add(Msg.parse("<dark_gray>"));
        }
        return lines;
    }

    private String compactLine(PlayerData target) {
        double fraction = target.buffer() / plugin.config().bufferMax();
        return Msg.parse("<%accent%>⚠ <white>%bar% <dark_gray>| <white>%player% <dark_gray>| <gray>%probability%",
                placeholders(target, fraction, 10, Map.of("trend", compactHistory(target.bufferHistory()))));
    }

    private String historyDigits(List<Double> history) {
        return historyDigits(history, 5, false);
    }

    private String compactHistory(List<Double> history) {
        StringBuilder builder = new StringBuilder(5);
        int start = Math.max(0, history.size() - 5);
        for (int i = start; i < history.size(); i++) {
            int digit = Math.min(9, Math.max(0, (int) Math.round(history.get(i))));
            builder.append(digit);
        }
        while (builder.length() < 5) {
            builder.insert(0, '-');
        }
        if (builder.length() > 5) {
            return builder.substring(builder.length() - 5);
        }
        return builder.toString();
    }

    private String historyDigits(List<Double> history, int count, boolean padded) {
        if (history.size() <= 1) {
            return padded ? "-----" : "";
        }
        int start = Math.max(0, history.size() - (count + 1));
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < history.size() - 1; i++) {
            double value = history.get(i);
            double fraction = value / plugin.config().bufferMax();
            if (builder.length() > 0) {
                builder.append(padded ? "" : "<dark_gray> ");
            }
            builder.append("<").append(historyColour(fraction)).append(">");
            builder.append(Math.min(9, Math.max(0, (int) Math.round(value))));
        }
        if (padded) {
            while (builder.length() < count) {
                builder.append("-");
            }
        }
        return builder.toString();
    }

    private void syncAlwaysProb() {
        for (UUID viewerId : alwaysProbViewers) {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline() || !viewer.hasPermission("yuppyai.alwaysprob")) {
                alwaysProbViewers.remove(viewerId);
                continue;
            }
            syncAlwaysProb(viewer);
        }
    }

    private void syncAlwaysProb(Player viewer) {
        if (viewer == null || !viewer.isOnline() || !viewer.hasPermission("yuppyai.alwaysprob")) {
            return;
        }
        Map<UUID, Watch> watches = watching.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (!isVisibleTo(viewer, target)) {
                continue;
            }
            PlayerData data = plugin.data().get(target);
            if (data != null) {
                watch(viewer, data, true, true);
            }
        }
        watches.entrySet().removeIf(entry -> {
            Watch watch = entry.getValue();
            if (!watch.permanent()) {
                return false;
            }
            Player target = plugin.getServer().getPlayer(entry.getKey());
            if (target != null && isVisibleTo(viewer, target)) {
                return false;
            }
            destroyHolograms(viewer, watch);
            return true;
        });
        if (watches.isEmpty()) {
            watching.remove(viewer.getUniqueId());
        }
    }

    private boolean isVisibleTo(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return false;
        }
        if (!viewer.isOnline() || !target.isOnline()) {
            return false;
        }
        if (viewer.getUniqueId().equals(target.getUniqueId())) {
            return false;
        }
        return viewer.canSee(target);
    }

    private boolean canKeepWatch(Player watcher, Player target, boolean permanent) {
        if (watcher == null || target == null) {
            return false;
        }
        if (!watcher.isOnline() || !target.isOnline()) {
            return false;
        }
        if (watcher.getUniqueId().equals(target.getUniqueId())) {
            return !permanent;
        }
        return watcher.canSee(target);
    }

    private Map<String, String> placeholders(PlayerData target, double fraction, int barWidth,
                                             Map<String, String> extra) {
        var theme = plugin.theme().current();
        Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("bar", Msg.bar(fraction, barWidth));
        placeholders.put("probability", Msg.percent(target.probability()));
        placeholders.put("buffer", Msg.round(target.buffer(), 1));
        placeholders.put("max_buffer", Msg.round(plugin.config().bufferMax(), 0));
        placeholders.put("player", target.name());
        placeholders.put("primary", theme.primary());
        placeholders.put("secondary", theme.secondary());
        placeholders.put("accent", theme.accent());
        placeholders.put("accent_soft", theme.accentSoft());
        if (extra != null) {
            placeholders.putAll(extra);
        }
        return placeholders;
    }

    private String historyColour(double fraction) {
        if (fraction >= 0.66D) {
            return "#ff5a5a";
        }
        if (fraction >= 0.33D) {
            return "#ffd25e";
        }
        return "#67f08a";
    }

    private void destroyHolograms(Player watcher, Watch watch) {
        if (watcher == null) {
            return;
        }
        int[] ids = watch.holograms().stream().mapToInt(Line::entityId).toArray();
        if (ids.length > 0) {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY, true);
            packet.getIntegerArrays().write(0, ids);
            send(watcher, packet);
        }
        watcher.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                net.md_5.bungee.api.chat.TextComponent.fromLegacyText(""));
    }

    private void send(Player watcher, PacketContainer packet) {
        protocolManager.sendServerPacket(watcher, packet);
    }

    public void forget(UUID uuid) {
        Map<UUID, Watch> removed = watching.remove(uuid);
        if (removed != null) {
            Player watcher = plugin.getServer().getPlayer(uuid);
            if (watcher != null) {
                for (Watch watch : removed.values()) {
                    destroyHolograms(watcher, watch);
                }
            }
            return;
        }

        List<UUID> watchers = new ArrayList<>(watching.keySet());
        for (UUID watcherId : watchers) {
            Map<UUID, Watch> watches = watching.get(watcherId);
            if (watches == null) {
                continue;
            }
            Watch watch = watches.remove(uuid);
            if (watch != null) {
                Player watcher = plugin.getServer().getPlayer(watcherId);
                if (watcher != null) {
                    destroyHolograms(watcher, watch);
                }
            }
            if (watches.isEmpty()) {
                watching.remove(watcherId);
            }
        }
    }
}

