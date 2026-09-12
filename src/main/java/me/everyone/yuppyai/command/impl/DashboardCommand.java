package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class DashboardCommand extends SubCommand {

    public DashboardCommand(YuppyAI plugin) {
        super(plugin, "dashboard", List.of("gui", "menu"), "yuppyai.command",
                "/yai dashboard", "Open the moderation dashboard.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.menus().openDashboard((Player) sender);
    }
}
