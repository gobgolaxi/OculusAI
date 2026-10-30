package me.everyone.oculusai.player;


import me.everyone.oculusai.core.Platform;
import me.everyone.oculusai.core.Service;
import me.everyone.oculusai.core.TickLoop;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.everyone.oculusai.OculusAI;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class EntityTracker implements Service {

    /** ``playerLike`` separates what may be analysed from what may not. Aim
     *  against a mob is not the same measurement as aim against a player: a
     *  zombie walks a straight line at a fixed speed and never strafes, so a
     *  perfectly ordinary player farming one produces the flat, precise
     *  rotation that the model was taught to read as a cheat. Dummies count as
     *  player-like - they are player-shaped NPCs and exist to be recorded
     *  against. */
    public record Tracked(double x, double y, double z, double width, double height,
                          boolean playerLike, UUID worldId, long at) {

        public double centreY() {
            return y + height / 2.0D;
        }
    }

    /** One second covers a client that is a few movement packets behind the
     *  server. WallCheck keeps its own shorter window; HitBox needs the wider
     *  one because an attack packet carries no target position of its own. */
    /** Глаза относительно высоты. Хитбокс игрока 1.8, глаза на 1.62 - для
     *  сника и для player-shaped NPC другой высоты доля честнее константы. */
    private static final double EYE_RATIO = 1.62D / 1.8D;

    private static final long HISTORY_MS = 1000L;
    private static final int HISTORY_LIMIT = 20;

    private final OculusAI plugin;
    private final Map<Integer, Tracked> tracked = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<Tracked>> history = new ConcurrentHashMap<>();
    /** Entity id to the online player, refreshed once per tick. WallCheck used
     *  to scan every online player on every hit to find this mapping. */
    private final Map<Integer, Player> players = new ConcurrentHashMap<>();
    private TickLoop.Handle task;
    /** Folia only: one sampling task per player, on the thread owning them. */
    private final Map<UUID, Platform.Cancellable> followers = new ConcurrentHashMap<>();

    public EntityTracker(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        // One sweep of every online player is a cross-region read, which Folia
        // forbids: there is no thread on which every player is legal. The same
        // work is therefore split up and each player sampled on the thread
        // that owns them. On Paper the single sweep stays, because one task
        // doing the rounds is cheaper than one task per player.
        if (plugin.platform().folia()) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                follow(player);
            }
            return;
        }
        task = plugin.ticks().every(1L, this::sample);
    }

    /** Starts sampling one player on their own thread. Does nothing off
     *  Folia, where the single sweep already covers them. */
    public void follow(Player player) {
        if (!plugin.platform().folia() || player == null) {
            return;
        }
        Platform.Cancellable existing = followers.remove(player.getUniqueId());
        if (existing != null) {
            existing.cancel();
        }
        followers.put(player.getUniqueId(),
                plugin.platform().entityRepeating(player, 1L, 1L, () -> sample(player)));
    }

    /** Stops sampling one player. */
    public void unfollow(Player player) {
        if (player == null) {
            return;
        }
        Platform.Cancellable follower = followers.remove(player.getUniqueId());
        if (follower != null) {
            follower.cancel();
        }
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Platform.Cancellable follower : followers.values()) {
            follower.cancel();
        }
        followers.clear();
        tracked.clear();
        history.clear();
        players.clear();
    }

    private void sample() {
        // Players and dummies only. Mobs used to be sampled here as well, up
        // to a budget per tick, so that a swing at one could be scored - but a
        // swing at one is no longer analysed at all, so their positions were
        // being written twenty times a second and never read.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sample(player);
        }
    }

    /** Records where one player is. Must run on the thread owning them. */
    private void sample(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        players.put(player.getEntityId(), player);
        remember(player.getEntityId(), new Tracked(
                location.getX(), location.getY(), location.getZ(),
                player.getWidth(), player.getHeight(), true, world.getUID(),
                System.currentTimeMillis()));
    }

    public void track(int entityId, double x, double y, double z, double width,
                      double height, World world) {
        track(entityId, x, y, z, width, height, true, world);
    }

    public void track(int entityId, double x, double y, double z, double width,
                      double height, boolean playerLike, World world) {
        if (world == null) {
            return;
        }
        remember(entityId, new Tracked(x, y, z, width, height, playerLike,
                world.getUID(), System.currentTimeMillis()));
    }

    private void remember(int entityId, Tracked entry) {
        tracked.put(entityId, entry);
        Deque<Tracked> entries = history.computeIfAbsent(entityId, key -> new ArrayDeque<>());
        synchronized (entries) {
            entries.addLast(entry);
            long cutoff = entry.at() - HISTORY_MS;
            while (entries.size() > 1
                    && (entries.peekFirst().at() < cutoff || entries.size() > HISTORY_LIMIT)) {
                entries.removeFirst();
            }
        }
    }

    /** Whether this entity may be analysed at all. An entity nobody is
     *  tracking answers false: unknown is not the same as a player, and
     *  guessing in favour of analysis is how a mob fight becomes a ban. */
    public boolean playerLike(int entityId) {
        Tracked entry = tracked.get(entityId);
        return entry != null && entry.playerLike();
    }

    public List<Tracked> recent(int entityId) {
        Deque<Tracked> entries = history.get(entityId);
        if (entries == null) {
            return List.of();
        }
        long cutoff = System.currentTimeMillis() - HISTORY_MS;
        synchronized (entries) {
            while (!entries.isEmpty() && entries.peekFirst().at() < cutoff) {
                entries.removeFirst();
            }
            return List.copyOf(entries);
        }
    }

    public void forget(int entityId) {
        tracked.remove(entityId);
        history.remove(entityId);
        players.remove(entityId);
    }

    /** The online player for this entity id, or null for a dummy/unknown. */
    public Player player(int entityId) {
        Player player = players.get(entityId);
        return player != null && player.isOnline() ? player : null;
    }

    public Tracked get(int entityId) {
        return tracked.get(entityId);
    }

    /** Отслеживаемая сущность вместе со своим id. */
    public record Near(int entityId, Tracked entry, double distance) {
    }

    /** Ближайший игроко-подобный к этой точке, в её же мире, кроме самого.
     *
     *  <p>Нужен затем, что до появления этого метода угол до цели считался
     *  только в тике удара: по пакету атаки, у которого цель написана. В
     *  остальных тиках окна цели не было известно вообще, и самый сильный
     *  признак аимбота - как шла ошибка прицела к хитбоксу - существовал
     *  одной точкой из тридцати двух.
     *
     *  <p>Ближайший, а не «тот, кого ударили»: в тике без удара никто не
     *  сказал, по кому велась рука, и ближайший - единственная догадка,
     *  которую можно сделать не заглядывая в будущее окна. На дуэли это тот
     *  же игрок; в свалке это меняется от тика к тику, и канал смены цели
     *  говорит об этом прямо, а не прячет. */
    public Near nearestPlayerLike(Tracked self, int selfId, double maxBlocks) {
        if (self == null) {
            return null;
        }
        double limit = maxBlocks * maxBlocks;
        Near best = null;
        for (Map.Entry<Integer, Tracked> entry : tracked.entrySet()) {
            if (entry.getKey() == selfId) {
                continue;
            }
            Tracked other = entry.getValue();
            if (!other.playerLike() || !other.worldId().equals(self.worldId())) {
                continue;
            }
            double dx = other.x() - self.x();
            double dy = other.centreY() - (self.y() + self.height() * EYE_RATIO);
            double dz = other.z() - self.z();
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared > limit) {
                continue;
            }
            if (best == null || squared < best.distance() * best.distance()) {
                best = new Near(entry.getKey(), other, Math.sqrt(squared));
            }
        }
        return best;
    }

    /** Сколько игроко-подобных рядом, кроме самого.
     *
     *  <p>Отличает «довёл до единственного» от «выбрал из трёх»: ровная
     *  доводка на дуэли и та же ровность, когда вокруг четверо, - это разные
     *  события, и без этого числа они выглядят одинаково. */
    public int countPlayerLike(Tracked self, int selfId, double maxBlocks) {
        if (self == null) {
            return 0;
        }
        double limit = maxBlocks * maxBlocks;
        int count = 0;
        for (Map.Entry<Integer, Tracked> entry : tracked.entrySet()) {
            if (entry.getKey() == selfId) {
                continue;
            }
            Tracked other = entry.getValue();
            if (!other.playerLike() || !other.worldId().equals(self.worldId())) {
                continue;
            }
            double dx = other.x() - self.x();
            double dy = other.centreY() - (self.y() + self.height() * EYE_RATIO);
            double dz = other.z() - self.z();
            if (dx * dx + dy * dy + dz * dz <= limit) {
                count++;
            }
        }
        return count;
    }

    public void forget(Player player) {
        forget(player.getEntityId());
    }
}
