package me.everyone.yuppyai.gui;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.manager.KickAnimation;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.Map;

public final class DashboardMenu extends Menu {

    private static final int SLOT_STATUS = 11;
    private static final int SLOT_THEME = 13;
    private static final int SLOT_PLAYERS = 15;
    private static final int SLOT_AUTOMOD = 29;
    private static final int SLOT_PUNISH = 30;
    private static final int SLOT_REFRESH = 31;
    private static final int SLOT_EVIDENCE = 32;
    private static final int SLOT_ALWAYS_PROB = 33;
    private static final int SLOT_ALERT_SOUND = 22;
    private static final int SLOT_KICK_ANIM = 20;

    private static final String[] ALERT_SOUNDS = {
            "BLOCK_NOTE_BLOCK_BELL",
            "ENTITY_EXPERIENCE_ORB_PICKUP",
            "BLOCK_ANVIL_PLACE",
            "UI_BUTTON_CLICK"
    };

    public DashboardMenu(YuppyAI plugin, org.bukkit.entity.Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("dashboard.title"));
    }

    @Override
    protected int size() {
        return 45;
    }

    @Override
    protected void build() {
        fillBorder(item(Material.GRAY_STAINED_GLASS_PANE, "", null));

        boolean up = plugin.api().reachable();
        set(SLOT_STATUS, item(up ? Material.LIME_DYE : Material.RED_DYE,
                plugin.lang().text(up ? "dashboard.service.up" : "dashboard.service.down"),
                List.of(
                        Msg.parse("<gray>" + plugin.config().apiUrl()),
                        "",
                        up ? plugin.lang().text("dashboard.service.answering")
                                : plugin.lang().text("dashboard.service.start"),
                        up ? "" : plugin.lang().text("dashboard.service.start-command"))));

        var theme = plugin.theme().current();
        set(SLOT_THEME, item(Material.NETHER_STAR, plugin.lang().text("dashboard.theme.name"),
                List.of(
                        plugin.lang().text("dashboard.theme.current",
                                Map.of("value", plugin.lang().text("theme." + theme.id()))),
                        Msg.parse("<gray>" + theme.primary() + " <dark_gray>/ <gray>" + theme.secondary()),
                        Msg.parse("<gray>" + theme.accent() + " <dark_gray>/ <gray>" + theme.accentSoft()),
                        "",
                        plugin.lang().text("dashboard.theme.click"))));

        set(SLOT_PLAYERS, item(Material.PLAYER_HEAD, plugin.lang().text("dashboard.players.name"),
                List.of(
                        plugin.lang().text("dashboard.players.online",
                                Map.of("value", Integer.toString(plugin.data().size()))),
                        "",
                        plugin.lang().text("dashboard.players.click"))));

        boolean autoMod = plugin.config().autoModEnabled();
        set(SLOT_AUTOMOD, item(autoMod ? Material.WRITABLE_BOOK : Material.BOOK,
                plugin.lang().text("dashboard.automod.name"),
                List.of(
                        plugin.lang().text(autoMod ? "dashboard.automod.state-on" : "dashboard.automod.state-off"),
                        "",
                        plugin.lang().text("dashboard.automod.line1"),
                        plugin.lang().text("dashboard.automod.line2"),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        boolean punishing = plugin.config().punishmentEnabled();
        set(SLOT_PUNISH, item(punishing ? Material.IRON_SWORD : Material.WOODEN_SWORD,
                plugin.lang().text("dashboard.punish.name"),
                List.of(
                        plugin.lang().text(punishing ? "dashboard.punish.state-on" : "dashboard.punish.state-off"),
                        plugin.lang().text("dashboard.punish.command",
                                Map.of("value", shorten(plugin.config().punishmentCommand()))),
                        "",
                        plugin.lang().text("dashboard.punish.line1"),
                        plugin.lang().text("dashboard.punish.line2"),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        boolean evidence = plugin.config().evidenceEnabled();
        set(SLOT_EVIDENCE, item(evidence ? Material.WRITABLE_BOOK : Material.PAPER,
                plugin.lang().text("dashboard.evidence.name"),
                List.of(
                        plugin.lang().text(evidence ? "dashboard.evidence.state-on" : "dashboard.evidence.state-off",
                                Map.of("value", Integer.toString(plugin.config().evidenceSeconds()))),
                        "",
                        plugin.lang().text("dashboard.evidence.line1"),
                        plugin.lang().text("dashboard.evidence.line2",
                                Map.of("value", plugin.config().evidenceLabel())),
                        plugin.lang().text("dashboard.evidence.line3",
                                Map.of("value", Msg.round(plugin.config().cancelBelow(), 1))),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        var alertSoundName = plugin.config().alertSoundName();
        set(SLOT_ALERT_SOUND, item(Material.BELL,
                plugin.lang().text("dashboard.alertsound.name"),
                List.of(
                        plugin.lang().text("dashboard.alertsound.current", Map.of("value", alertSoundName)),
                        plugin.lang().text("dashboard.alertsound.params", Map.of(
                                "volume", Msg.round(plugin.config().alertSoundVolume(), 1),
                                "pitch", Msg.round(plugin.config().alertSoundPitch(), 1))),
                        "",
                        plugin.lang().text("dashboard.alertsound.line1"),
                        plugin.lang().text("dashboard.alertsound.line2"),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        boolean allowed = viewer.hasPermission("yuppyai.alwaysprob");
        boolean alwaysProb = plugin.displays().isAlwaysProb(viewer.getUniqueId());
        set(SLOT_ALWAYS_PROB, item(allowed
                        ? (alwaysProb ? Material.ENDER_EYE : Material.ENDER_EYE)
                        : Material.BARRIER,
                plugin.lang().text("dashboard.alwaysprob.name"),
                List.of(
                        allowed
                                ? plugin.lang().text(alwaysProb ? "dashboard.alwaysprob.state-on" : "dashboard.alwaysprob.state-off")
                                : plugin.lang().text("shared.no-permission"),
                        "",
                        allowed ? plugin.lang().text("dashboard.alwaysprob.line1") : plugin.lang().text("shared.no-permission"),
                        allowed ? plugin.lang().text("dashboard.alwaysprob.line2") : "",
                        "",
                        allowed ? plugin.lang().text("shared.click-toggle") : "")));

        KickAnimation anim = plugin.config().kickAnimation();
        Material animMat = switch (anim) {
            case EXPLODE -> Material.TNT;
            case LIGHTNING -> safeMaterial("LIGHTNING_ROD", "GOLD_INGOT");
            case FIREWORK -> Material.FIREWORK_ROCKET;
            case WITHER -> Material.WITHER_SKELETON_SKULL;
            default -> Material.BARRIER;
        };
        set(SLOT_KICK_ANIM, item(animMat,
                plugin.lang().text("dashboard.kickanim.name"),
                List.of(
                        plugin.lang().text("dashboard.kickanim.current",
                                Map.of("value", plugin.lang().text("kickanim." + anim.name().toLowerCase()))),
                        "",
                        plugin.lang().text("dashboard.kickanim.line1"),
                        plugin.lang().text("dashboard.kickanim.line2"),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        set(SLOT_REFRESH, item(Material.CLOCK, plugin.lang().text("shared.refresh"),
                List.of(plugin.lang().text("dashboard.refresh.lore"))));
    }

    private void toggle(String path, boolean fallback) {
        plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path, fallback));
        plugin.saveConfig();
        plugin.reloadEverything();
        refresh();
    }

    private void cycleAlertSound() {
        String current = plugin.config().alertSoundName();
        int index = 0;
        for (int i = 0; i < ALERT_SOUNDS.length; i++) {
            if (ALERT_SOUNDS[i].equalsIgnoreCase(current)) {
                index = (i + 1) % ALERT_SOUNDS.length;
                break;
            }
        }
        plugin.getConfig().set("buffer.alert-sound", ALERT_SOUNDS[index]);
        plugin.saveConfig();
        plugin.reloadEverything();
        refresh();
        viewer.playSound(viewer.getLocation(), plugin.config().alertSound(),
                plugin.config().alertSoundVolume(), plugin.config().alertSoundPitch());
    }

    private void cycleKickAnimation() {
        KickAnimation next = plugin.config().kickAnimation().next();
        plugin.getConfig().set("punishment.kick-animation", next.name());
        plugin.saveConfig();
        plugin.reloadEverything();
        refresh();
    }

    private static Material safeMaterial(String... names) {
        for (String name : names) {
            try { return Material.valueOf(name); } catch (Exception ignored) {}
        }
        return Material.GOLD_INGOT;
    }

    private String shorten(String command) {
        if (command == null || command.isBlank()) {
            return "none set";
        }
        return command.length() <= 32 ? command : command.substring(0, 29) + "...";
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        switch (event.getSlot()) {
            case SLOT_AUTOMOD -> toggle("automod.enabled", true);
            case SLOT_PUNISH -> toggle("punishment.enabled", false);
            case SLOT_EVIDENCE -> toggle("punishment.evidence.enabled", true);
            case SLOT_ALERT_SOUND -> cycleAlertSound();
            case SLOT_KICK_ANIM -> cycleKickAnimation();
            case SLOT_THEME -> {
                var next = plugin.theme().cycle();
                viewer.sendMessage(plugin.config().prefix()
                        + plugin.lang().text("dashboard.theme.changed",
                        Map.of("value", plugin.lang().text("theme." + next.id()))));
                refresh();
            }
            case SLOT_PLAYERS -> new PlayersMenu(plugin, viewer).open();
            case SLOT_ALWAYS_PROB -> {
                if (!viewer.hasPermission("yuppyai.alwaysprob")) {
                    return;
                }
                if (plugin.displays().isAlwaysProb(viewer.getUniqueId())) {
                    plugin.displays().disableAlwaysProb(viewer);
                } else {
                    plugin.displays().enableAlwaysProb(viewer);
                }
                refresh();
            }
            case SLOT_REFRESH -> refresh();
            default -> {
            }
        }
    }
}
