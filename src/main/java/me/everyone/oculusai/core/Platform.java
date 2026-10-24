package me.everyone.oculusai.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import me.everyone.oculusai.OculusAI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** Which server this is running on, and how to get onto the right thread.
 *
 * <p>Paper has one thread that owns every entity in every world, so a single
 * repeating task can read the lot. Folia splits the world into regions, each
 * ticked by its own thread, and an entity may only be touched from the thread
 * that currently owns it; there is no thread from which all of them are legal.
 * Code that wants to touch a player therefore has to say so, and this class is
 * the only place that knows which server it is talking to.
 *
 * <p>Three places to be, then, and every feature here has to name one: the
 * global region, which owns no entity and no block; an entity's own region,
 * which owns that entity and whatever it is standing in; and a location's
 * region, which owns the blocks and the entities of an area. Anything that
 * needs two of them at once has to pass a snapshot between them rather than
 * reach across - which is what the holograms, the manikin and the spectator
 * follow now do, and why they used to be switched off here instead.
 *
 * <p>Folia's schedulers are reached by reflection rather than by compiling
 * against them. The plugin ships one jar per Minecraft generation, not one per
 * server fork, and paper-api has none of these types: a direct call would turn
 * a Folia-only class into a hard requirement and stop the jar loading on the
 * Paper servers that are the overwhelming majority. Reflection keeps the Paper
 * path exactly what it was and costs one lookup per scheduled task.
 */
public final class Platform {

    /** A scheduled task, however this server chose to represent one. */
    public interface Cancellable {
        void cancel();
    }

    private final OculusAI plugin;
    private final boolean folia;

    public Platform(OculusAI plugin) {
        this.plugin = plugin;
        this.folia = detect();
    }

    /** True on Folia and its derivatives. */
    public boolean folia() {
        return folia;
    }

    private static boolean detect() {
        try {
            // The regionised server class exists only where the world is
            // actually split into regions. Checking for the scheduler method
            // instead would also match the compatibility shims some forks
            // expose while still ticking on one thread.
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /** Runs an action every {@code periodTicks}, not tied to any one entity.
     *
     * <p>This is the plugin's heartbeat. On Folia it lands on the global
     * region thread, which owns no entities: whatever it does with a player
     * has to be handed to {@link #onEntity} first. */
    public Cancellable globalRepeating(long initialDelayTicks, long periodTicks, Runnable action) {
        long delay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (!folia) {
            var task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, action, delay, period);
            return task::cancel;
        }
        Object scheduler = invoke(Bukkit.class, "getGlobalRegionScheduler", null);
        Object task = invoke(scheduler, "runAtFixedRate",
                new Class<?>[] {org.bukkit.plugin.Plugin.class, Consumer.class, long.class, long.class},
                plugin, (Consumer<Object>) ignored -> action.run(), delay, period);
        return () -> invoke(task, "cancel", null);
    }

    /** Runs an action on the thread that owns this entity, as soon as it can.
     *
     * <p>On Paper that is the main thread, and if the caller is already on it
     * the action runs inline - the behaviour {@code MainThread} always had. On
     * Folia it goes to the entity's own scheduler, which also means it is
     * simply dropped if the entity is gone by then, which is what we want for
     * every caller here: a sound for a player who logged off, a kick for a
     * player already removed. */
    public void onEntity(Entity entity, Runnable action) {
        if (entity == null) {
            return;
        }
        if (!folia) {
            plugin.mainThread().run(action);
            return;
        }
        Object scheduler = invoke(entity, "getScheduler", null);
        invoke(scheduler, "run",
                new Class<?>[] {org.bukkit.plugin.Plugin.class, Consumer.class, Runnable.class},
                plugin, (Consumer<Object>) ignored -> action.run(), null);
    }

    /** Runs an action every {@code periodTicks} on the thread owning this
     *  entity, for as long as the entity exists. */
    public Cancellable entityRepeating(Entity entity, long initialDelayTicks, long periodTicks,
                                       Runnable action) {
        long delay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (!folia) {
            var task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, action, delay, period);
            return task::cancel;
        }
        Object scheduler = invoke(entity, "getScheduler", null);
        Object task = invoke(scheduler, "runAtFixedRate",
                new Class<?>[] {org.bukkit.plugin.Plugin.class, Consumer.class, Runnable.class,
                        long.class, long.class},
                plugin, (Consumer<Object>) ignored -> action.run(), null, delay, period);
        return () -> {
            if (task != null) {
                invoke(task, "cancel", null);
            }
        };
    }

