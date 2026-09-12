package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.manager.TestServerManager.DummyOptions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class NpcCommand extends SubCommand {

    public NpcCommand(YuppyAI plugin) {
        super(plugin, "npc", List.of("dummy", "sparring"), "yuppyai.npc",
                "/yai npc [count|clear] [hp:<n>] [still] [mortal]",
                "Spawn a sparring dummy that attacks you.", true);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;

        if (args.length > 0 && (args[0].equalsIgnoreCase("clear") || args[0].equalsIgnoreCase("off"))) {
            plugin.testServer().clearDummies();
            replyKey(sender, "npc.cleared");
            return;
        }

        int requested = 1;
        double health = -1.0D;
        boolean stationary = false;
        boolean invulnerable = true;

        for (String arg : args) {
            if (arg.toLowerCase(java.util.Locale.ROOT).startsWith("hp:")) {
                try {
                    health = Double.parseDouble(arg.substring(3));
                } catch (NumberFormatException ignored) {
                    replyKey(sender, "npc.usage", Map.of("value", usage()));
                    return;
                }
                continue;
            }
            if (arg.equalsIgnoreCase("still") || arg.equalsIgnoreCase("stationary")) {
                stationary = true;
                continue;
            }
            if (arg.equalsIgnoreCase("mortal") || arg.equalsIgnoreCase("vulnerable")) {
                invulnerable = false;
                continue;
            }
            try {
                requested = Integer.parseInt(arg);
            } catch (NumberFormatException ignored) {
                replyKey(sender, "npc.usage", Map.of("value", usage()));
                return;
            }
        }

        DummyOptions options = new DummyOptions(health, stationary, invulnerable);
        int spawned = plugin.testServer().spawnDummies(player, requested, options);
        if (spawned == 0) {
            replyKey(sender, "npc.full");
            return;
        }
        replyKey(sender, "npc.spawned", Map.of("value", Integer.toString(spawned)));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        return List.of("1", "3", "clear", "hp:50", "still", "mortal");
    }
}
