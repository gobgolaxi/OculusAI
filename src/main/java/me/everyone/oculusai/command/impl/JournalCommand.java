package me.everyone.oculusai.command.impl;

import java.util.List;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class JournalCommand extends SubCommand {

    public JournalCommand(OculusAI plugin) {
        super(plugin, "journal", List.of("suspects", "watchlist"), "oculusai.journal",
                "/oai journal", "Open the live suspicion journal.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.menus().openJournal((Player) sender);
    }
}
