package me.everyone.yuppyai.gui;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.manager.TestServerManager.DummyOptions;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DummyMenu extends Menu {

    private static final int SLOT_HP_MINUS = 11;
    private static final int SLOT_HP = 13;
    private static final int SLOT_HP_PLUS = 15;
    private static final int SLOT_STATIONARY = 20;
    private static final int SLOT_INVULNERABLE = 22;
    private static final int SLOT_REMOVE = 24;

    private static final double DEFAULT_HEALTH = 20.0D;
    private static final double MIN_HEALTH = 1.0D;
    private static final double MAX_HEALTH = 2000.0D;

    private final UUID dummyId;

    public DummyMenu(YuppyAI plugin, Player viewer, UUID dummyId) {
        super(plugin, viewer);
        this.dummyId = dummyId;
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("dummy.title"));
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void build() {
        DummyOptions options = plugin.testServer().optionsOf(dummyId);
        if (options == null) {
            viewer.closeInventory();
            viewer.sendMessage(plugin.config().prefix() + plugin.lang().text("dummy.gone"));
            return;
        }

        fillBorder(item(Material.GRAY_STAINED_GLASS_PANE, "", null));

        double health = options.health() > 0 ? options.health() : DEFAULT_HEALTH;
        String hpKey = options.health() > 0 ? "dummy.hp.current" : "dummy.hp.default";
        set(SLOT_HP_MINUS, item(Material.RED_DYE, plugin.lang().text("shared.click-toggle"), null));
        set(SLOT_HP, item(Material.PAPER, plugin.lang().text("dummy.hp.name"),
                List.of(
                        plugin.lang().text(hpKey, Map.of("value", trim(health))),
                        "",
                        plugin.lang().text("dummy.hp.line1"),
                        plugin.lang().text("dummy.hp.line2"))));
        set(SLOT_HP_PLUS, item(Material.LIME_DYE, plugin.lang().text("shared.click-toggle"), null));

        set(SLOT_STATIONARY, item(options.stationary() ? Material.BARRIER : Material.COMPASS,
                plugin.lang().text("dummy.stationary.name"),
                List.of(
                        plugin.lang().text(options.stationary()
                                ? "dummy.stationary.state-on" : "dummy.stationary.state-off"),
                        "",
                        plugin.lang().text("dummy.stationary.line1"),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        set(SLOT_INVULNERABLE, item(options.invulnerable() ? Material.TOTEM_OF_UNDYING : Material.IRON_SWORD,
                plugin.lang().text("dummy.invulnerable.name"),
                List.of(
                        plugin.lang().text(options.invulnerable()
                                ? "dummy.invulnerable.state-on" : "dummy.invulnerable.state-off"),
                        "",
                        plugin.lang().text("dummy.invulnerable.line1"),
                        "",
                        plugin.lang().text("shared.click-toggle"))));

        set(SLOT_REMOVE, item(Material.BARRIER, plugin.lang().text("dummy.remove.name"),
                List.of(plugin.lang().text("dummy.remove.line1"))));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        DummyOptions options = plugin.testServer().optionsOf(dummyId);
        if (options == null) {
            viewer.closeInventory();
            return;
        }

        double health = options.health() > 0 ? options.health() : DEFAULT_HEALTH;
        boolean shift = event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT;

        switch (event.getSlot()) {
            case SLOT_HP_MINUS -> {
                double next = clamp(health - (shift ? 10.0D : 1.0D));
                plugin.testServer().updateOptions(dummyId, new DummyOptions(next, options.stationary(), options.invulnerable()));
                refresh();
            }
            case SLOT_HP_PLUS -> {
                double next = clamp(health + (shift ? 10.0D : 1.0D));
                plugin.testServer().updateOptions(dummyId, new DummyOptions(next, options.stationary(), options.invulnerable()));
                refresh();
            }
            case SLOT_STATIONARY -> {
                plugin.testServer().updateOptions(dummyId,
                        new DummyOptions(options.health(), !options.stationary(), options.invulnerable()));
                refresh();
            }
            case SLOT_INVULNERABLE -> {
                plugin.testServer().updateOptions(dummyId,
                        new DummyOptions(options.health(), options.stationary(), !options.invulnerable()));
                refresh();
            }
            case SLOT_REMOVE -> {
                plugin.testServer().removeDummy(dummyId);
                viewer.closeInventory();
            }
            default -> {
            }
        }
    }

    private static double clamp(double value) {
        return Math.max(MIN_HEALTH, Math.min(MAX_HEALTH, value));
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
