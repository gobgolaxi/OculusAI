package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class VanishCommand extends SubCommand {

    public VanishCommand(YuppyAI plugin) {
        super(plugin, "vanish", List.of("unwatch", "exit"), "yuppyai.vanish",
                "/yai vanish [player|off]", "Enter or leave hidden spectator observation.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0) {
            if (plugin.journal().isVanished(player.getUniqueId())) {
                leave(player);
                return;
            }
            plugin.journal().enterVanish(player);
            player.sendMessage(plugin.config().prefix() + plugin.lang().text("vanish.enter"));
            return;
        }

        if (args[0].equalsIgnoreCase("off")
                || args[0].equalsIgnoreCase("leave")
                || args[0].equalsIgnoreCase("exit")) {
            leave(player);
            return;
        }

        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(plugin.config().prefix() + plugin.lang().text("vanish.player-offline"));
            return;
        }
        plugin.journal().observe(player, target.getUniqueId());
        player.sendMessage(plugin.config().prefix() + plugin.lang().text("vanish.observe",
                Map.of("player", target.getName())));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new java.util.ArrayList<>(onlineNames(args[0]));
        if ("off".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            options.add("off");
        }
        return options;
    }

    private void leave(Player player) {
        boolean left = plugin.journal().leaveVanish(player);
        if (!left) {
            player.sendMessage(plugin.config().prefix() + plugin.lang().text("vanish.not-vanished"));
            return;
        }
        player.sendMessage(plugin.config().prefix() + plugin.lang().text("vanish.exit"));
    }
}
