package me.everyone.oculusai.command.sub;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.player.Bypass;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Toggles the analysis exemption for one player.
 *
 * <p>Works for offline players too: the list stores UUIDs, so the command is
 * useful before the player joins rather than only after somebody notices them
 * in a fight. */
public final class BypassCommand extends SubCommand {

    public BypassCommand(OculusAI plugin) {
        super(plugin, "bypass", List.of("ignore"), "oculusai.command",
                "/oai bypass <player>",
                "Turn the anticheat on or off for one player.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            replyKey(sender, "bypass.usage", Map.of("value", usageText()));
            return;
        }

        String name;
        UUID uuid;
        Player online = plugin.getServer().getPlayerExact(args[0]);
        if (online != null) {
            name = online.getName();
            uuid = online.getUniqueId();
        } else {
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(args[0]);
            if (!offline.hasPlayedBefore()) {
                replyKey(sender, "bypass.unknown");
                return;
            }
            name = offline.getName() == null ? args[0] : offline.getName();
            uuid = offline.getUniqueId();
        }

        Bypass.Toggle toggle = plugin.bypass().toggle(uuid);
        replyKey(sender, toggle.enabled() ? "bypass.enabled" : "bypass.disabled",
                Map.of("player", name));
        if (!toggle.saved()) {
            replyKey(sender, "bypass.save-failed");
        }
        if (!toggle.enabled() && online != null && online.hasPermission("oculusai.bypass")) {
            replyKey(sender, "bypass.permission", Map.of("player", name));
        }
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? onlineNames(args[0]) : List.of();
    }
}
