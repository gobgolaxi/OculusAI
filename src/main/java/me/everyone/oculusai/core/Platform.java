package me.everyone.oculusai.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import me.everyone.oculusai.OculusAI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

/** Which server this is running on, and how to get onto the right thread.
 *
 * <p>Paper has one thread that owns every entity in every world, so a single
 * repeating task can read the lot. Folia splits the world into regions, each
 * ticked by its own thread, and an entity may only be touched from the thread
 * that currently owns it; there is no thread from which all of them are legal.
 * Code that wants to touch a player therefore has to say so, and this class is
 * the only place that knows which server it is talking to.
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
            Method method = signature == null
                    ? owner.getMethod(name)
                    : owner.getMethod(name, signature);
            return method.invoke(isStatic ? null : target, args);
        } catch (NoSuchMethodException | IllegalAccessException failure) {
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
