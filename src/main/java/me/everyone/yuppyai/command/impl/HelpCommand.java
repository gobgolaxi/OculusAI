package me.everyone.yuppyai.command.impl;

import java.util.List;
import java.util.Map;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.manager.CommandManager;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;

public final class HelpCommand extends SubCommand {

    private final CommandManager commands;

    public HelpCommand(YuppyAI plugin, CommandManager commands) {
        super(plugin, "help", List.of("?"), null, "/yai help", "Show this list.", false);
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
