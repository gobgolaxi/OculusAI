package me.everyone.oculusai.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import me.everyone.oculusai.OculusAI;

public final class TickLoop implements Service {

    private final OculusAI plugin;
    private final List<Timer> timers = new CopyOnWriteArrayList<>();
    private Platform.Cancellable task;
    private volatile long tick;

    public TickLoop(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        tick = 0L;
        // On Folia this is the global region thread: it owns no entities, so
        // anything a timer does to a player has to go through
        // Platform.onEntity first.
        task = plugin.platform().globalRepeating(1L, 1L, this::runTick);
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        timers.clear();
    }

    public Handle every(long periodTicks, Runnable action) {
        return every(periodTicks, periodTicks, action);
    }

    public Handle every(long initialDelayTicks, long periodTicks, Runnable action) {
        return schedule(Math.max(0L, initialDelayTicks), Math.max(1L, periodTicks), action);
    }

    public Handle after(long delayTicks, Runnable action) {
        return schedule(Math.max(0L, delayTicks), 0L, action);
    }

    public void runNow(Runnable action) {
        plugin.mainThread().run(action);
    }

    private Handle schedule(long delayTicks, long periodTicks, Runnable action) {
        Timer timer = new Timer(tick + delayTicks, periodTicks, action);
        timers.add(timer);
        return timer;
    }

    private void runTick() {
        long now = ++tick;
        for (Timer timer : timers) {
            if (timer.cancelled) {
                timers.remove(timer);
                continue;
            }
            if (timer.due > now) {
                continue;
            }
            try {
                timer.action.run();
            } catch (Throwable failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Uncaught tick task failure", failure);
            }
            if (timer.period == 0L) {
                timers.remove(timer);
            } else {
                timer.due = now + timer.period;
            }
        }
    }

    private final class Timer implements Handle {

        private final long period;
        private final Runnable action;
        private long due;
        private volatile boolean cancelled;

        private Timer(long due, long period, Runnable action) {
            this.due = due;
            this.period = period;
            this.action = action;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    public interface Handle {
        void cancel();
    }
}
