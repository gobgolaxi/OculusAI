package me.everyone.yuppyai.command.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.manager.MonitorManager;
import org.bukkit.command.CommandSender;

public final class MonitorCommand extends SubCommand {

    public MonitorCommand(YuppyAI plugin) {
        super(plugin, "monitor", List.of("mon", "watch"), "yuppyai.alerts",
                "/yai monitor [player...]",
                "Live list of who looks suspicious.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        MonitorManager monitors = plugin.monitors();

        if (args.length == 0) {
            if (monitors.watching(sender)) {
                monitors.stop(sender);
                replyKey(sender, "monitor-cmd.stopped");
                return;
            }
            monitors.start(sender, List.of());
            replyKey(sender, "monitor-cmd.started-all");
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
        replyKey(sender, "monitor-cmd.started", Map.of("players", String.join(", ", names)));
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
        return plugin.monitors().onlineNames(args[args.length - 1]);
    }
}
