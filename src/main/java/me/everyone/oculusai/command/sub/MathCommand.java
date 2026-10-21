package me.everyone.oculusai.command.sub;

import java.util.List;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;

/** Switches what /oai prob shows.
 *
 * <p>On by default: the command prints the smoothed probability that feeds the
 * buffer. With it off the command prints the model's raw probability instead.
 * The buffer keeps integrating in both modes, so Aim alerts and punishments do
 * not change. Running the command again puts the smoothed view back. */
public final class MathCommand extends SubCommand {

    public MathCommand(OculusAI plugin) {
        super(plugin, "math", List.of("buffer"), "oculusai.command",
                "/oai math", "Show the raw model probability instead of the smoothed one.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        boolean math = plugin.detection().toggleMath();
        // textOr, not text: a server whose language file predates this command
        // must still see a real message rather than the key name.
        sender.sendMessage(plugin.config().prefix() + plugin.lang().textOr(
                math ? "math.enabled" : "math.disabled",
                math ? "<green>Prob shows the smoothed reading.<gray> The buffer, "
                        + "Aim alerts and punishments work as usual."
                     : "<yellow>Prob shows the raw model probability.<gray> The buffer "
                        + "and checks keep running."));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return List.of();
    }
}
