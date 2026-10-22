package me.everyone.oculusai.command.sub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.punishment.Animations;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Picks the send-off the plugin plays when it punishes somebody itself.
 *
 * <p>The dashboard has a button for this too, but a button can only step to
 * the next effect, and stepping is a poor way to answer "what does wither
 * look like". Here an effect can be named outright, and a player naming one
 * sees it played on themselves before it is saved - which is the only honest
 * preview, and the reason the dashboard button does not try: an effect drawn
 * behind an open inventory cannot be seen. */
public final class AnimationsCommand extends SubCommand {

    public AnimationsCommand(OculusAI plugin) {
        super(plugin, "animations", List.of("animation", "anim"), "oculusai.command",
                "/oai animations [" + String.join("|", Animations.ALL) + "]",
                "Choose the effect played when the plugin punishes on its own.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        String current = plugin.config().punishmentAnimation();
        if (args.length < 2) {
            replyKey(sender, "animations.current",
                    Map.of("value", plugin.lang().textOr("kickanim." + current, current)));
            reply(sender, "<gray>" + plugin.lang().textOr("animations.list", "Available:")
                    + " <white>" + String.join("<dark_gray>, <white>", Animations.ALL));
            return;
        }

        String wanted = args[1].trim().toLowerCase(Locale.ROOT);
        if (!Animations.ALL.contains(wanted)) {
            replyKey(sender, "animations.unknown", Map.of("value", args[1]));
            return;
        }

        plugin.getConfig().set("punishment.animation", wanted);
        plugin.saveConfig();
        plugin.reloadEverything();
        replyKey(sender, "animations.set",
                Map.of("value", plugin.lang().textOr("kickanim." + wanted, wanted)));

        // Played on the asker, where they can see it, and only once the choice
        // is stored - so what they are shown is what the next cheater gets.
        // The same sequence the punishment uses, minus everything that is a
        // punishment: no freeze, no stripping, no kick. Being thrown into the
        // air is left in, because that is the effect.
        if (sender instanceof Player player && !wanted.equals(Animations.NONE)) {
            plugin.punishments().playShow(player, Animations.pick(wanted), null, () -> {
            });
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 2) {
            return List.of();
        }
        String partial = args[1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(Animations.ALL);
        options.removeIf(option -> !option.startsWith(partial));
        return options;
    }
}
