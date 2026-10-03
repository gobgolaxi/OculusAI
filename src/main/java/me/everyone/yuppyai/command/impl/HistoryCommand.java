package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.data.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Opens the reading history of one player as a paginated menu.
 *
 * <p>With no name it shows the caller's own readings, which makes it easy to
 * sanity-check the detector on yourself instead of only on suspects.
 */
public final class HistoryCommand extends SubCommand {

    public HistoryCommand(YuppyAI plugin) {
        super(plugin, "history", List.of("hist", "log"), "yuppyai.journal",
                "/yai history [ник]", "Browse a player's detection history.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        PlayerData target;
        if (args.length == 0) {
            target = plugin.data().get((Player) sender);
            if (target == null) {
                replyKey(sender, "history.no-data");
                return;
            }
        } else {
            target = plugin.data().byName(args[0]);
            if (target == null) {
                replyKey(sender, "shared.player-offline");
                return;
            }
        }

        plugin.menus().openHistory((Player) sender, target);
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return onlineNames(args[0]);
    }
}