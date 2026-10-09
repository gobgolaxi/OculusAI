package me.everyone.yuppyai.command.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.data.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
                UUID stored = plugin.history().resolve(args[0]);
                if (stored == null) {
                    replyKey(sender, "history.unknown-player",
                            Map.of("player", args[0]));
                    return;
                }
                String name = plugin.history().nameOf(stored);
                plugin.menus().openHistory((Player) sender, stored,
                        name == null ? args[0] : name);
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
        List<String> options = new ArrayList<>(onlineNames(args[0]));
        String prefix = args[0].toLowerCase(Locale.ROOT);
        for (UUID uuid : plugin.history().tracked()) {
            String name = plugin.history().nameOf(uuid);
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)
                    && !options.contains(name)) {
                options.add(name);
            }
        }
        return options;
    }
}
