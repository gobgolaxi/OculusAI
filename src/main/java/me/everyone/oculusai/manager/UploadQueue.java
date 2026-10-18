package me.everyone.oculusai.manager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import me.everyone.oculusai.OculusAI;
import org.bukkit.scheduler.BukkitTask;

/**
 * Captures that did not reach the service, kept until they do.
 *
 * A recording is work somebody did with their hands: half an hour of a player
 * fighting under a label. Before this, a failed upload meant that half hour
 * was gone - the request answered 403 or the service was down, {@code commit}
 * got a null, and the rows lived on only until the next restart. Now the batch
 * lands here instead and is offered again every {@link #RETRY_SECONDS}
 * seconds, for as long as the server is up.
 *
 * What it deliberately does not do:
 *
 * <ul>
 *   <li><b>Survive a restart.</b> The queue is memory, like the capture it
 *       came from. A stop-the-server-now outage still loses it.</li>
 *   <li><b>Guard against a double send.</b> A batch the service stored but
 *       whose answer never came back will be sent again, and the service has
 *       no way to tell the second copy from new data. Losing a capture was
 *       judged worse than storing one twice.</li>
 * </ul>
 */
public final class UploadQueue implements Manager {

    /** Between attempts. Long enough not to hammer a service that is down,
     *  short enough that a blip costs a few seconds rather than a capture. */
    public static final int RETRY_SECONDS = 5;

    /** The queue is held in memory, so it cannot be allowed to grow without
     *  limit: a service that is down for an hour during a long recording
     *  session would otherwise push the server into the garbage collector. */
    private static final int MAX_BATCHES = 64;

    /** What a single failed send was, in full, so the retry is the same
     *  request rather than a reconstruction of it. */
    public record Batch(String label, String player, String uuid,
                        List<String> featureNames, List<double[]> rows,
                        boolean enabled, String source, String person,
                        List<double[]> context, int featureVersion) {
    }

    private final OculusAI plugin;
    private final Deque<Batch> pending = new ArrayDeque<>();
    /** One flight at a time: the task fires every five seconds whether or not
     *  the previous attempt has answered, and two attempts at the same batch
     *  would send it twice. */
    private final AtomicBoolean sending = new AtomicBoolean();
    private BukkitTask task;

    public UploadQueue(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::flush,
                RETRY_SECONDS * 20L, RETRY_SECONDS * 20L);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        int lost = size();
        if (lost > 0) {
            plugin.getLogger().warning("Shutting down with " + lost
                    + " capture(s) still unsent; they are lost");
        }
        synchronized (pending) {
            pending.clear();
        }
    }

    /** Takes a failed send. The batch is retried until the service accepts it. */
    public void offer(Batch batch) {
        if (batch.rows().isEmpty()) {
            return;
        }
        int waiting;
        boolean dropped = false;
        synchronized (pending) {
            // The oldest goes, not the newest: a capture made twenty minutes
            // ago is the one most likely to be stale, and dropping what just
            // happened would punish the person still recording.
            if (pending.size() >= MAX_BATCHES) {
                pending.pollFirst();
                dropped = true;
            }
            pending.addLast(batch);
            waiting = pending.size();
        }
        if (dropped) {
            plugin.getLogger().severe("Unsent captures hit the limit of " + MAX_BATCHES
                    + "; the oldest was dropped. The service has been unreachable"
                    + " for a long time - check /oai diag");
        }
        plugin.getLogger().warning("Capture for " + batch.player() + " (" + batch.rows().size()
                + " windows) was not accepted; retrying every " + RETRY_SECONDS
                + "s, " + waiting + " waiting");
    }

    /** How many captures are still waiting. Shown by /oai diag. */
    public int size() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /** How many windows are waiting across every held capture. */
    public int windows() {
        synchronized (pending) {
            return pending.stream().mapToInt(batch -> batch.rows().size()).sum();
        }
    }

    /** Offers the oldest batch again. Called on the timer, and by /oai diag. */
    public void flush() {
        if (!sending.compareAndSet(false, true)) {
            return;
        }
        Batch batch;
        synchronized (pending) {
            batch = pending.peekFirst();
        }
        if (batch == null) {
            sending.set(false);
            return;
        }

        plugin.api().addSamples(batch.label(), batch.player(), batch.uuid(),
                        batch.featureNames(), batch.rows(), batch.enabled(),
                        batch.source(), batch.person(), batch.context(),
                        batch.featureVersion())
                .whenComplete((response, failure) -> {
                    try {
                        if (response == null) {
                            return;
                        }
                        int left;
                        synchronized (pending) {
                            // Removed by identity, not by position: nothing else
                            // takes from the head, but a batch offered while this
                            // one was in flight must not be dropped by mistake.
                            pending.remove(batch);
                            left = pending.size();
                        }
                        plugin.getLogger().info("Held capture for " + batch.player()
                                + " (" + batch.rows().size() + " windows) went through"
                                + (left == 0 ? "" : "; " + left + " still waiting"));
                    } catch (RuntimeException error) {
                        plugin.getLogger().log(Level.WARNING,
                                "Error finishing a held capture", error);
                    } finally {
                        sending.set(false);
                    }
                });
    }
}
