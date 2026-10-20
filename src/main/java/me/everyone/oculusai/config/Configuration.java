package me.everyone.oculusai.config;

import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.core.Service;

public final class Configuration implements Service {

    private final Config settings;
    private final Themes theme;
    private final Language language;
    private final HudConfig hud;

    public Configuration(OculusAI plugin) {
        this.settings = new Config(plugin);
        this.theme = new Themes(plugin);
        this.language = new Language(plugin);
        this.hud = new HudConfig(plugin);
    }

    @Override
    public void enable() {
        settings.enable();
        theme.enable();
        language.enable();
        hud.enable();
    }

    @Override
    public void disable() {
        hud.disable();
        language.disable();
        theme.disable();
        settings.disable();
    }

    @Override
    public void reload() {
        settings.reload();
        theme.reload();
        language.reload();
        hud.reload();
    }

    public Config settings() {
        return settings;
    }

    public Themes theme() {
        return theme;
    }

    public Language language() {
        return language;
    }

    public HudConfig hud() {
        return hud;
    }
}
