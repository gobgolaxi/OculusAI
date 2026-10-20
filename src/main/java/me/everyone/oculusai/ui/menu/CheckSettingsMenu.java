package me.everyone.oculusai.ui.menu;

import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.check.Check;
import me.everyone.oculusai.ui.gui.Menu;
import me.everyone.oculusai.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Settings for one registered check.
 *
 * <p>Number rows use left click to increase and right click to decrease;
 * shift multiplies the step by five. Boolean rows toggle with left click.
 * Every row writes straight to config.yml and reloads the plugin, matching the
 * rest of the dashboard. */
public final class CheckSettingsMenu extends Menu {

    private static final int SLOT_HEADER = 4;
    private static final int SLOT_BACK = 40;
    private static final int[] OPTION_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private record Option(String key, Material material, String path, boolean toggle,
                          double fallback, double min, double max, double step,
                          boolean integer, boolean millis) {

        static Option toggle(String key, Material material, String path, boolean fallback) {
            return new Option(key, material, path, true, fallback ? 1.0D : 0.0D,
                    0.0D, 1.0D, 1.0D, true, false);
        }

        static Option number(String key, Material material, String path, double fallback,
                             double min, double max, double step, boolean integer) {
            return new Option(key, material, path, false, fallback, min, max, step,
                    integer, false);
        }

        static Option millis(String key, Material material, String path, double fallback,
                             double min, double max, double step) {
            return new Option(key, material, path, false, fallback, min, max, step,
                    true, true);
        }
    }

    private final String checkId;

