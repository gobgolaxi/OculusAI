package me.everyone.yuppyai.manager;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.logging.Level;

public final class DisplayManager implements Manager {
    private static final int DISPLAY_UPDATE_TICKS = 2;
    private static final int REMOUNT_TICKS = 20;
    private static final int ALWAYS_PROB_SYNC_TICKS = 10;

    private record Watch(UUID target, long expiresAt, List<Line> holograms, boolean compact, boolean permanent) {
    }

    private record Line(int entityId, UUID uuid) {
    }

    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_000_000);

    private static final int IDX_TRANSLATION;
    private static final int IDX_BILLBOARD;
    private static final int IDX_TEXT;
    private static final int IDX_LINE_WIDTH;
    private static final int IDX_BACKGROUND;
    private static final int IDX_TEXT_OPACITY;
    private static final int IDX_STYLE_FLAGS;

    private static final int UNRESOLVED = -1;

    private static final WrappedDataWatcher.Serializer TRANSLATION_SERIALIZER;

    static {
        Class<?> displayClass = null;
        Class<?> textDisplayClass = null;
        try {
            displayClass = Class.forName("net.minecraft.world.entity.Display");
            textDisplayClass = Class.forName("net.minecraft.world.entity.Display$TextDisplay");
        } catch (ReflectiveOperationException | LinkageError e) {
            java.util.logging.Logger.getLogger("YuppyAI").log(Level.SEVERE,
                    "Could not load the server's Display/TextDisplay NMS classes; holograms are disabled on this server build.", e);
        }
        dumpAccessors("Display", displayClass);
        dumpAccessors("Display.TextDisplay", textDisplayClass);
        IDX_TRANSLATION = accessorIdByFragment(displayClass, "TRANSLATION");
        IDX_BILLBOARD = accessorId(displayClass, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID");
        IDX_TEXT = accessorId(textDisplayClass, "DATA_TEXT_ID");
        IDX_LINE_WIDTH = accessorId(textDisplayClass, "DATA_LINE_WIDTH_ID");
        IDX_BACKGROUND = accessorId(textDisplayClass, "DATA_BACKGROUND_COLOR_ID");
        IDX_TEXT_OPACITY = accessorId(textDisplayClass, "DATA_TEXT_OPACITY_ID");
        IDX_STYLE_FLAGS = accessorId(textDisplayClass, "DATA_STYLE_FLAGS_ID");
        TRANSLATION_SERIALIZER = resolveTranslationSerializer();
    }

    private static WrappedDataWatcher.Serializer resolveTranslationSerializer() {
        for (String className : new String[]{"org.joml.Vector3fc", "org.joml.Vector3f"}) {
            try {
                WrappedDataWatcher.Serializer serializer = WrappedDataWatcher.Registry.get(Class.forName(className));
                if (serializer != null) {
                    return serializer;
                }
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            }
        }
        java.util.logging.Logger.getLogger("YuppyAI").log(Level.SEVERE,
                "ProtocolLib has no registered serializer for org.joml.Vector3f/Vector3fc on this server build; "
                        + "hologram lines will stack without vertical offset instead of crashing the client.");
        return null;
    }

    private static final String ACCESSOR_CLASS_NAME = "net.minecraft.network.syncher.EntityDataAccessor";

    private static void dumpAccessors(String label, Class<?> owner) {
        if (owner == null) {
            return;
        }
        StringBuilder builder = new StringBuilder("YuppyAI: " + label + " EntityDataAccessor fields on this server build:");
        for (Field field : owner.getDeclaredFields()) {
            if (!ACCESSOR_CLASS_NAME.equals(field.getType().getName())) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object accessor = field.get(null);
                int id = accessorObjectId(accessor);
                builder.append("\n  [").append(id).append("] ").append(field.getName())
                        .append(" -> ").append(field.getGenericType());
            } catch (ReflectiveOperationException e) {
                builder.append("\n  ").append(field.getName()).append(" -> <unreadable: ").append(e).append(">");
            }
        }
        java.util.logging.Logger.getLogger("YuppyAI").log(Level.INFO, builder.toString());
    }

    private static int accessorId(Class<?> owner, String fieldName) {
        if (owner == null) {
            return UNRESOLVED;
        }
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return accessorObjectId(field.get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            java.util.logging.Logger.getLogger("YuppyAI").log(Level.SEVERE,
                    "Could not resolve metadata index for " + owner.getSimpleName() + "." + fieldName
                            + " on this server build; that hologram field will be omitted instead of using a guessed index.", e);
            return UNRESOLVED;
        }
    }

    private static int accessorIdByFragment(Class<?> owner, String nameFragment) {
        if (owner == null) {
            return UNRESOLVED;
        }
        String needle = nameFragment.toUpperCase(java.util.Locale.ROOT);
        Field match = null;
        List<String> candidates = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) {
            if (!ACCESSOR_CLASS_NAME.equals(field.getType().getName())) {
                continue;
            }
            if (field.getName().toUpperCase(java.util.Locale.ROOT).contains(needle)) {
                candidates.add(field.getName());
                match = field;
            }
        }
        if (candidates.size() != 1) {
            java.util.logging.Logger.getLogger("YuppyAI").log(Level.SEVERE,
                    "Expected exactly one EntityDataAccessor field on " + owner.getSimpleName()
                            + " containing \"" + nameFragment + "\", found " + candidates
                            + "; that hologram field will be omitted instead of using an ambiguous index.");
            return UNRESOLVED;
        }
        try {
            match.setAccessible(true);
            return accessorObjectId(match.get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            java.util.logging.Logger.getLogger("YuppyAI").log(Level.SEVERE,
                    "Could not read resolved field " + owner.getSimpleName() + "." + match.getName()
                            + " on this server build; that hologram field will be omitted instead of using a guessed index.", e);
            return UNRESOLVED;
        }
    }

    private static int accessorObjectId(Object accessor) throws ReflectiveOperationException {
        try {
            return (int) accessor.getClass().getMethod("id").invoke(accessor);
        } catch (NoSuchMethodException ignored) {
            return (int) accessor.getClass().getMethod("getId").invoke(accessor);
        }
    }

    private static final byte BILLBOARD_CENTER = 3;

    private static final double PASSENGER_ATTACH_HEIGHT = 1.8D;

    private final YuppyAI plugin;
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private final Map<UUID, Map<UUID, Watch>> watching = new ConcurrentHashMap<>();
    private final Set<UUID> alwaysProbViewers = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    private int ticks;
    private int remountTicks;

    public DisplayManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        ticks = 0;
        remountTicks = 0;
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
        remountTicks += DISPLAY_UPDATE_TICKS;
        boolean remount = remountTicks >= REMOUNT_TICKS;
        if (remount) {
            remountTicks = 0;
        }
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
                updateMetadata(watcher, target, watch);
                if (remount) {
                    send(watcher, mountPacket(target.player().getEntityId(), watch.holograms()));
                }
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

    private List<Line> createLines(int count) {
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(new Line(NEXT_ENTITY_ID.getAndIncrement(), UUID.randomUUID()));
        }
        return lines;
    }

    private void spawnHolograms(Player watcher, PlayerData target, Watch watch) {
        List<String> lines = hologramLines(target, watch.compact());
        Location base = target.player().getLocation();
        int count = watch.holograms().size();
        for (int i = 0; i < count; i++) {
            Line line = watch.holograms().get(i);
            send(watcher, spawnPacket(line.entityId(), line.uuid(), base));
            send(watcher, metadataPacket(line.entityId(), i < lines.size() ? lines.get(i) : "",
                    lineTranslationY(i, count)));
        }
        send(watcher, mountPacket(target.player().getEntityId(), watch.holograms()));
    }

    private void updateMetadata(Player watcher, PlayerData target, Watch watch) {
        List<String> lines = hologramLines(target, watch.compact());
        int count = watch.holograms().size();
        for (int i = 0; i < count; i++) {
            Line line = watch.holograms().get(i);
            send(watcher, metadataPacket(line.entityId(), i < lines.size() ? lines.get(i) : "",
                    lineTranslationY(i, count)));
        }
    }

    private float lineTranslationY(int index, int lineCount) {
        double top = Math.max(2.25D, plugin.probConfig().hologramOffset() + 1.15D);
        double step = 0.28D;
        double y = top - PASSENGER_ATTACH_HEIGHT + (lineCount - index - 1) * step;
        return (float) y;
    }

    private PacketContainer mountPacket(int vehicleEntityId, List<Line> holograms) {
        int[] ids = holograms.stream().mapToInt(Line::entityId).toArray();
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.MOUNT, true);
        packet.getIntegers().write(0, vehicleEntityId);
        packet.getIntegerArrays().writeSafely(0, ids);
        if (packet.getIntegerArrays().readSafely(0) == null) {
            packet.getIntLists().writeSafely(0, Arrays.stream(ids).boxed().toList());
        }
        return packet;
    }

    private PacketContainer spawnPacket(int entityId, UUID uuid, org.bukkit.Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY, true);
        packet.getIntegers().write(0, entityId);
        packet.getUUIDs().write(0, uuid);
        packet.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
        packet.getDoubles().write(0, location.getX());
        packet.getDoubles().write(1, location.getY());
        packet.getDoubles().write(2, location.getZ());
        return packet;
    }

    private PacketContainer metadataPacket(int entityId, String line, float translationY) {
        List<WrappedDataValue> metadata = new ArrayList<>(5);
        if (IDX_TRANSLATION != UNRESOLVED && TRANSLATION_SERIALIZER != null) {
            metadata.add(new WrappedDataValue(IDX_TRANSLATION,
                    TRANSLATION_SERIALIZER, new org.joml.Vector3f(0.0F, translationY, 0.0F)));
        }
        if (IDX_BILLBOARD != UNRESOLVED) {
            metadata.add(new WrappedDataValue(IDX_BILLBOARD,
                    WrappedDataWatcher.Registry.get(Byte.class), BILLBOARD_CENTER));
        }
        if (IDX_TEXT != UNRESOLVED) {
            metadata.add(new WrappedDataValue(IDX_TEXT,
                    WrappedDataWatcher.Registry.getChatComponentSerializer(false),
                    chatComponent(line).getHandle()));
        }
        if (IDX_BACKGROUND != UNRESOLVED) {
            metadata.add(new WrappedDataValue(IDX_BACKGROUND,
                    WrappedDataWatcher.Registry.get(Integer.class), 0x40000000));
        }
        if (IDX_TEXT_OPACITY != UNRESOLVED) {
            metadata.add(new WrappedDataValue(IDX_TEXT_OPACITY,
                    WrappedDataWatcher.Registry.get(Byte.class), (byte) -1));
        }

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA, true);
        packet.getIntegers().write(0, entityId);
        packet.getDataValueCollectionModifier().write(0, metadata);
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
            packet.getIntegerArrays().writeSafely(0, ids);
            if (packet.getIntegerArrays().readSafely(0) == null) {
                packet.getIntLists().writeSafely(0, Arrays.stream(ids).boxed().toList());
            }
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
