package me.everyone.oculusai.replay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Platform;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** The last few seconds before a punishment, kept so somebody can watch them.
 *
 *  <p>A buffer and a probability are an argument nobody can check. This keeps
 *  a ring buffer of ticks per suspect - where they were, where they were
 *  looking, what stood around them, what landed - and when the punishment
 *  fires it freezes the buffer, takes one snapshot of the terrain and sends
 *  the lot to the service, which holds it for a day. The panel replays it.
 *
 *  <p>Three rules keep this from being a performance problem:
 *
 *  <ul>
 *    <li>only players actually in combat are recorded. Out of a fight there is
 *        nothing to see and nothing that will ever be punished, so an idle
 *        server records nobody at all;</li>
 *    <li>the buffer is bounded by time, not by events: {@code seconds * 20}
 *        frames, oldest dropped, so memory per suspect is fixed and known;</li>
 *    <li>the terrain - the only expensive read - happens once per punishment,
 *        not once per tick. Everything else in a frame is a field read off an
 *        entity that the server has already ticked.</li>
 *  </ul>
 *
 *  <p>Nothing here ever blocks the main thread on the network: the JSON is
 *  built on the owning thread (it must be - it reads entities) and handed to
 *  the HTTP worker.
 */
public final class ReplayRecorder implements Service {

    /** How many actors one frame may name. A frame is written twenty times a
     *  second; a hundred mobs in a farm would be a hundred rows twenty times
     *  a second, and the nearest two dozen are the ones a replay is about. */
    private static final int MAX_ACTORS = 24;
    /** The format the service and the viewer agree on. Bumped when the shape
     *  changes, never reused: an old viewer must be able to tell that it does
     *  not understand a new replay rather than draw it wrong. */
    private static final int FORMAT_VERSION = 1;

    private final OculusAI plugin;
    private final Map<UUID, Deque<ReplayFrame>> buffers = new ConcurrentHashMap<>();
    /** Blows seen by the listener since the last frame, per suspect. The
     *  listener runs on the server's own event thread and the sampler drains
     *  this on the tick loop, so it has to be a queue rather than a list. */
    private final Map<UUID, ConcurrentLinkedQueue<ReplayFrame.Hit>> pending =
            new ConcurrentHashMap<>();
    private final Map<UUID, Integer> ticks = new ConcurrentHashMap<>();
    /** Folia only: one sampler per player, on the thread that owns them. */
    private final Map<UUID, Platform.Cancellable> followers = new ConcurrentHashMap<>();
    private TickLoop.Handle task;

