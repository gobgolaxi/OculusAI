package me.everyone.oculusai.command.sub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens the check settings and lets an admin toggle them from chat. */
public final class ChecksCommand extends SubCommand {

    private static final List<String> KEYS =
            List.of("analysis", "checks", "wall", "aim", "hitbox", "punish");
    private static final List<String> OPTIONS =
            List.of("status", "on", "off", "analysis", "checks", "wall", "aim",
                    "hitbox", "punish");

    public ChecksCommand(OculusAI plugin) {
        super(plugin, "checks", List.of("check", "settings"), "oculusai.command",
                "/oai checks [on|off|status|analysis|checks|wall|aim|hitbox|punish]",
                "Open check settings or toggle checks.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.menus().openChecks(player);
            } else {
                status(sender);
            }
            return;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("status")) {
            status(sender);
            return;
        }

        if (first.equals("on") || first.equals("off")) {
            apply(sender, "analysis", first.equals("on"));
            return;
        }

        if (!KEYS.contains(first) || args.length < 2) {
            replyKey(sender, "checks.usage", Map.of("value", usageText()));
            return;
        }

        String state = args[1].toLowerCase(Locale.ROOT);
        if (!state.equals("on") && !state.equals("off")) {
            replyKey(sender, "checks.usage", Map.of("value", usageText()));
            return;
        }
        apply(sender, first, state.equals("on"));
    }

    private void apply(CommandSender sender, String key, boolean value) {
        plugin.getConfig().set(path(key), value);
        plugin.saveConfig();
        plugin.reloadEverything();
        replyKey(sender, value ? "checks.enabled" : "checks.disabled",
                Map.of("name", plugin.lang().text("checks.name." + key)));
    }

    private String path(String key) {
        return switch (key) {
            case "analysis" -> "analysis.enabled";
            case "checks" -> "checks.enabled";
            case "wall" -> "checks.wall.enabled";
            case "aim" -> "checks.aim.enabled";
            case "hitbox" -> "checks.hitbox.enabled";
            case "punish" -> "punishment.enabled";
            default -> "analysis.enabled";
        };
    }

    private void status(CommandSender sender) {
        replyKey(sender, "checks.status", Map.of(
                "analysis", state(plugin.config().analysisEnabled()),
                "checks", state(plugin.config().checksEnabled()),
                "wall", state(plugin.config().wallCheckEnabled()),
                "aim", state(plugin.config().checkAimEnabled()),
                "hitbox", state(plugin.config().hitboxCheckEnabled()),
                "punish", state(plugin.config().punishmentEnabled())));
    }

    private String state(boolean value) {
        return plugin.lang().text(value ? "checks.state.enabled" : "checks.state.disabled");
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            for (String option : OPTIONS) {
                if (option.startsWith(prefix)) {
                    options.add(option);
                }
            }
            return options;
        }
        if (args.length == 2 && KEYS.contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("on", "off").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
