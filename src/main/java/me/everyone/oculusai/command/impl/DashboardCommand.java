package me.everyone.oculusai.command.impl;

import java.util.List;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DashboardCommand extends SubCommand {

    public DashboardCommand(OculusAI plugin) {
        super(plugin, "dashboard", List.of("gui", "menu"), "oculusai.command",
                "/oai dashboard", "Open the moderation dashboard.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.menus().openDashboard((Player) sender);
    }
}