    /** Runs an action on the thread that owns this place in the world.
     *
     * <p>The missing third scheduler, and the reason several features were
     * simply off on Folia. {@link #globalRepeating} runs where no entity is
     * legal and {@link #onEntity} runs where exactly one is; neither can read
     * a block, an arbitrary chunk, or the entities standing in an area. Folia
     * ties all of those to the region that owns the location, which is what
     * this hands the work to.
     *
     * <p>On Paper it is the main thread, like everything else. */
    public void onRegion(Location where, Runnable action) {
        if (where == null || where.getWorld() == null) {
            return;
        }
        if (!folia) {
            plugin.mainThread().run(action);
            return;
        }
        Object scheduler = invoke(Bukkit.class, "getRegionScheduler", null);
        invoke(scheduler, "execute",
                new Class<?>[] {org.bukkit.plugin.Plugin.class, org.bukkit.World.class,
                        int.class, int.class, Runnable.class},
                plugin, where.getWorld(), where.getBlockX() >> 4, where.getBlockZ() >> 4,
                action);
    }

    /** Runs an action every {@code periodTicks} on the thread owning this
     *  place. Used for upkeep of a fixed area - the test arena's mobs and
     *  scoreboards - which belongs to one region however many players walk
     *  through it. */
    public Cancellable regionRepeating(Location where, long initialDelayTicks,
                                       long periodTicks, Runnable action) {
        long delay = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);
        if (where == null || where.getWorld() == null) {
            return () -> { };
        }
        if (!folia) {
            var task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, action, delay, period);
            return task::cancel;
        }
        Object scheduler = invoke(Bukkit.class, "getRegionScheduler", null);
        Object task = invoke(scheduler, "runAtFixedRate",
                new Class<?>[] {org.bukkit.plugin.Plugin.class, Location.class, Consumer.class,
                        long.class, long.class},
                plugin, where, (Consumer<Object>) ignored -> action.run(), delay, period);
        return () -> {
            if (task != null) {
                invoke(task, "cancel", null);
            }
        };
    }

    /** Whether the caller's thread owns this entity right now.
     *
     * <p>Always true on Paper, where one thread owns everything. On Folia it
     * is the question anything touching a second entity has to ask, and the
     * only honest way to ask it: region ownership is not a property of the
     * entity, it is a property of the entity *and* the asking thread. */
    public boolean owns(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (!folia) {
            return plugin.mainThread().isCurrent();
        }
        Object answer = invoke(Bukkit.getServer(), "isOwnedByCurrentRegion",
                new Class<?>[] {Entity.class}, entity);
        return answer instanceof Boolean owned && owned;
    }

    /** Whether the caller's thread owns this chunk right now. Same question
     *  as {@link #owns(Entity)}, asked of a place: a block read is only legal
     *  from the region that owns the chunk it is in. */
    public boolean owns(org.bukkit.World world, int chunkX, int chunkZ) {
        if (world == null) {
            return false;
        }
        if (!folia) {
            return plugin.mainThread().isCurrent();
        }
        Object answer = invoke(Bukkit.getServer(), "isOwnedByCurrentRegion",
                new Class<?>[] {org.bukkit.World.class, int.class, int.class},
                world, chunkX, chunkZ);
        return answer instanceof Boolean owned && owned;
    }

    /** Moves a player, and runs something once they have arrived.
     *
     * <p>Always the asynchronous teleport, on both servers. On Folia a plain
     * {@code teleport} across a region boundary is refused outright; on Paper
     * the asynchronous one is the same teleport with the chunk load waited on
     * rather than forced, which is what anybody wants anyway. The callback
     * lands on the thread that owns the player at their destination, which is
     * exactly where a caller wanting to touch them next has to be. */
    public void teleport(Player player, Location destination, Runnable arrived) {
        if (player == null || destination == null) {
            return;
        }
        player.teleportAsync(destination).thenAccept(moved -> {
            if (moved && arrived != null) {
                onEntity(player, arrived);
            }
        });
    }

    /** Finds a method that can actually be called.
     *
     * <p>Not the same thing as finding a method. Folia's schedulers hand back
     * implementations whose classes are not public -
     * {@code FoliaEntityScheduler$EntityScheduledTask} is the one that
     * mattered - and a public method on a non-public class cannot be invoked
     * reflectively at all: the lookup succeeds and the call fails with
     * IllegalAccessException. Every {@code cancel()} on this server went that
     * way, which meant no scheduled task could be stopped: the punishment
     * show cancelled its frame task, threw instead, never reached the line
     * that disconnects, and left the cheater frozen in mid-air rather than
     * kicked. The animation ran, the kick never came, and nothing in the
     * plugin said why.
     *
     * <p>So the method is taken from the public interface that declares it,
     * which is the thing the server actually promises. Opening up the
     * implementation is the last resort and works only because Paper is on
     * the class path rather than in a module of its own. */
    private static Method lookup(Class<?> owner, String name, Class<?>[] signature)
            throws NoSuchMethodException {
        Method direct = signature == null ? owner.getMethod(name) : owner.getMethod(name, signature);
        if (Modifier.isPublic(direct.getDeclaringClass().getModifiers())) {
            return direct;
        }
        for (Class<?> type : supertypes(owner)) {
            if (!Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            try {
                return signature == null ? type.getMethod(name) : type.getMethod(name, signature);
            } catch (NoSuchMethodException notHere) {
                // A supertype that does not declare it; keep looking.
            }
        }
        direct.setAccessible(true);
        return direct;
    }

    /** Every interface and superclass of a type, nearest first. */
    private static java.util.Set<Class<?>> supertypes(Class<?> type) {
        // A set, because add() is also the "have I seen this already" test
        // here: a type reached through two different interfaces must not be
        // queued twice.
        java.util.Set<Class<?>> found = new java.util.LinkedHashSet<>();
        java.util.Deque<Class<?>> queue = new java.util.ArrayDeque<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> next = queue.poll();
            for (Class<?> parent : next.getInterfaces()) {
                if (found.add(parent)) {
                    queue.add(parent);
                }
            }
            Class<?> superclass = next.getSuperclass();
            if (superclass != null && superclass != Object.class && found.add(superclass)) {
                queue.add(superclass);
            }
        }
        return found;
    }

    /** Calls a method that only exists on Folia.
     *
     * <p>Anything going wrong here is a mismatch between this code and the
     * server's own API, not a condition a caller could handle, so it is thrown
     * rather than swallowed: a Folia scheduler that silently did nothing would
     * leave the plugin looking enabled and analysing nobody. */
    private static Object invoke(Object target, String name, Class<?>[] signature, Object... args) {
        boolean isStatic = target instanceof Class<?>;
        Class<?> owner = isStatic ? (Class<?>) target : target.getClass();
        try {
            Method method = lookup(owner, name, signature);
            return method.invoke(isStatic ? null : target, args);
        } catch (NoSuchMethodException | IllegalAccessException | SecurityException failure) {
            throw new IllegalStateException(
                    "This server's " + name + " does not look like Folia's", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Folia's " + name + " failed", cause);
        }
    }
}
