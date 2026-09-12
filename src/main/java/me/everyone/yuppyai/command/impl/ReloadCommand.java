package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

public final class ReloadCommand extends SubCommand {

    public ReloadCommand(YuppyAI plugin) {
        super(plugin, "reload", List.of("rl"), "yuppyai.command",
                "/yai reload", "Re-read config.yml.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        long start = System.currentTimeMillis();
        plugin.reloadEverything();
        replyKey(sender, "reload.done",
                Map.of("value", Long.toString(System.currentTimeMillis() - start)));
    }
}
