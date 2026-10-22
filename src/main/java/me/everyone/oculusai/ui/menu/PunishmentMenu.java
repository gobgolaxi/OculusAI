package me.everyone.oculusai.ui.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.punishment.Animations;
import me.everyone.oculusai.ui.gui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Everything that happens to a caught cheater, on one screen.
 *
 * <p>The dashboard used to carry a single button that stepped to the next
 * effect. Stepping is a poor way to choose from seven things and had nowhere
 * to put the rest of it, so the settings that belong together - whether to
 * punish at all, whether to strip, how many punishments earn a ban, and what
 * the send-off looks like - are here instead, each where it can be read
 * rather than discovered by clicking. */
public final class PunishmentMenu extends Menu {

    private static final int SLOT_HEADER = 4;

    /** One slot per effect, in the order {@link Animations#ALL} lists them. */
    private static final int[] ANIMATION_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private static final int SLOT_ENABLED = 29;
    private static final int SLOT_STRIP = 31;
    private static final int SLOT_BAN_AFTER = 33;
    private static final int SLOT_BACK = 40;

    public PunishmentMenu(OculusAI plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("punish-menu.title"));
    }

    @Override
    protected int size() {
        return 45;
    }

    @Override
    protected void build() {
        boolean enabled = plugin.config().punishmentEnabled();
        boolean strip = plugin.config().punishmentStrip();
        String current = plugin.config().punishmentAnimation();
        int banAfter = plugin.config().punishmentBanAfter();

        set(SLOT_HEADER, item(Material.IRON_SWORD, plugin.lang().text("punish-menu.header"),
                List.of(
                        plugin.lang().text(enabled
                                ? "punish-menu.state-on" : "punish-menu.state-off"),
                        plugin.lang().text("punish-menu.command",
                                Map.of("value", command())),
                        "",
                        plugin.lang().text("punish-menu.hint"))));

        for (int i = 0; i < Animations.ALL.size() && i < ANIMATION_SLOTS.length; i++) {
            String id = Animations.ALL.get(i);
            boolean chosen = id.equals(current);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.lang().text(chosen
                    ? "punish-menu.animation-chosen" : "punish-menu.animation-pick"));
            if (id.equals(Animations.RANDOM)) {
                lore.add("");
                lore.add(plugin.lang().text("punish-menu.random-note"));
            }
            // The strip toggle decides whether the inventory actually comes
            // out, so an effect promising flying items while it is off would
            // be promising something that does not happen.
            if (id.equals(Animations.ASCEND) && !strip) {
                lore.add("");
                lore.add(plugin.lang().text("punish-menu.ascend-needs-strip"));
            }
            ItemStack icon = item(icon(id),
                    plugin.lang().textOr("kickanim." + id, id), lore);
            int slot = ANIMATION_SLOTS[i];
            button(slot, icon, () -> choose(id));
        }

        button(SLOT_ENABLED, item(enabled ? Material.REDSTONE_TORCH : Material.LEVER,
                plugin.lang().text("punish-menu.enabled.name"),
                List.of(
                        plugin.lang().text(enabled
                                ? "punish-menu.state-on" : "punish-menu.state-off"),
                        "",
                        plugin.lang().text("shared.click-toggle"))),
                () -> flip("punishment.enabled", enabled));

        button(SLOT_STRIP, item(strip ? Material.HOPPER : Material.CHEST,
                plugin.lang().text("punish-menu.strip.name"),
                List.of(
                        plugin.lang().text(strip
                                ? "punish-menu.state-on" : "punish-menu.state-off"),
                        "",
                        plugin.lang().text("punish-menu.strip.line1"),
                        plugin.lang().text("punish-menu.strip.line2"),
                        "",
                        plugin.lang().text("shared.click-toggle"))),
                () -> flip("punishment.strip", strip));

        button(SLOT_BAN_AFTER, item(banAfter > 0 ? Material.BARRIER : Material.PAPER,
                plugin.lang().text("punish-menu.ban.name"),
                List.of(
                        banAfter > 0
                                ? plugin.lang().text("punish-menu.ban.after",
                                        Map.of("value", Integer.toString(banAfter)))
                                : plugin.lang().text("punish-menu.ban.off"),
                        plugin.lang().text("punish-menu.ban.with",
                                Map.of("value", banCommand())),
                        "",
                        plugin.lang().text("punish-menu.ban.left"),
                        plugin.lang().text("punish-menu.ban.right"),
                        plugin.lang().text("punish-menu.ban.shift"))),
                click -> {
                    int step = click.shift() ? 5 : 1;
                    // Never below zero, which is the off position, and never
                    // so high that the number stops meaning anything.
                    int next = Math.max(0, Math.min(100,
                            banAfter + (click.right() ? -step : step)));
                    plugin.getConfig().set("punishment.ban-after", next);
                    save();
                });

        button(SLOT_BACK, item(Material.ARROW, plugin.lang().text("shared.back"), null),
                () -> new DashboardMenu(plugin, viewer).open());
    }

    private void choose(String id) {
        plugin.getConfig().set("punishment.animation", id);
        save();
    }

    private void flip(String path, boolean current) {
        plugin.getConfig().set(path, !current);
        save();
    }

    private void save() {
        plugin.saveConfig();
        plugin.reloadEverything();
        refresh();
    }

    private String command() {
        String command = plugin.config().punishmentCommand();
        return command == null || command.isBlank()
                ? plugin.lang().text("punish-menu.command-none")
                : shorten(command);
    }

    private String banCommand() {
        String command = plugin.config().punishmentBanCommand();
        return command == null || command.isBlank()
                ? plugin.lang().text("punish-menu.ban.vanilla")
                : shorten(command);
    }

    private static String shorten(String command) {
        return command.length() <= 28 ? command : command.substring(0, 25) + "...";
    }

    /** Materials that exist on both generations: no lightning rod, which
     *  arrived in 1.17 and would be air on the 1.16 jar. */
    private static Material icon(String id) {
        return switch (id) {
            case Animations.ASCEND -> Material.FEATHER;
            case Animations.EXPLODE -> Material.TNT;
            case Animations.LIGHTNING -> Material.BLAZE_ROD;
            case Animations.FIREWORK -> Material.FIREWORK_ROCKET;
            case Animations.WITHER -> Material.WITHER_SKELETON_SKULL;
            case Animations.RANDOM -> Material.ENDER_PEARL;
            default -> Material.BARRIER;
        };
    }
}