    public CheckSettingsMenu(OculusAI plugin, Player viewer, String checkId) {
        super(plugin, viewer);
        this.checkId = checkId == null ? "" : checkId.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    protected String title() {
        return plugin.theme().title(plugin.lang().text("check-settings.title",
                Map.of("check", plugin.lang().textOr("check.name." + checkId, checkId))));
    }

    @Override
    protected int size() {
        return 45;
    }

    @Override
    protected void build() {
        fillBorder(item(Material.BLACK_STAINED_GLASS_PANE, " ", (List<String>) null));

        Check check = plugin.checks().find(checkId);
        String name = plugin.lang().textOr("check.name." + checkId, checkId);
        boolean enabled = check != null && plugin.getConfig().getBoolean(check.enabledPath(), true);
        set(SLOT_HEADER, item(icon(checkId), name,
                List.of(
                        plugin.lang().text(enabled
                                ? "check-settings.state-on" : "check-settings.state-off"),
                        plugin.lang().textOr("check.desc." + checkId,
                                plugin.lang().text("check-settings.no-description")),
                        "",
                        plugin.lang().text("check-settings.header-hint"))));

        List<Option> options = options();
        for (int i = 0; i < options.size() && i < OPTION_SLOTS.length; i++) {
            Option option = options.get(i);
            button(OPTION_SLOTS[i], optionItem(option), click -> change(option, click));
        }

        button(SLOT_BACK, item(Material.ARROW, plugin.lang().text("check-settings.back"),
                List.of(plugin.lang().text("check-settings.back-hint"))),
                () -> new ChecksMenu(plugin, viewer).open());
    }

    private List<Option> options() {
        return switch (checkId) {
            case "aim" -> List.of(
                    Option.toggle("enabled", Material.LEVER, "checks.aim.enabled", true),
                    Option.toggle("test-only", Material.LEVER, "checks.aim.test-only", false),
                    Option.number("smooth", Material.HOPPER, "checks.aim.smooth-windows",
                            plugin.config().checkAimSmoothWindows(), 1.0D, 15.0D, 1.0D, true),
                    Option.number("alert-at", Material.BELL, "buffer.alert-at",
                            plugin.config().alertAt(), 0.0D, plugin.config().bufferMax(),
                            0.1D, false),
                    Option.number("punish-at", Material.IRON_SWORD, "buffer.punish-at",
                            plugin.config().punishAt(), 0.0D, plugin.config().bufferMax(),
                            0.1D, false),
                    Option.number("buffer-max", Material.CHEST, "buffer.max",
                            plugin.config().bufferMax(), 0.1D, 50.0D, 0.5D, false),
                    Option.number("gain", Material.REDSTONE, "buffer.gain",
                            plugin.config().gain(), 0.1D, 10.0D, 0.1D, false),
                    Option.number("decay", Material.GUNPOWDER, "buffer.decay",
                            plugin.config().decay(), 0.1D, 10.0D, 0.1D, false),
                    Option.number("confirmations", Material.REPEATER, "checks.aim.confirmations",
                            plugin.config().checkAimConfirmations(), 1.0D, 50.0D, 1.0D, true),
                    Option.millis("cooldown-ms", Material.CLOCK, "checks.aim.cooldown-ms",
                            plugin.config().checkAimCooldownMs(), 0.0D, 600_000.0D, 1_000.0D));
            case "wall" -> List.of(
                    Option.toggle("enabled", Material.LEVER, "checks.wall.enabled", true),
                    Option.toggle("test-only", Material.LEVER, "checks.wall.test-only", false),
                    Option.number("max-distance", Material.IRON_DOOR, "checks.wall.max-distance",
                            plugin.config().wallCheckMaxDistance(), 0.5D, 20.0D, 0.5D, false),
                    Option.millis("cooldown-ms", Material.CLOCK, "checks.wall.cooldown-ms",
                            plugin.config().checkWallCooldownMs(), 0.0D, 600_000.0D, 1_000.0D));
            case "hitbox" -> List.of(
                    Option.toggle("enabled", Material.LEVER, "checks.hitbox.enabled", true),
                    Option.toggle("test-only", Material.LEVER, "checks.hitbox.test-only", false),
                    Option.number("max-width", Material.ARMOR_STAND, "checks.hitbox.max-width",
                            plugin.config().hitboxCheckMaxWidth(), 0.1D, 5.0D, 0.1D, false),
                    Option.number("max-height", Material.ARMOR_STAND, "checks.hitbox.max-height",
                            plugin.config().hitboxCheckMaxHeight(), 0.1D, 10.0D, 0.1D, false),
                    Option.millis("cooldown-ms", Material.CLOCK, "checks.hitbox.cooldown-ms",
                            plugin.config().checkHitboxCooldownMs(), 0.0D, 600_000.0D, 1_000.0D));
            case "nolookhit" -> List.of(
                    Option.toggle("enabled", Material.LEVER, "checks.nolookhit.enabled", true),
                    Option.toggle("test-only", Material.LEVER, "checks.nolookhit.test-only", false),
                    Option.number("max-distance", Material.TARGET,
                            "checks.nolookhit.max-distance",
                            plugin.config().noLookHitMaxDistance(), 0.5D, 20.0D, 0.5D, false),
                    Option.number("hitbox-margin", Material.TARGET,
                            "checks.nolookhit.hitbox-margin",
                            plugin.config().noLookHitHitboxMargin(), 0.0D, 0.5D, 0.1D, false),
                    Option.number("max-angle", Material.COMPASS,
                            "checks.nolookhit.max-angle",
                            plugin.config().noLookHitMaxAngle(), 0.0D, 90.0D, 1.0D, true),
                    Option.millis("cooldown-ms", Material.CLOCK,
                            "checks.nolookhit.cooldown-ms",
                            plugin.config().checkNoLookHitCooldownMs(),
                            0.0D, 600_000.0D, 1_000.0D));
            default -> List.of(
                    Option.toggle("enabled", Material.LEVER,
                            "checks." + checkId + ".enabled", true));
        };
    }

    private ItemStack optionItem(Option option) {
        return item(option.material(), plugin.lang().text(nameKey(option)),
                List.of(
                        plugin.lang().text("check-settings.value",
                                Map.of("value", display(option))),
                        "",
                        plugin.lang().text(hintKey(option)),
                        plugin.lang().text(option.toggle()
                                ? "check-settings.toggle-hint" : "check-settings.number-hint")));
    }

    private void change(Option option, Click click) {
        String path = option.path();
        if (option.toggle()) {
            if (click.right()) {
                return;
            }
            plugin.getConfig().set(path,
                    !plugin.getConfig().getBoolean(path, option.fallback() >= 0.5D));
        } else {
            double current = plugin.getConfig().getDouble(path, option.fallback());
            double step = option.step() * (click.shift() ? 5.0D : 1.0D);
            double next = click.right() ? current - step : current + step;
            next = Math.max(option.min(), Math.min(option.max(), next));
            if (option.integer()) {
                next = Math.rint(next);
            } else {
                next = Math.round(next * 10.0D) / 10.0D;
            }
            plugin.getConfig().set(path, next);
        }
        plugin.saveConfig();
        plugin.reloadEverything();
        refresh();
    }

    private String display(Option option) {
        if (option.toggle()) {
            return plugin.lang().text(plugin.getConfig().getBoolean(option.path(),
                    option.fallback() >= 0.5D)
                    ? "check-settings.on" : "check-settings.off");
        }
        double value = plugin.getConfig().getDouble(option.path(), option.fallback());
        if (option.millis()) {
            return Msg.round(value / 1000.0D, 1) + " "
                    + plugin.lang().text("check-settings.seconds");
        }
        return option.integer()
                ? Long.toString(Math.round(value))
                : Msg.round(value, 1);
    }

    private String nameKey(Option option) {
        return shared(option) ? "check-settings.shared." + option.key() + ".name"
                : "check-settings." + checkId + "." + option.key() + ".name";
    }

    private String hintKey(Option option) {
        return shared(option) ? "check-settings.shared." + option.key() + ".hint"
                : "check-settings." + checkId + "." + option.key() + ".hint";
    }

    private boolean shared(Option option) {
        return switch (option.key()) {
            case "enabled", "test-only", "confirmations", "cooldown-ms" -> true;
            default -> false;
        };
    }

    private Material icon(String id) {
        return switch (id) {
            case "wall" -> Material.IRON_DOOR;
            case "aim" -> Material.ENDER_EYE;
            case "hitbox" -> Material.ARMOR_STAND;
            case "nolookhit" -> Material.TARGET;
            default -> Material.PAPER;
        };
    }
}
