package me.everyone.oculusai.ui;


import me.everyone.oculusai.core.Platform;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.EntityTracker;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

public final class Hud implements Service {
    private static final int DISPLAY_UPDATE_TICKS = 2;
    private static final int REMOUNT_TICKS = 20;
    private static final int ALWAYS_PROB_SYNC_TICKS = 10;
    /** Who had AlwaysProb on, one id per line. A state file rather than
     *  config.yml: saving Bukkit's config on every toggle rewrites the file
     *  and drops its comments, and this is per-player state, not a setting. */
    private static final String ALWAYS_PROB_STATE = "alwaysprob.state";

    private record Watch(UUID target, UUID world, long expiresAt, List<Line> holograms,
                         boolean compact, boolean permanent) {
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
            Logger.getLogger("OculusAI").log(Level.SEVERE,
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
        java.util.logging.Logger.getLogger("OculusAI").log(Level.SEVERE,
                "ProtocolLib has no registered serializer for org.joml.Vector3f/Vector3fc on this server build; "
                        + "hologram lines will stack without vertical offset instead of crashing the client.");
        return null;
    }

    private static final String ACCESSOR_CLASS_NAME = "net.minecraft.network.syncher.EntityDataAccessor";

    private static void dumpAccessors(String label, Class<?> owner) {
        if (owner == null) {
            return;
        }
        StringBuilder builder = new StringBuilder("OculusAI: " + label + " EntityDataAccessor fields on this server build:");
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
        java.util.logging.Logger.getLogger("OculusAI").log(Level.INFO, builder.toString());
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
            java.util.logging.Logger.getLogger("OculusAI").log(Level.SEVERE,
                    "Could not resolve metadata index for " + owner.getSimpleName() + "." + fieldName
                            + " on this server build; that hologram field will be omitted instead of using a guessed index.", e);
            return UNRESOLVED;
        }
    }

