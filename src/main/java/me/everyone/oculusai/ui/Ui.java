package me.everyone.oculusai.ui;

import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;

public final class Ui implements Service {

    private final Hud hud;
    private final Menus menus;
    private final Monitor monitor;

    public Ui(OculusAI plugin) {
        this.hud = new Hud(plugin);
        this.menus = new Menus(plugin);
        this.monitor = new Monitor(plugin);
    }

    @Override
    public void enable() {
        hud.enable();
        monitor.enable();
        menus.enable();
    }

    @Override
    public void disable() {
        menus.disable();
        monitor.disable();
        hud.disable();
    }

    public Hud hud() {
        return hud;
    }

    public Menus menus() {
        return menus;
    }

    public Monitor monitor() {
        return monitor;
    }
}
