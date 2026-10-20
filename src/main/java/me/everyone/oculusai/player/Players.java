package me.everyone.oculusai.player;

import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;

public final class Players implements Service {

    private final PlayerRegistry registry;
    private final EntityTracker tracker;
    private final SetupNotifier setup;

    public Players(OculusAI plugin) {
        this.registry = new PlayerRegistry(plugin);
        this.tracker = new EntityTracker(plugin);
        this.setup = new SetupNotifier(plugin);
    }

    @Override
    public void enable() {
        registry.enable();
        tracker.enable();
        setup.enable();
    }

    @Override
    public void disable() {
        setup.disable();
        tracker.disable();
        registry.disable();
    }

    @Override
    public void reload() {
        setup.disable();
        setup.enable();
    }

    public PlayerRegistry registry() {
        return registry;
    }

    public EntityTracker tracker() {
        return tracker;
    }

    public SetupNotifier setup() {
        return setup;
    }
}
