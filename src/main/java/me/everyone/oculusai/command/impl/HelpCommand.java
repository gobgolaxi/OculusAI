package me.everyone.oculusai.command.impl;

import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.manager.CommandManager;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;

public final class HelpCommand extends SubCommand {

    private final CommandManager commands;

    public HelpCommand(OculusAI plugin, CommandManager commands) {
        super(plugin, "help", List.of("?"), null, "/oai help", "Show this list.", false);
        this.commands = commands;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage(plugin.lang().text("help.divider"));
        sender.sendMessage(plugin.config().prefix() + plugin.lang().text("help.header", Map.of(
                "version", plugin.getDescription().getVersion(),
                "status", plugin.lang().text(plugin.api().reachable() ? "help.status.up" : "help.status.down"))));
        for (SubCommand command : commands.subCommands()) {
            if (command.permission() != null && !sender.hasPermission(command.permission())) {
                continue;
            }
            sender.sendMessage(Msg.parse("<aqua>" + escapeTags(command.usage())
                    + " <dark_gray>- <gray>"
                    + plugin.lang().textOr("help.command." + command.name(), command.description())));
        }
        sender.sendMessage(plugin.lang().text("help.divider"));
    }

    private static String escapeTags(String usage) {
        return usage.replace("<", "&lt;").replace(">", "&gt;");
    }
}
