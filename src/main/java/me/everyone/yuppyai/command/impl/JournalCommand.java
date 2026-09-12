package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class JournalCommand extends SubCommand {

    public JournalCommand(YuppyAI plugin) {
        super(plugin, "journal", List.of("suspects", "watchlist"), "yuppyai.journal",
                "/yai journal", "Open the live suspicion journal.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.menus().openJournal((Player) sender);
    }
}
