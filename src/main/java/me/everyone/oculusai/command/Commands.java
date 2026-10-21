package me.everyone.oculusai.command;


import me.everyone.oculusai.core.Service;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.command.sub.BypassCommand;
import me.everyone.oculusai.command.sub.ChecksCommand;
import me.everyone.oculusai.command.sub.ConnectCommand;
import me.everyone.oculusai.command.sub.DashboardCommand;
import me.everyone.oculusai.command.sub.DataCommand;
import me.everyone.oculusai.command.sub.DiagCommand;
import me.everyone.oculusai.command.sub.ForgetCommand;
import me.everyone.oculusai.command.sub.GuideCommand;
import me.everyone.oculusai.command.sub.HelpCommand;
import me.everyone.oculusai.command.sub.HistoryCommand;
import me.everyone.oculusai.command.sub.JournalCommand;
import me.everyone.oculusai.command.sub.MathCommand;
import me.everyone.oculusai.command.sub.MonitorCommand;
import me.everyone.oculusai.command.sub.NpcCommand;
import me.everyone.oculusai.command.sub.PlayerCommand;
import me.everyone.oculusai.command.sub.ProbCommand;
import me.everyone.oculusai.command.sub.ReloadCommand;
import me.everyone.oculusai.command.sub.ReportCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class Commands implements Service, CommandExecutor, TabCompleter {

    private final OculusAI plugin;
    private final List<SubCommand> subCommands = new ArrayList<>();

    public Commands(OculusAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        subCommands.clear();
        subCommands.add(new HelpCommand(plugin, this));
        subCommands.add(new GuideCommand(plugin));
        subCommands.add(new JournalCommand(plugin));
        subCommands.add(new HistoryCommand(plugin));
        subCommands.add(new ProbCommand(plugin));
        subCommands.add(new MathCommand(plugin));
        subCommands.add(new BypassCommand(plugin));
        subCommands.add(new MonitorCommand(plugin));
        subCommands.add(new PlayerCommand(plugin));
        subCommands.add(new ForgetCommand(plugin));
        subCommands.add(new DataCommand(plugin));
        subCommands.add(new ReportCommand(plugin));
        subCommands.add(new NpcCommand(plugin));
        subCommands.add(new ConnectCommand(plugin));
        subCommands.add(new DashboardCommand(plugin));
        subCommands.add(new ChecksCommand(plugin));
        subCommands.add(new DiagCommand(plugin));
        subCommands.add(new ReloadCommand(plugin));

        PluginCommand command = plugin.getCommand("oculusai");
        if (command == null) {
            plugin.getLogger().severe("/oculusai is missing from plugin.yml");
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
        if (!sender.hasPermission("oculusai.command")) {
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
                    Map.of("label", label)));
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
        if (!sender.hasPermission("oculusai.command")) {
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
