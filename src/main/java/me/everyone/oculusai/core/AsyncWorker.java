package me.everyone.oculusai.core;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AsyncWorker {

    private final Logger logger;
    private final ScheduledExecutorService executor;

    public AsyncWorker(Logger logger) {
        this.logger = logger;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "OculusAI-Worker");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public Executor executor() {
        return executor;
    }

    public void execute(Runnable task) {
        executor.execute(guard(task));
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return executor.schedule(guard(task), delay, unit);
    }

    public ScheduledFuture<?> repeat(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return executor.scheduleWithFixedDelay(guard(task), initialDelay, period, unit);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable failure) {
                logger.log(Level.SEVERE, "Uncaught worker failure", failure);
            }
        };
    }
}
