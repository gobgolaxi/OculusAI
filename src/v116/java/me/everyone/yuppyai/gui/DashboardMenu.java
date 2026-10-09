package me.everyone.yuppyai.gui;

import java.util.List;
import java.util.Map;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.manager.TestServerManager.DummyOptions;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

public final class DashboardMenu extends Menu {

    private static final int SLOT_STATUS = 10;
    private static final int SLOT_PLAYERS = 12;
    private static final int SLOT_JOURNAL = 14;
    private static final int SLOT_DUMMY = 16;

    private static final int SLOT_PUNISH = 28;
    private static final int SLOT_EVIDENCE = 30;
    private static final int SLOT_ALERT_SOUND = 32;
    private static final int SLOT_ALWAYS_PROB = 34;

    private static final int SLOT_THEME = 48;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_CLOSE = 50;

    private static final String[] ALERT_SOUNDS = {
            "BLOCK_NOTE_BLOCK_BELL",
            "ENTITY_EXPERIENCE_ORB_PICKUP",
            "BLOCK_ANVIL_PLACE",
            "UI_BUTTON_CLICK"
    };

    public DashboardMenu(YuppyAI plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("dashboard.title"));
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void build() {
        ItemStack edge = item(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        fillBorder(edge);
        ItemStack divider = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int slot = 19; slot <= 25; slot++) {
            set(slot, divider);
        }
        for (int slot = 46; slot <= 47; slot++) {
            set(slot, edge);
        }
        for (int slot = 51; slot <= 52; slot++) {
            set(slot, edge);
        }

        boolean up = plugin.api().reachable();
        set(SLOT_STATUS, item(up ? Material.LIME_DYE : Material.RED_DYE,
                plugin.lang().text(up ? "dashboard.service.up" : "dashboard.service.down"),
                List.of(Msg.parse("<dark_gray>" + plugin.config().apiUrl()))));

        set(SLOT_PLAYERS, item(Material.PLAYER_HEAD, plugin.lang().text("dashboard.players.name"),
                List.of(
                        plugin.lang().text("dashboard.players.online",
                                Map.of("value", Integer.toString(plugin.data().size()))),
                        "",
                        plugin.lang().text("dashboard.players.click"))));

        set(SLOT_JOURNAL, item(Material.WRITABLE_BOOK, plugin.lang().text("dashboard.journal.name"),
                List.of(plugin.lang().text("dashboard.journal.click"))));

        set(SLOT_DUMMY, item(Material.ARMOR_STAND, plugin.lang().text("dashboard.dummy.name"),
                List.of(
                        plugin.lang().text("dashboard.dummy.count",
                                Map.of("value", Integer.toString(plugin.npcs().count()))),
                        "",
                        plugin.lang().text("dashboard.dummy.click"))));

        boolean punishing = plugin.config().punishmentEnabled();
        set(SLOT_PUNISH, item(punishing ? Material.IRON_SWORD : Material.WOODEN_SWORD,
                plugin.lang().text("dashboard.punish.name"),
                List.of(
                        plugin.lang().text(punishing ? "dashboard.punish.state-on" : "dashboard.punish.state-off"),
                        plugin.lang().text("dashboard.punish.command",
                                Map.of("value", shorten(plugin.config().punishmentCommand()))),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        boolean evidence = plugin.config().evidenceEnabled();
        set(SLOT_EVIDENCE, item(evidence ? Material.WRITTEN_BOOK : Material.PAPER,
                plugin.lang().text("dashboard.evidence.name"),
                List.of(
                        plugin.lang().text(evidence ? "dashboard.evidence.state-on" : "dashboard.evidence.state-off",
                                Map.of("value", Integer.toString(plugin.config().evidenceSeconds()))),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        set(SLOT_ALERT_SOUND, item(Material.BELL, plugin.lang().text("dashboard.alertsound.name"),
                List.of(
                        plugin.lang().text("dashboard.alertsound.current",
                                Map.of("value", plugin.config().alertSoundName())),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        boolean allowed = viewer.hasPermission("yuppyai.alwaysprob");
        boolean alwaysProb = plugin.displays().isAlwaysProb(viewer.getUniqueId());
        set(SLOT_ALWAYS_PROB, item(allowed ? Material.ENDER_EYE : Material.BARRIER,
                plugin.lang().text("dashboard.alwaysprob.name"),
                List.of(
                        allowed
                                ? plugin.lang().text(alwaysProb
                                        ? "dashboard.alwaysprob.state-on" : "dashboard.alwaysprob.state-off")
                                : plugin.lang().text("shared.no-permission"),
                        "",
                        allowed ? plugin.lang().text("shared.click-toggle") : "")));

        var theme = plugin.theme().current();
        set(SLOT_THEME, item(Material.NETHER_STAR, plugin.lang().text("dashboard.theme.name"),
                List.of(
                        plugin.lang().text("dashboard.theme.current",
                                Map.of("value", plugin.lang().text("theme." + theme.id()))),
                        "",
                        plugin.lang().text("dashboard.theme.click"))));

        set(SLOT_REFRESH, item(Material.CLOCK, plugin.lang().text("shared.refresh"), null));
        set(SLOT_CLOSE, item(Material.BARRIER, plugin.lang().text("shared.close"), null));
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

    private String shorten(String command) {
        if (command == null || command.isBlank()) {
            return plugin.lang().text("dashboard.punish.no-command");
        }
        return command.length() <= 32 ? command : command.substring(0, 29) + "...";
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        switch (event.getSlot()) {
            case SLOT_PUNISH -> toggle("punishment.enabled", false);
            case SLOT_EVIDENCE -> toggle("punishment.evidence.enabled", true);
            case SLOT_ALERT_SOUND -> cycleAlertSound();
            case SLOT_JOURNAL -> new JournalMenu(plugin, viewer).open();
            case SLOT_PLAYERS -> new PlayersMenu(plugin, viewer).open();
            case SLOT_DUMMY -> {
                if (!viewer.hasPermission("yuppyai.npc")) {
                    return;
                }
                viewer.closeInventory();
                int spawned = plugin.npcs().spawn(viewer, 1, DummyOptions.DEFAULT);
                viewer.sendMessage(plugin.config().prefix() + plugin.lang().text(
                        spawned > 0 ? "npc.spawned" : "npc.full",
                        Map.of("value", Integer.toString(spawned))));
            }
            case SLOT_THEME -> {
                var next = plugin.theme().cycle();
                viewer.sendMessage(plugin.config().prefix()
                        + plugin.lang().text("dashboard.theme.changed",
                        Map.of("value", plugin.lang().text("theme." + next.id()))));
                refresh();
            }
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
            case SLOT_CLOSE -> viewer.closeInventory();
            default -> {
            }
        }
    }
}
