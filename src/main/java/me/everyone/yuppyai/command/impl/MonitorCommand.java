package me.everyone.yuppyai.command.impl;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.manager.MonitorManager;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starts and stops the live detection readout.
 *
 * <p>With no names it follows everyone being tracked, hottest first; with names
 * it follows exactly those, and re-resolves them every tick so somebody who
 * joins later still appears. The feed refreshes once a second and only reaches
 * the person who asked for it.
 */
public final class MonitorCommand extends SubCommand {

    public MonitorCommand(YuppyAI plugin) {
        super(plugin, "monitor", List.of("mon", "watch"), "yuppyai.alerts",
                "/yai monitor [player...] | /yai monitor stop",
                "Keep a live detection readout in chat.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        MonitorManager monitors = plugin.monitors();

        if (args.length == 0) {
            // Bare /yai monitor restarts the feed; if one is already running for
            // this sender, treat it as a stop so the command is its own inverse
            // and a second press is never a no-op the operator cannot escape.
            if (monitors.watching(sender)) {
                monitors.stop(sender);
                replyKey(sender, "monitor-cmd.stopped");
                return;
            }
            monitors.start(sender, List.of());
            replyKey(sender, "monitor-cmd.started-all", Map.of(
                    "seconds", Integer.toString(MonitorManager.PERIOD_TICKS / 20)));
            return;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("stop") || first.equals("off") || first.equals("clear")) {
            boolean was = monitors.stop(sender);
            replyKey(sender, was ? "monitor-cmd.stopped" : "monitor-cmd.not-watching");
            return;
        }

        List<String> names = new ArrayList<>(List.of(args));
        monitors.start(sender, names);
        replyKey(sender, "monitor-cmd.started", Map.of(
                "players", String.join(", ", names),
                "seconds", Integer.toString(MonitorManager.PERIOD_TICKS / 20)));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(plugin.monitors().onlineNames(args[0]));
            for (String stop : List.of("stop", "off")) {
                if (stop.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(stop);
                }
            }
            return options;
        }
        // Every argument after the first is another player to follow.
        return plugin.monitors().onlineNames(args[args.length - 1]);
    }
}
