package me.everyone.oculusai.command.sub;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.ui.Monitor;
import org.bukkit.command.CommandSender;

public final class MonitorCommand extends SubCommand {

    /** The word that brings back the old list: everyone who looks suspicious. */
    private static final String LIST = "list";

    public MonitorCommand(OculusAI plugin) {
        super(plugin, "monitor", List.of("mon", "watch"), "oculusai.alerts",
                "/oai monitor [list|player...]",
                "Watch everybody in PvP, or a list of who looks suspicious.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Monitor monitors = plugin.monitors();

        // Without a name this is the watch over everybody: on, and off again
        // by the same command.
        if (args.length == 0) {
            boolean on = monitors.toggleGlobal();
            replyKey(sender, on ? "monitor-cmd.global-on" : "monitor-cmd.global-off");
            return;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("stop") || first.equals("off") || first.equals("clear")) {
            boolean was = monitors.stop(sender);
            replyKey(sender, was ? "monitor-cmd.stopped" : "monitor-cmd.not-watching");
            return;
        }

        if (first.equals(LIST)) {
            monitors.start(sender, List.of());
            replyKey(sender, "monitor-cmd.started-all");
            return;
        }

        List<String> names = new ArrayList<>(List.of(args));
        monitors.start(sender, names);
        replyKey(sender, "monitor-cmd.started", Map.of("players", String.join(", ", names)));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(plugin.monitors().onlineNames(args[0]));
            for (String option : List.of(LIST, "stop", "off")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(option);
                }
            }
            return options;
        }
        return plugin.monitors().onlineNames(args[args.length - 1]);
    }
}
