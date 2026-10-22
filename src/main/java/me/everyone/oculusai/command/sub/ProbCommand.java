package me.everyone.oculusai.command.sub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ProbCommand extends SubCommand {

    public ProbCommand(OculusAI plugin) {
        super(plugin, "prob", List.of("watch", "p"), "oculusai.alerts",
                "/oai prob <player|off>", "Watch a player's live aim reading.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player watcher = (Player) sender;

        if (args.length == 0 || args[0].equalsIgnoreCase("off")
                || args[0].equalsIgnoreCase("stop")) {
            // Silent, like the monitor: the hologram going away says it.
            plugin.displays().stop(watcher);
            return;
        }

        PlayerData target = plugin.data().byName(args[0]);
        if (target == null) {
            replyKey(sender, "shared.player-offline");
            return;
        }

        plugin.displays().watch(watcher, target);
        replyKey(sender, "prob.watching", Map.of(
                "player", target.name(),
                "buffer", Msg.round(target.buffer(), 1),
                "probability", Msg.percent(shownProbability(target)),
                "windows", Integer.toString(target.windowsAnalysed())));
        if (target.windowsAnalysed() == 0) {
            replyKey(sender, "prob.no-data");
        }
    }

    /** The model's raw answer when /oai math is off, the smoothed one that
     *  feeds the buffer otherwise. The buffer number is unaffected. */
    private double shownProbability(PlayerData target) {
        return target.displayProbability(!plugin.detection().mathEnabled());
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(onlineNames(args[0]));
        if ("off".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            options.add("off");
        }
        return options;
    }
}
