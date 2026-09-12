package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class ProbCommand extends SubCommand {

    public ProbCommand(YuppyAI plugin) {
        super(plugin, "prob", List.of("watch", "p"), "yuppyai.alerts",
                "/yai prob <player|off>", "Watch a player's live aim reading.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player watcher = (Player) sender;

        if (args.length == 0 || args[0].equalsIgnoreCase("off")
                || args[0].equalsIgnoreCase("stop")) {
            boolean was = plugin.displays().isWatching(watcher.getUniqueId());
            plugin.displays().stop(watcher);
            replyKey(sender, was ? "prob.stopped" : "prob.not-watching");
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
                "probability", Msg.percent(target.probability()),
                "windows", Integer.toString(target.windowsAnalysed())));
        if (target.windowsAnalysed() == 0) {
            replyKey(sender, "prob.no-data");
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new java.util.ArrayList<>(onlineNames(args[0]));
        if ("off".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            options.add("off");
        }
        return options;
    }
}
