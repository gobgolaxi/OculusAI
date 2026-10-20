package me.everyone.oculusai.core;

import me.everyone.oculusai.OculusAI;
import org.bukkit.Bukkit;

public final class MainThread {

    private final OculusAI plugin;

    public MainThread(OculusAI plugin) {
        this.plugin = plugin;
    }

    public boolean isCurrent() {
        return Bukkit.isPrimaryThread();
    }

    public void run(Runnable action) {
        if (isCurrent()) {
            action.run();
        } else {
            plugin.ticks().after(0L, action);
        }
    }

    public void after(Runnable action, long ticks) {
        plugin.ticks().after(ticks, action);
    }
}
