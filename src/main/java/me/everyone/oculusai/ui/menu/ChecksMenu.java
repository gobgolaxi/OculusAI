package me.everyone.oculusai.ui.menu;

import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.check.Check;
import me.everyone.oculusai.ui.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The list of every registered check.
 *
 * <p>Left click toggles a check, right click opens its own settings screen.
 * The list is read from {@link me.everyone.oculusai.check.Checks}, so adding a
 * check to the plugin makes it appear here without a second hard-coded menu
 * entry. */
public final class ChecksMenu extends Menu {

    private static final int[] SLOTS = {
            20, 21, 22, 23, 24,
            29, 30, 31, 32, 33,
            38, 39, 40, 41, 42
    };
    private static final int SLOT_HEADER = 4;
    private static final int SLOT_BACK = 44;

    public ChecksMenu(OculusAI plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("checks-menu.title"));
    }

    @Override
    protected int size() {
        return 45;
    }

    @Override
    protected void build() {
        fillBorder(item(Material.BLACK_STAINED_GLASS_PANE, " ", (List<String>) null));

        List<Check> checks = plugin.checks().all();
        set(SLOT_HEADER, item(Material.COMPARATOR, plugin.lang().text("checks-menu.header"),
                List.of(
                        plugin.lang().text("checks-menu.registered",
                                Map.of("value", Integer.toString(checks.size()))),
                        plugin.lang().text(plugin.config().analysisEnabled()
                                ? "checks-menu.analysis-on" : "checks-menu.analysis-off"),
                        "",
                        plugin.lang().text("checks-menu.hint"))));

        for (int i = 0; i < checks.size() && i < SLOTS.length; i++) {
            Check check = checks.get(i);
            boolean enabled = enabled(check);
            ItemStack icon = item(icon(check), plugin.lang().textOr(
                            "check.name." + check.id(), check.id()),
                    List.of(
                            plugin.lang().text(enabled
                                    ? "checks-menu.state-on" : "checks-menu.state-off"),
                            plugin.lang().textOr("check.desc." + check.id(),
                                    plugin.lang().text("checks-menu.no-description")),
                            "",
                            plugin.lang().text("checks-menu.left"),
                            plugin.lang().text("checks-menu.right")));
            toggle(SLOTS[i], icon,
                    () -> setEnabled(check, !enabled),
                    () -> new CheckSettingsMenu(plugin, viewer, check.id()).open());
        }

        if (checks.isEmpty()) {
            set(22, item(Material.BARRIER, plugin.lang().text("checks-menu.empty"),
                    List.of(plugin.lang().text("checks-menu.empty-hint"))));
        }

        button(SLOT_BACK, item(Material.ARROW, plugin.lang().text("checks-menu.back"),
                List.of(plugin.lang().text("checks-menu.back-hint"))),
                () -> new DashboardMenu(plugin, viewer).open());
    }

    private boolean enabled(Check check) {
        return plugin.getConfig().getBoolean(check.enabledPath(), true);
    }

    private void setEnabled(Check check, boolean value) {
        plugin.getConfig().set(check.enabledPath(), value);
        plugin.saveConfig();
        plugin.reloadEverything();
        refresh();
    }

    private Material icon(Check check) {
        return switch (check.id()) {
            case "wall" -> Material.IRON_DOOR;
            case "aim" -> Material.ENDER_EYE;
            default -> Material.PAPER;
        };
    }
}
