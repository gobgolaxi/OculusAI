package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.util.Msg;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ThemeManager implements Manager {

    public record Theme(String id, String primary, String secondary, String accent, String accentSoft) {
    }

    private final YuppyAI plugin;
    private final Map<String, Theme> themes = new LinkedHashMap<>();
    private Theme current;

    public ThemeManager(YuppyAI plugin) {
        this.plugin = plugin;
        registerDefaults();
    }

    @Override
    public void enable() {
        load();
    }

    @Override
    public void reload() {
        load();
    }

    private void load() {
        String id = plugin.getConfig().getString("theme.name", "aurora");
        current = themes.getOrDefault(id, themes.get("aurora"));
    }

    private void registerDefaults() {
        themes.put("aurora", new Theme("aurora", "#8e7bff", "#38bdf8", "#ffd166", "#b8f2e6"));
        themes.put("ember", new Theme("ember", "#ff6b6b", "#ff9f1c", "#ffe66d", "#f7b267"));
        themes.put("ocean", new Theme("ocean", "#00c6ff", "#0072ff", "#80ffdb", "#72efdd"));
        themes.put("rose", new Theme("rose", "#ff4d8d", "#ff8fab", "#ffd6e0", "#ffc2d1"));
        themes.put("mint", new Theme("mint", "#06d6a0", "#1b9aaa", "#f4f1bb", "#94d2bd"));
        themes.put("midnight", new Theme("midnight", "#577590", "#277da1", "#90be6d", "#4d908e"));
    }

    public Theme current() {
        return current;
    }

    public List<Theme> themes() {
        return List.copyOf(themes.values());
    }

    public Theme cycle() {
        List<Theme> ordered = themes();
        int index = Math.max(0, ordered.indexOf(current));
        Theme next = ordered.get((index + 1) % ordered.size());
        plugin.getConfig().set("theme.name", next.id());
        plugin.saveConfig();
        current = next;
        return current;
    }

    public String title(String suffix) {
        return Msg.parse("<gradient:" + current.primary() + ":" + current.secondary()
                + "><bold>ʏᴜᴘᴘʏᴀɪ</bold></gradient> <dark_gray>- <white>" + suffix);
    }

    public String accent(String text) {
        return Msg.parse("<" + current.accent() + ">" + text);
    }

    public String accentSoft(String text) {
        return Msg.parse("<" + current.accentSoft() + ">" + text);
    }

    public String prefix(String template) {
        if (template == null || template.isBlank() || !template.contains("%primary%")) {
            return Msg.parse("<gradient:" + current.primary() + ":" + current.secondary()
                    + "><bold>ʏᴜᴘᴘʏᴀɪ</bold></gradient> <dark_gray>» <gray>");
        }
        return Msg.parse(template
                .replace("%primary%", current.primary())
                .replace("%secondary%", current.secondary())
                .replace("%accent%", current.accent())
                .replace("%accent_soft%", current.accentSoft()));
    }
}
