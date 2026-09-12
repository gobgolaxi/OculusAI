package me.everyone.yuppyai.manager;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.command.impl.DashboardCommand;
import me.everyone.yuppyai.command.impl.DataCommand;
import me.everyone.yuppyai.command.impl.ForgetCommand;
import me.everyone.yuppyai.command.impl.HelpCommand;
import me.everyone.yuppyai.command.impl.JournalCommand;
import me.everyone.yuppyai.command.impl.NpcCommand;
import me.everyone.yuppyai.command.impl.PlayerCommand;
import me.everyone.yuppyai.command.impl.ProbCommand;
import me.everyone.yuppyai.command.impl.ReloadCommand;
import me.everyone.yuppyai.command.impl.VanishCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class CommandManager implements Manager, CommandExecutor, TabCompleter {

    private final YuppyAI plugin;
    private final List<SubCommand> subCommands = new ArrayList<>();

    public CommandManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        subCommands.clear();
        subCommands.add(new HelpCommand(plugin, this));
        subCommands.add(new JournalCommand(plugin));
        subCommands.add(new ProbCommand(plugin));
        subCommands.add(new PlayerCommand(plugin));
        subCommands.add(new VanishCommand(plugin));
        subCommands.add(new ForgetCommand(plugin));
        subCommands.add(new DataCommand(plugin));
        subCommands.add(new NpcCommand(plugin));
        subCommands.add(new DashboardCommand(plugin));
        subCommands.add(new ReloadCommand(plugin));

        PluginCommand command = plugin.getCommand("yuppyai");
        if (command == null) {
            plugin.getLogger().severe("/yuppyai is missing from plugin.yml");
            return;
        }
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    public List<SubCommand> subCommands() {
        return subCommands;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("yuppyai.command")) {
            sender.sendMessage(plugin.config().prefix() + plugin.lang().text("command.no-permission"));
            return true;
        }
        if (args.length == 0) {
            find("help").execute(sender, new String[0]);
            return true;
        }

        SubCommand subCommand = find(args[0]);
        if (subCommand == null) {
            sender.sendMessage(plugin.config().prefix() + plugin.lang().text("command.unknown",
                    java.util.Map.of("label", label)));
            return true;
        }
        if (subCommand.permission() != null && !sender.hasPermission(subCommand.permission())) {
            sender.sendMessage(plugin.config().prefix() + plugin.lang().text("command.no-permission"));
            return true;
        }
        if (subCommand.playerOnly() && !(sender instanceof Player)) {
            sender.sendMessage(plugin.config().prefix() + plugin.lang().text("command.players-only"));
            return true;
        }

        subCommand.execute(sender, Arrays.copyOfRange(args, 1, args.length));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("yuppyai.command")) {
            return List.of();
        }
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (SubCommand subCommand : subCommands) {
                if (subCommand.permission() != null && !sender.hasPermission(subCommand.permission())) {
                    continue;
                }
                if (subCommand.name().startsWith(partial)) {
                    names.add(subCommand.name());
                }
            }
            return names;
        }

        SubCommand subCommand = find(args[0]);
        if (subCommand == null
                || (subCommand.permission() != null && !sender.hasPermission(subCommand.permission()))) {
            return List.of();
        }
        return subCommand.complete(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    private SubCommand find(String input) {
        for (SubCommand subCommand : subCommands) {
            if (subCommand.matches(input)) {
                return subCommand;
            }
        }
        return null;
    }
}