    public ReplayRecorder(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        if (!plugin.config().replayEnabled()) {
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(new ReplayListener(plugin), plugin);
        // Same split as EntityTracker: on Paper one task reads every player,
        // on Folia a player may only be read from the thread that owns them.
        if (plugin.platform().folia()) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                follow(player);
            }
            return;
        }
        task = plugin.ticks().every(1L, this::sampleAll);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        followers.values().forEach(Platform.Cancellable::cancel);
        followers.clear();
        buffers.clear();
        pending.clear();
        ticks.clear();
    }

    @Override
    public void reload() {
        disable();
        enable();
    }

    /** Starts sampling one player on their own thread. Does nothing off
     *  Folia, where the single sweep already covers them. */
    public void follow(Player player) {
        if (!plugin.config().replayEnabled() || !plugin.platform().folia() || player == null) {
            return;
        }
        Platform.Cancellable existing = followers.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
        }
        followers.put(player.getUniqueId(),
                plugin.platform().entityRepeating(player, 1L, 1L, () -> sample(player)));
    }

    public void unfollow(Player player) {
        if (player == null) {
            return;
        }
        Platform.Cancellable existing = followers.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
        }
    }

    /** Drops everything remembered about a player. Called when they leave and
     *  when an operator forgets them: a replay is evidence about a suspicion,
     *  and a suspicion that has been cleared should leave nothing behind. */
    public void forget(UUID uuid) {
        buffers.remove(uuid);
        pending.remove(uuid);
        ticks.remove(uuid);
    }

    /** Records one blow. Called from the listener, on whatever thread the
     *  server fired the damage event on. */
    void note(UUID suspect, ReplayFrame.Hit hit) {
        if (!plugin.config().replayEnabled() || !buffers.containsKey(suspect)) {
            // Nothing is being recorded for this player - they are not in a
            // fight as far as the sampler is concerned - so the blow has
            // nowhere to go. Keeping it would mean an unbounded map of hits
            // for players nobody is watching.
            return;
        }
        pending.computeIfAbsent(suspect, id -> new ConcurrentLinkedQueue<>()).add(hit);
    }

    private void sampleAll() {
        for (PlayerData data : plugin.data().all()) {
            Player player = data.player();
            if (player != null && player.isOnline()) {
                sample(player);
            }
        }
    }

    private void sample(Player player) {
        PlayerData data = plugin.data().get(player);
        if (data == null || !player.isOnline()) {
            return;
        }
        // Out of combat there is nothing worth keeping, and a cleared buffer
        // means whatever was kept no longer describes a suspect. Both halves
        // matter: the first stops an idle server recording anything, the
        // second stops a fight from an hour ago being the one in the file.
        if (!data.inCombat(plugin.config().replayCombatMs())) {
            if (buffers.remove(player.getUniqueId()) != null) {
                pending.remove(player.getUniqueId());
                ticks.remove(player.getUniqueId());
            }
            return;
        }
        if (plugin.bypass().isBypassed(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        int frames = Math.max(20, plugin.config().replaySeconds() * 20);
        Deque<ReplayFrame> buffer = buffers.computeIfAbsent(uuid, id -> new ArrayDeque<>());
        int tick = ticks.merge(uuid, 1, Integer::sum);

        Location where = player.getLocation();
        int flags = 0;
        if (player.isSprinting()) flags |= ReplayFrame.FLAG_SPRINTING;
        if (player.isSneaking()) flags |= ReplayFrame.FLAG_SNEAKING;
        if (player.isOnGround()) flags |= ReplayFrame.FLAG_ON_GROUND;
        if (player.getLocation().getBlock().isLiquid()) flags |= ReplayFrame.FLAG_IN_WATER;

        ReplayFrame frame = new ReplayFrame(tick,
                where.getX(), where.getY(), where.getZ(),
                where.getYaw(), where.getPitch(), flags,
                data.probability(), data.buffer(),
                actors(player), drain(uuid));

        synchronized (buffer) {
            buffer.addLast(frame);
            while (buffer.size() > frames) {
                buffer.removeFirst();
            }
        }
    }

    private List<ReplayFrame.Hit> drain(UUID uuid) {
        ConcurrentLinkedQueue<ReplayFrame.Hit> queue = pending.get(uuid);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        List<ReplayFrame.Hit> hits = new ArrayList<>(2);
        ReplayFrame.Hit hit;
        while ((hit = queue.poll()) != null) {
            hits.add(hit);
        }
        return List.copyOf(hits);
    }

    /** What stood near the suspect this tick.
     *
     *  <p>{@code getNearbyEntities} is a scan of the chunks around the player,
     *  which is why only players in combat get one and why the radius is the
     *  scene's radius rather than something generous. On Folia an entity at a
     *  region border can refuse to be read at all; a frame without actors is
     *  worth more than no replay, so that is caught and the frame goes out
     *  with the suspect alone in it. */
    private List<ReplayFrame.Actor> actors(Player player) {
        double radius = plugin.config().replayRadius();
        List<ReplayFrame.Actor> actors = new ArrayList<>();
        try {
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (actors.size() >= MAX_ACTORS) {
                    break;
                }
                Location at = entity.getLocation();
                double health = entity instanceof LivingEntity living ? living.getHealth() : 0.0D;
                actors.add(new ReplayFrame.Actor(entity.getEntityId(),
                        types.index(entity.getType().name()),
                        at.getX(), at.getY(), at.getZ(),
                        at.getYaw(), at.getPitch(), health));
            }
        } catch (RuntimeException crossRegion) {
            // Folia, almost certainly. Nothing to do about it and nothing
            // worth logging twenty times a second.
            return List.of();
        }
        return List.copyOf(actors);
    }

    /** Entity type names, shared by every frame of every replay this run.
     *  The table travels with the replay, so an index means the same thing a
     *  day later when the panel reads it back. */
    private final TypeTable types = new TypeTable();

    private static final class TypeTable {

        private final Map<String, Integer> indices = new LinkedHashMap<>();

        synchronized int index(String name) {
            Integer known = indices.get(name);
            if (known != null) {
                return known;
            }
            int next = indices.size();
            indices.put(name, next);
            return next;
        }

        synchronized JsonArray toJson() {
            JsonArray array = new JsonArray();
            indices.keySet().forEach(array::add);
            return array;
        }
    }

    /** Freezes what is buffered for this player and sends it up.
     *
     *  <p>Called from the punishment path, before the send-off strips or kills
     *  anybody: the scene has to be the one that got them punished, not the
     *  one the punishment left behind. Does its reading on the owning thread
     *  and its sending on the HTTP worker. */
    public void capture(PlayerData data, String kind) {
        if (!plugin.config().replayEnabled()) {
            return;
        }
        Player player = data.player();
        if (player == null || !player.isOnline()) {
            return;
        }
        Deque<ReplayFrame> buffer = buffers.get(data.uuid());
        if (buffer == null) {
            return;
        }
        List<ReplayFrame> frozen;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            frozen = List.copyOf(buffer);
        }

        plugin.platform().onEntity(player, () -> {
            JsonObject payload = build(data, player, kind, frozen);
            if (payload == null) {
                return;
            }
            plugin.api().uploadReplay(payload).thenAccept(answer -> {
                if (answer == null) {
                    plugin.getLogger().warning("Replay for " + data.name()
                            + " was not accepted by the service");
                    return;
                }
                String id = answer.has("id") ? answer.get("id").getAsString() : "";
                plugin.getLogger().info("Replay of " + data.name() + " stored as " + id);
            });
        });
    }

    private JsonObject build(PlayerData data, Player player, String kind,
                             List<ReplayFrame> frames) {
        JsonObject out = new JsonObject();
        out.addProperty("format", FORMAT_VERSION);
        out.addProperty("player", data.name());
        out.addProperty("uuid", data.uuid().toString());
        out.addProperty("kind", kind);
        out.addProperty("probability", round(data.probability(), 4));
        out.addProperty("buffer", round(data.buffer(), 3));
        out.addProperty("at", System.currentTimeMillis());
        out.addProperty("world", player.getWorld().getName());
        out.addProperty("radius", plugin.config().replayRadius());
        out.addProperty("self_id", player.getEntityId());

        try {
            // Every position the suspect was in, so the ground is captured
            // along the whole path rather than around the one spot they
            // happened to be standing on when the punishment fired.
            List<double[]> path = new ArrayList<>(frames.size());
            for (ReplayFrame frame : frames) {
                path.add(new double[]{frame.x(), frame.y(), frame.z()});
            }
            path.add(new double[]{player.getLocation().getX(),
                    player.getLocation().getY(), player.getLocation().getZ()});
            JsonObject terrain = SceneCapture.terrain(player.getWorld(), path,
                    plugin.config().replayRadius());
            if (terrain != null) {
                out.add("terrain", terrain);
            }
        } catch (RuntimeException unreadable) {
            // A region boundary on Folia, or a world unloaded between the
            // punishment and this call. The frames are still worth having.
            plugin.getLogger().fine("Replay terrain for " + data.name()
                    + " could not be read: " + unreadable.getMessage());
        }

        JsonArray rows = new JsonArray();
        for (ReplayFrame frame : frames) {
            rows.add(frameJson(frame));
        }
        out.add("frames", rows);
        out.add("types", types.toJson());
        out.add("names", names(player, frames));

        int limit = plugin.config().replayMaxKb() * 1024;
        String encoded = out.toString();
        if (encoded.length() <= limit) {
            return out;
        }
        // Over the cap. The terrain is the biggest single thing and the least
        // important - the frames are the evidence - so it goes first, and
        // only then are frames trimmed from the front. Dropping the oldest
        // keeps the moment of the kick, which is the end.
        out.remove("terrain");
        while (out.toString().length() > limit && rows.size() > 20) {
            rows.remove(0);
        }
        if (out.toString().length() > limit) {
            plugin.getLogger().warning("Replay of " + data.name()
                    + " is over " + plugin.config().replayMaxKb() + " KB even trimmed; dropped");
            return null;
        }
        return out;
    }

    private JsonObject frameJson(ReplayFrame frame) {
        JsonObject out = new JsonObject();
        out.addProperty("t", frame.tick());
        JsonArray self = new JsonArray();
        self.add(round(frame.x(), 3));
        self.add(round(frame.y(), 3));
        self.add(round(frame.z(), 3));
        self.add(round(frame.yaw(), 2));
        self.add(round(frame.pitch(), 2));
        self.add(frame.flags());
        out.add("s", self);
        out.addProperty("p", round(frame.probability(), 4));
        out.addProperty("b", round(frame.buffer(), 3));

        if (!frame.actors().isEmpty()) {
            JsonArray actors = new JsonArray();
            for (ReplayFrame.Actor actor : frame.actors()) {
                JsonArray row = new JsonArray();
                row.add(actor.id());
                row.add(actor.type());
                row.add(round(actor.x(), 3));
                row.add(round(actor.y(), 3));
                row.add(round(actor.z(), 3));
                row.add(round(actor.yaw(), 2));
                row.add(round(actor.pitch(), 2));
                row.add(round(actor.health(), 1));
                actors.add(row);
            }
            out.add("a", actors);
        }

        if (!frame.hits().isEmpty()) {
            JsonArray hits = new JsonArray();
            for (ReplayFrame.Hit hit : frame.hits()) {
                JsonArray row = new JsonArray();
                row.add(hit.attacker());
                row.add(hit.victim());
                row.add(hit.crit() ? 1 : 0);
                row.add(round(hit.damage(), 2));
                hits.add(row);
            }
            out.add("h", hits);
        }
        return out;
    }

    /** Entity id to a name, for the ids that actually appear in the replay.
     *
     *  <p>Only players and named entities get one: the viewer labels a mob by
     *  its type, and a nameplate over every zombie in the scene would say
     *  nothing. Resolved from the live server, so an entity that died during
     *  the eight seconds is left unnamed rather than guessed at. */
    private JsonObject names(Player suspect, List<ReplayFrame> frames) {
        JsonObject out = new JsonObject();
        out.addProperty(Integer.toString(suspect.getEntityId()), suspect.getName());
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (ReplayFrame frame : frames) {
            for (ReplayFrame.Actor actor : frame.actors()) {
                if (!seen.add(actor.id())) {
                    continue;
                }
                Player named = playerById(actor.id());
                if (named != null) {
                    out.addProperty(Integer.toString(actor.id()), named.getName());
                }
            }
        }
        return out;
    }

    private Player playerById(int entityId) {
        for (PlayerData data : plugin.data().all()) {
            if (data.entityId() == entityId) {
                return data.player();
            }
        }
        return null;
    }

    private static double round(double value, int places) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
