package me.everyone.oculusai.command.sub;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Kick or ban by hand, through the anticheat's own send-off.
 *
 * <p>The point is the show. A server that punishes cheats with a visible
 * effect should not break the pattern the moment a human is the one deciding:
 * {@code /oai kick} and {@code /oai ban} throw the player up, spill their
 * inventory and remove them exactly like a detection does, so players cannot
 * tell the plugin's judgement from the staff's - which is the whole reason an
 * admin would use these instead of vanilla /kick and /ban. */
public final class PunishCommand extends SubCommand {

    private final boolean ban;

    private PunishCommand(OculusAI plugin, String name, List<String> aliases, boolean ban,
                          String description) {
        super(plugin, name, aliases, "oculusai.punish",
                "/oai " + name + " <player> [reason]", description, false);
        this.ban = ban;
    }

    public static PunishCommand kick(OculusAI plugin) {
        return new PunishCommand(plugin, "kick", List.of(), false,
                "Kick a player with the anticheat's send-off.");
    }

    public static PunishCommand ban(OculusAI plugin) {
        return new PunishCommand(plugin, "ban", List.of(), true,
                "Ban a player with the anticheat's send-off.");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            replyKey(sender, "manual.usage", Map.of("value", usageText()));
            return;
        }

        Player target = plugin.getServer().getPlayerExact(args[0]);
        if (target == null) {
            // Only online players: the send-off needs somebody to play it on.
            // An offline ban is vanilla /ban and already works.
            replyKey(sender, "shared.player-offline");
            return;
        }
        if (sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId())) {
            replyKey(sender, "manual.self");
            return;
        }

        // No reason typed means the one the plugin uses on its own, so a
        // server that worded punishment.kick-message gets that wording here
        // too rather than a second hardcoded string.
        String reason = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim()
                : plugin.config().punishmentKickMessage();

        if (!plugin.punishments().manual(target, ban, reason, sender.getName())) {
            replyKey(sender, "shared.player-offline");
            return;
        }
        replyKey(sender, ban ? "manual.banned" : "manual.kicked", Map.of(
                "player", target.getName(),
                "reason", reason));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? onlineNames(args[0]) : List.of();
    }
}
