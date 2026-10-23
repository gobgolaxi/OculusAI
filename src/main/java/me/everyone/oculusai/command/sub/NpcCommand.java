package me.everyone.oculusai.command.sub;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.npc.NpcOptions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class NpcCommand extends SubCommand {

    public NpcCommand(OculusAI plugin) {
        super(plugin, "npc", List.of("dummy", "sparring"), "oculusai.npc",
                "/oai npc [count|clear] [hp:<n>] [still] [mortal]",
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
            if (arg.toLowerCase(Locale.ROOT).startsWith("hp:")) {
                try {
                    health = Double.parseDouble(arg.substring(3));
                } catch (NumberFormatException ignored) {
                    replyKey(sender, "npc.usage", Map.of("value", usageText()));
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
                replyKey(sender, "npc.usage", Map.of("value", usageText()));
                return;
            }
        }

        NpcOptions options = new NpcOptions(health, stationary, invulnerable);
        int spawned = plugin.testServer().spawnDummies(player, requested, options);
        if (spawned == 0) {
            replyKey(sender, plugin.npcs().supported() ? "npc.full" : "npc.unsupported");
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
