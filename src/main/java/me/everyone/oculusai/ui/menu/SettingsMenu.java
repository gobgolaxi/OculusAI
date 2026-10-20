package me.everyone.oculusai.ui.menu;

import java.util.List;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.ui.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The settings screen opened by right-clicking the power switch. */
public final class SettingsMenu extends Menu {

    private static final int SLOT_ANALYSIS = 10;
    private static final int SLOT_CHECKS = 11;
    private static final int SLOT_TEST = 12;
    private static final int SLOT_WALL = 13;
    private static final int SLOT_AIM = 14;
    private static final int SLOT_PUNISH = 15;
    private static final int SLOT_BACK = 22;

    public SettingsMenu(OculusAI plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("settings.title"));
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void build() {
        ItemStack edge = item(Material.BLACK_STAINED_GLASS_PANE, " ", (List<String>) null);
        fillBorder(edge);

        toggleButton(SLOT_ANALYSIS, toggleItem(Material.COMPARATOR, "analysis",
                        plugin.config().analysisEnabled()),
                "analysis.enabled", true);
        toggleButton(SLOT_CHECKS, toggleItem(Material.SHIELD, "checks",
                        plugin.config().checksEnabled()),
                "checks.enabled", true);
        toggleButton(SLOT_TEST, toggleItem(Material.LEVER, "test",
                        plugin.config().checkTestMode()),
                "checks.test-mode", true);
        toggleButton(SLOT_WALL, toggleItem(Material.IRON_DOOR, "wall",
                        plugin.config().wallCheckEnabled()),
                "checks.wall.enabled", true);
        toggleButton(SLOT_AIM, toggleItem(Material.ENDER_EYE, "aim",
                        plugin.config().checkAimEnabled()),
                "checks.aim.enabled", true);
        toggleButton(SLOT_PUNISH, toggleItem(Material.IRON_SWORD, "punish",
                        plugin.config().punishmentEnabled()),
                "punishment.enabled", false);

        button(SLOT_BACK, item(Material.ARROW, plugin.lang().text("settings.back"),
                List.of(plugin.lang().text("settings.hint"))),
                () -> new DashboardMenu(plugin, viewer).open());
    }

    private ItemStack toggleItem(Material material, String key, boolean value) {
        return item(material, plugin.lang().text("settings." + key + ".name"),
                List.of(
                        plugin.lang().text("settings." + key + (value ? ".on" : ".off")),
                        "",
                        plugin.lang().text("settings." + key + ".hint"),
                        plugin.lang().text("shared.click-toggle")));
    }
}
