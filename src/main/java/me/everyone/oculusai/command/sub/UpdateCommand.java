package me.everyone.oculusai.command.sub;

import java.util.List;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;

/** Asks the service about a newer build right now, instead of waiting for the
 *  timer. The answer is the same line everybody else gets in chat, so there is
 *  one wording for "a new version is out" and not a second one here. */
public final class UpdateCommand extends SubCommand {

    public UpdateCommand(OculusAI plugin) {
        super(plugin, "update", List.of("version", "ver"), "oculusai.command",
                "/oai update", "Check for a newer build of the anticheat.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!plugin.config().updateEnabled()) {
            // The check is one HTTP call and would work, but answering it
            // while the operator has turned checks off would be answering a
            // question they asked the config not to ask.
            replyKey(sender, "update.off");
            return;
        }
        plugin.updates().checkNow(sender);
    }
}