    private static int accessorIdByFragment(Class<?> owner, String nameFragment) {
        if (owner == null) {
            return UNRESOLVED;
        }
        String needle = nameFragment.toUpperCase(Locale.ROOT);
        Field match = null;
        List<String> candidates = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) {
            if (!ACCESSOR_CLASS_NAME.equals(field.getType().getName())) {
                continue;
            }
            if (field.getName().toUpperCase(Locale.ROOT).contains(needle)) {
                candidates.add(field.getName());
                match = field;
            }
        }
        if (candidates.size() != 1) {
            java.util.logging.Logger.getLogger("OculusAI").log(Level.SEVERE,
                    "Expected exactly one EntityDataAccessor field on " + owner.getSimpleName()
                            + " containing \"" + nameFragment + "\", found " + candidates
                            + "; that hologram field will be omitted instead of using an ambiguous index.");
            return UNRESOLVED;
        }
        try {
            match.setAccessible(true);
            return accessorObjectId(match.get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            java.util.logging.Logger.getLogger("OculusAI").log(Level.SEVERE,
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

    /** How old a position sample may be before the player it describes is
     *  treated as unlocatable. The tracker samples every tick, so anything
     *  this old means its sampler has stopped - the player is leaving. */
    private static final long SIGHT_MAX_AGE_MS = 1_000L;

    private final OculusAI plugin;
    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private final Map<UUID, Map<UUID, Watch>> watching = new ConcurrentHashMap<>();
    private final Set<UUID> alwaysProbViewers = ConcurrentHashMap.newKeySet();
    /** Folia only: one drawing pass per watcher, on the watcher's own thread.
     *  That is the thread allowed to send them packets and read their world,
     *  and it is where everything a watcher needs now happens. */
    private final Map<UUID, Platform.Cancellable> passes = new ConcurrentHashMap<>();
    /** Folia only: when each watcher's holograms were last re-mounted. A
     *  per-watcher clock, because each pass now ticks on its own thread and
     *  one shared counter would be read and written by all of them. */
    private final Map<UUID, Long> remountedAt = new ConcurrentHashMap<>();
    private TickLoop.Handle task;
    private int ticks;
    private int remountTicks;
    private boolean mountPacketUnsupported;
    private boolean mountFallbackBroken;

    public Hud(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        ticks = 0;
        remountTicks = 0;
        loadAlwaysProb();
        task = plugin.ticks().every(1L, DISPLAY_UPDATE_TICKS, this::tick);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        passes.values().forEach(Platform.Cancellable::cancel);
        passes.clear();
        remountedAt.clear();
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

    /** Reads back who had AlwaysProb on before the last stop.
     *
     *  <p>Without this the feature came back off after every restart, and a
     *  viewer who had turned it on months ago had to remember to turn it on
     *  again - which reads exactly like "it disappeared". Holograms are not
     *  spawned here: nobody is online yet at enable time, and the join hook
     *  and the sync timer put them up. */
    private void loadAlwaysProb() {
        alwaysProbViewers.clear();
        Path state = plugin.getDataFolder().toPath().resolve(ALWAYS_PROB_STATE);
        if (!Files.isRegularFile(state)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(state, StandardCharsets.UTF_8)) {
                String value = line.trim();
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    alwaysProbViewers.add(UUID.fromString(value));
                } catch (IllegalArgumentException notAViewer) {
                    // A hand-edit, or a line from a future format.
                }
            }
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read " + state + "; AlwaysProb starts off", failure);
        }
    }

    private void saveAlwaysProb() {
        Path state = plugin.getDataFolder().toPath().resolve(ALWAYS_PROB_STATE);
        try {
            Files.createDirectories(state.getParent());
            StringBuilder out = new StringBuilder();
            for (UUID viewer : alwaysProbViewers) {
                out.append(viewer).append('\n');
            }
            Files.writeString(state, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not save AlwaysProb to " + state, failure);
        }
    }

    /** Whether this server build can draw the holograms at all.
     *
     *  <p>True here: this source set is the one with Display entities. The
     *  1.16 build answers false, and so did this one on Folia until the
     *  drawing pass stopped reading two players at once. */
    public boolean supportsAlwaysProb() {
        return true;
    }

    public void watch(Player watcher, PlayerData target) {
        // One line unless the operator asked for the tall one. Six lines of
        // hologram over a head in a fight hide the thing being watched, and the
        // two numbers that decide anything - the reading and the buffer - fit
        // on one line.
        watch(watcher, target, plugin.hudConfig().hologramCompact(), false);
    }

    /** Turns AlwaysProb on for one viewer. False when they may not have it,
     *  so the caller can say so instead of the click doing nothing. */
    public boolean enableAlwaysProb(Player viewer) {
        if (!viewer.hasPermission("oculusai.alwaysprob")) {
            return false;
        }
        alwaysProbViewers.add(viewer.getUniqueId());
        saveAlwaysProb();
        syncAlwaysProb(viewer);
        return true;
    }

    public void disableAlwaysProb(Player viewer) {
        if (!alwaysProbViewers.remove(viewer.getUniqueId())) {
            return;
        }
        saveAlwaysProb();
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
        watch(watcher, target, compact, permanent, true);
    }

    private void watch(Player watcher, PlayerData target, boolean compact, boolean permanent,
                       boolean mayWaitForTarget) {
        UUID world = worldOf(watcher);
        UUID targetWorld = worldOf(target.player());
        if (folia() && targetWorld == null && mayWaitForTarget && target.player().isOnline()) {
            // Nobody has sampled this player yet - they joined a moment ago.
            // Take one sample on their own region, which is where that is
            // legal, and come back: the alternative is a command that silently
            // does nothing the one time somebody runs it. Once only, so a
            // player who cannot be sampled at all is dropped rather than
            // chased round the schedulers forever.
            Player subject = target.player();
            plugin.platform().onEntity(subject, () -> {
                Location at = subject.getLocation();
                if (at.getWorld() != null) {
                    plugin.tracker().track(subject.getEntityId(), at.getX(), at.getY(), at.getZ(),
                            subject.getWidth(), subject.getHeight(), at.getWorld());
                }
                plugin.platform().onEntity(watcher,
                        () -> watch(watcher, target, compact, permanent, false));
            });
            return;
        }
        // A packet-created display entity lives in the viewer's current world.
        // Watching a player elsewhere would spawn text at the same coordinates
        // in the wrong world.
        if (world == null || !world.equals(targetWorld)) {
            return;
        }
        Map<UUID, Watch> watches = watching.computeIfAbsent(watcher.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        Watch previous = watches.get(target.uuid());
        if (previous != null && previous.world().equals(world)
                && previous.compact() == compact && previous.permanent() == permanent) {
            return;
        }
        previous = watches.remove(target.uuid());
        if (previous != null) {
            destroyHolograms(watcher, previous);
        }
        List<Line> holograms = createLines(compact ? 1 : Math.max(1, plugin.hudConfig().hologramLines()));
        Watch watch = new Watch(
                target.uuid(),
                world,
                permanent ? Long.MAX_VALUE : System.currentTimeMillis() + plugin.hudConfig().hologramSeconds() * 1000L,
                holograms,
                compact,
                permanent);
        watches.put(target.uuid(), watch);
        spawnHolograms(watcher, target, watch);
        // On Folia the drawing happens on this watcher's own thread, and this
        // is the moment it has something to draw.
        startPass(watcher.getUniqueId());
    }

    public void stop(Player watcher) {
        stopPass(watcher.getUniqueId());
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
        if (folia()) {
            // The drawing itself is not done here. This task runs on the
            // global region, which owns no entity and may not send a packet to
            // one: each watcher is drawn by a task on their own thread, and
            // this only makes sure every watcher has one.
            for (UUID watcher : watching.keySet()) {
                startPass(watcher);
            }
            return;
        }
        watching.entrySet().removeIf(entry -> {
            Player watcher = plugin.getServer().getPlayer(entry.getKey());
            return drawWatcher(watcher, entry.getValue(), now, remount);
        });
    }

    private boolean folia() {
        return plugin.platform().folia();
    }

    /** One watcher's holograms, brought up to date.
     *
     *  <p>Both servers run this; the difference is which thread it is on and
     *  how it got here. On Paper the one global pass calls it for every
     *  watcher in turn. On Folia each watcher has a task on their own thread
     *  that calls it for themselves, because that is the only thread allowed
     *  to read their world and send them packets.
     *
     *  @return true when this watcher is finished with and should be dropped.
     */
    private boolean drawWatcher(Player watcher, Map<UUID, Watch> watches,
                                long now, boolean remount) {
        if (watcher == null || !watcher.isOnline()) {
            for (Watch watch : watches.values()) {
                destroyHolograms(watcher, watch);
            }
            return true;
        }

        UUID world = worldOf(watcher);
        watches.entrySet().removeIf(watchEntry -> {
            Watch watch = watchEntry.getValue();
            PlayerData target = plugin.data().get(watch.target());
            if (target == null
                    || !target.player().isOnline()
                    // The client discards entities when it changes world.
                    // An old watch would keep sending metadata to ids the
                    // client no longer knows, so drop it and let the
                    // AlwaysProb sync spawn a fresh set in the new world.
                    || world == null
                    || !watch.world().equals(world)
                    || !canKeepWatch(watcher, target.player(), watch.permanent())
                    || (!watch.permanent() && now > watch.expiresAt())) {
                destroyHolograms(watcher, watch);
                return true;
            }
            updateMetadata(watcher, target, watch);
            if (remount) {
                sendMount(watcher, target.player(), watch.holograms());
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
    }

    /** Folia only: gives this watcher a drawing pass on their own thread, if
     *  they have not got one. Idempotent - the global tick calls it for every
     *  watcher it finds, and a watch starting calls it directly so the first
     *  holograms do not wait half a second. */
    private void startPass(UUID watcherId) {
        if (!folia() || passes.containsKey(watcherId)) {
            return;
        }
        Player watcher = plugin.getServer().getPlayer(watcherId);
        if (watcher == null || !watcher.isOnline()) {
            return;
        }
        passes.put(watcherId, plugin.platform().entityRepeating(watcher, 1L, DISPLAY_UPDATE_TICKS,
                () -> {
                    Map<UUID, Watch> watches = watching.get(watcherId);
                    Player self = plugin.getServer().getPlayer(watcherId);
                    long now = System.currentTimeMillis();
                    long last = remountedAt.getOrDefault(watcherId, 0L);
                    boolean remount = now - last >= REMOUNT_TICKS * 50L;
                    if (remount) {
                        remountedAt.put(watcherId, now);
                    }
                    if (watches == null || watches.isEmpty()
                            || drawWatcher(self, watches, now, remount)) {
                        watching.remove(watcherId);
                        stopPass(watcherId);
                    }
                }));
    }

    private void stopPass(UUID watcherId) {
        Platform.Cancellable pass = passes.remove(watcherId);
        if (pass != null) {
            pass.cancel();
        }
        remountedAt.remove(watcherId);
    }

    /** Where a player was last sampled, or null if that is too long ago to
     *  use.
     *
     *  <p>From the entity tracker rather than from the player, and that is the
     *  whole Folia fix. A hologram pass reads a watcher and a target, which on
     *  Folia are two entities two threads may own: there is no thread on which
     *  both are legal. The tracker already samples every player once a tick on
     *  the thread that owns them - it has to, the checks are fed from it - so
     *  the pass reads the sample and never reaches across. */
    private EntityTracker.Tracked sightOf(Player player) {
        EntityTracker.Tracked sight = plugin.tracker().get(player.getEntityId());
        return sight == null || System.currentTimeMillis() - sight.at() > SIGHT_MAX_AGE_MS
                ? null : sight;
    }

    /** Which world a player is in, read from wherever that is legal: the
     *  player on Paper, their own region's last note on Folia. Null means
     *  nobody can say right now, which every caller treats as "do not draw". */
    private UUID worldOf(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        if (!folia()) {
            return player.getWorld().getUID();
        }
        EntityTracker.Tracked sight = sightOf(player);
        return sight == null ? null : sight.worldId();
    }

    /** Where to spawn a hologram, read from wherever that is legal. Only has
     *  to be right for one tick: the text is mounted on the player as a
     *  passenger, and from then on their client carries it. */
    private Location positionOf(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }
        if (!folia()) {
            return player.getLocation();
        }
        EntityTracker.Tracked sight = sightOf(player);
        if (sight == null) {
            return null;
        }
        org.bukkit.World world = plugin.getServer().getWorld(sight.worldId());
        return world == null ? null : new Location(world, sight.x(), sight.y(), sight.z());
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
            // With one target the action bar has room for the configured
            // format. With several it used to collapse to a name and a bar,
            // which says that something is happening without saying what -
            // whether a buffer of 4.6 is climbing towards a kick or a reading
            // of 0.9 just arrived. Both numbers fit if the bar goes.
            builder.append(arrow(target)).append(" <white>").append(target.name()).append("<dark_gray>: ");
            if (!target.analysing(plugin.hudConfig().staleMs())) {
                builder.append("&cno analysing");
            } else {
                builder.append("<gray>").append(Msg.probability(shownProbability(target)));
                builder.append("<dark_gray>/<white>").append(Msg.round(target.buffer(), 1));
            }
            first = false;
        }
        return Msg.parse(builder.toString());
    }

    private String actionBarForTarget(PlayerData target) {
        double fraction = target.buffer() / plugin.config().bufferMax();
        if (!target.analysing(plugin.hudConfig().staleMs())) {
            return Msg.parse("%arrow% <white>%player% <dark_gray>| &cno analysing",
                    placeholders(target, fraction, 10, Map.of("trend", "-----")));
        }
        return Msg.parse(plugin.hudConfig().actionBarFormat(), placeholders(target, fraction, 10, null));
    }

    private String compactActionBarForTarget(PlayerData target) {
        double fraction = target.buffer() / plugin.config().bufferMax();
        if (!target.analysing(plugin.hudConfig().staleMs())) {
            return Msg.parse("%arrow% <white>%player% <dark_gray>| &cno analysing",
                    placeholders(target, fraction, 10, Map.of("trend", "-----")));
        }
        return Msg.parse(plugin.hudConfig().actionBarFormat(),
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
        Location base = positionOf(target.player());
        if (base == null) {
            return;
        }
        int count = watch.holograms().size();
        for (int i = 0; i < count; i++) {
            Line line = watch.holograms().get(i);
            send(watcher, spawnPacket(line.entityId(), line.uuid(), base));
            send(watcher, metadataPacket(line.entityId(), i < lines.size() ? lines.get(i) : "",
                    lineTranslationY(i, count)));
        }
        sendMount(watcher, target.player(), watch.holograms());
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
        double top = plugin.hudConfig().hologramHeight();
        double step = 0.28D;
        double y = top - PASSENGER_ATTACH_HEIGHT + (lineCount - index - 1) * step;
        return (float) y;
    }

    private void sendMount(Player watcher, Player target, List<Line> holograms) {
        PacketContainer packet = mountPacket(target, holograms);
        if (packet != null) {
            send(watcher, packet);
        }
    }

    private PacketContainer mountPacket(Player vehicle, List<Line> holograms) {
        int[] ids = holograms.stream().mapToInt(Line::entityId).toArray();
        if (!mountPacketUnsupported) {
            try {
                PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.MOUNT, true);
                packet.getIntegers().write(0, vehicle.getEntityId());
                writePassengers(packet, ids);
                return packet;
            } catch (RuntimeException | LinkageError e) {
                mountPacketUnsupported = true;
                plugin.getLogger().log(Level.WARNING,
                        "ProtocolLib cannot create MOUNT packets on this server build; using a reflective fallback.",
                        e);
            }
        }
        return reflectiveMountPacket(vehicle, ids);
    }

    private PacketContainer reflectiveMountPacket(Player vehicle, int[] ids) {
        if (mountFallbackBroken) {
            return null;
        }
        try {
            Object handle = vehicle.getClass().getMethod("getHandle").invoke(vehicle);
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            Class<?> packetClass = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSetPassengersPacket");
            Object nmsPacket = packetClass.getConstructor(entityClass).newInstance(handle);
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.MOUNT, nmsPacket);
            writePassengers(packet, ids);
            return packet;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            mountFallbackBroken = true;
            plugin.getLogger().log(Level.SEVERE,
                    "Could not build the MOUNT packet reflectively; holograms will not follow moving targets.",
                    e);
            return null;
        }
    }

    private void writePassengers(PacketContainer packet, int[] ids) {
        packet.getIntegerArrays().writeSafely(0, ids);
        if (packet.getIntegerArrays().readSafely(0) == null) {
            packet.getIntLists().writeSafely(0, Arrays.stream(ids).boxed().toList());
        }
    }

    private PacketContainer spawnPacket(int entityId, UUID uuid, Location location) {
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
        String state = target.analysing(plugin.hudConfig().staleMs())
                ? "live reading"
                : (target.windowsAnalysed() == 0 ? "&cno analysing &7(waiting for combat)"
                                                 : "&cno analysing");

        int max = Math.max(1, plugin.hudConfig().hologramLines());
        List<String> lines = new ArrayList<>(max);
        for (String format : plugin.hudConfig().hologramFormat()) {
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
        // A number left over from a minute ago looks exactly like a live one,
        // which is the thing most likely to mislead whoever is watching. When
        // windows stop arriving the line says that instead.
        if (!target.analysing(plugin.hudConfig().staleMs())) {
            return Msg.parse(plugin.hudConfig().staleFormat(),
                    placeholders(target, fraction, 10, Map.of("trend", "-----")));
        }
        return Msg.parse(plugin.hudConfig().hologramCompactFormat(),
                placeholders(target, fraction, 10, Map.of("trend", compactHistory(target.bufferHistory()))));
    }

    /** Which way the buffer is going, as one glyph: up means the last window
     *  pushed it towards a punishment, down means it is draining. Direction is
     *  the thing a watcher cannot get from the number alone - 3.1 on the way
     *  up and 3.1 on the way down are the same number and opposite situations. */
    private String arrow(PlayerData target) {
        List<Double> history = target.bufferHistory();
        if (history.size() < 2) {
            return "<dark_gray>\u25ac";
        }
        double last = history.get(history.size() - 1);
        double previous = history.get(history.size() - 2);
        if (last > previous + 0.01D) {
            return "<red>\u25b2";
        }
        if (last < previous - 0.01D) {
            return "<green>\u25bc";
        }
        return "<dark_gray>\u25ac";
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
            if (viewer == null || !viewer.isOnline()) {
                // Offline is not off. Dropping the viewer here is what used to
                // lose AlwaysProb on every disconnect, including the one-second
                // kind: they came back and it was gone with nothing said.
                continue;
            }
            if (!viewer.hasPermission("oculusai.alwaysprob")) {
                // The permission is actually gone while they are online, which
                // is a decision somebody made - that one does turn it off.
                alwaysProbViewers.remove(viewerId);
                saveAlwaysProb();
                continue;
            }
            // On Folia, on the viewer's own thread - the same thread their
            // drawing pass runs on. Two threads both deciding which holograms
            // this viewer should have would race over one watch list and the
            // packets that follow from it.
            plugin.platform().onEntity(viewer, () -> syncAlwaysProb(viewer));
        }
    }

    /** Rebuilds one viewer's holograms now, instead of up to half a second
     *  later. Called the moment the client's entity store is known to have
     *  been thrown away - a join, a respawn, a world change - because that is
     *  exactly when a viewer sees the numbers vanish and assumes the feature
     *  broke. Safe to call for anybody: it does nothing for a player who does
     *  not have AlwaysProb on. */
    public void resyncAlwaysProb(Player viewer) {
        if (viewer == null || !alwaysProbViewers.contains(viewer.getUniqueId())) {
            return;
        }
        syncAlwaysProb(viewer);
    }

    /** The same for everybody watching. Used when the thing that changed is a
     *  third player - somebody joined, or walked into another world - which
     *  changes what every viewer should have over their head. */
    public void resyncAlwaysProb() {
        syncAlwaysProb();
    }

    private void syncAlwaysProb(Player viewer) {
        if (viewer == null || !viewer.isOnline() || !viewer.hasPermission("oculusai.alwaysprob")) {
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
            if (target != null && watch.world().equals(worldOf(viewer))
                    && isVisibleTo(viewer, target)) {
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
        // A display entity is spawned in the viewer's client world; a target
        // in another world would put a hologram in the wrong place or nowhere
        // at all. World transfers also recreate the client's entity store, so
        // this check is what makes AlwaysProb rebuild itself afterwards.
        UUID world = worldOf(viewer);
        if (world == null || !world.equals(worldOf(target))) {
            return false;
        }
        // The viewer's own hiding list, so it is theirs to read wherever this
        // runs: a vanished player must not collect holograms.
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
        UUID world = worldOf(watcher);
        if (world == null || !world.equals(worldOf(target))) {
            return false;
        }
        return watcher.canSee(target);
    }

    /** The model output /oai math asks for: raw when it is off, the smoothed
     *  reading that feeds the buffer otherwise. */
    private double shownProbability(PlayerData target) {
        return target.displayProbability(!plugin.detection().mathEnabled());
    }

    private Map<String, String> placeholders(PlayerData target, double fraction, int barWidth,
                                             Map<String, String> extra) {
        var theme = plugin.theme().current();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("bar", Msg.bar(fraction, barWidth));
        placeholders.put("arrow", arrow(target));
        placeholders.put("probability", Msg.probability(shownProbability(target)));
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
        stopPass(uuid);
        Map<UUID, Watch> removed = watching.remove(uuid);
        if (removed != null) {
            Player watcher = plugin.getServer().getPlayer(uuid);
            if (watcher != null) {
                for (Watch watch : removed.values()) {
                    destroyHolograms(watcher, watch);
                }
            }
            // Falls through on purpose: this player was watching somebody, and
            // is probably also being watched by somebody else. Returning here
            // left those holograms hanging over a player who had just died or
            // left, until a later sweep happened to notice.
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
