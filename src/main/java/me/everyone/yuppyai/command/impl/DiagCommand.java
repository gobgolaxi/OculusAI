package me.everyone.yuppyai.command.impl;

import java.util.List;
import java.util.Locale;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.analysis.FeatureExtractor;
import me.everyone.yuppyai.analysis.FeatureVector;
import me.everyone.yuppyai.analysis.RotationSample;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.data.PlayerData;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DiagCommand extends SubCommand {

    public DiagCommand(YuppyAI plugin) {
        super(plugin, "diag", List.of("debug", "why"), "yuppyai.diag",
                "/yai diag [player]",
                "Show why a player's windows are or are not analysed.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player target;
        if (args.length > 0) {
            target = plugin.getServer().getPlayerExact(args[0]);
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            reply(sender, "<red>Console must name a player: <white>/yai diag &lt;player&gt;");
            return;
        }
        if (target == null) {
            reply(sender, "<red>That player is not online.");
            return;
        }

        PlayerData data = plugin.data().get(target);
        reply(sender, "<gray>Diagnostics for <white>" + target.getName());
        line(sender, "tracked", data != null, data == null
                ? "no PlayerData - the join was missed, relog" : "ok");
        if (data == null) {
            return;
        }

        line(sender, "bypass", !target.hasPermission("yuppyai.bypass"),
                target.hasPermission("yuppyai.bypass")
                        ? "has yuppyai.bypass (a wildcard permission grants it too)" : "no");
        line(sender, "excluded", !plugin.api().excluded(target),
                plugin.api().excluded(target)
                        ? "world/region excluded by the service" : "no");
        line(sender, "service", plugin.api().reachable(),
                plugin.api().reachable() ? "reachable" : "paused, not answering");

        int needed = plugin.config().windowTicks();
        int have = data.windowSize();
        line(sender, "window", have >= needed, have + "/" + needed + " samples");

        long age = data.msSinceAttack();
        boolean combat = data.inCombat(plugin.config().combatMs());
        line(sender, "combat", combat, age < 0
                ? "no swing ever registered - USE_ENTITY is not being read"
                : age + "ms since the last swing, limit " + plugin.config().combatMs() + "ms");

        List<RotationSample> window = data.snapshotWindow(needed);
        if (window == null) {
            return;
        }

        int attacks = 0;
        double rotation = 0.0D;
        for (int i = 0; i < window.size(); i++) {
            if (window.get(i).attacked()) {
                attacks++;
            }
            if (i > 0) {
                rotation += Math.abs(FeatureExtractor.wrapDegrees(
                        window.get(i).yaw() - window.get(i - 1).yaw()));
            }
        }
        line(sender, "attacks", attacks >= plugin.config().minAttacks(),
                attacks + " in the window, need " + plugin.config().minAttacks());
        line(sender, "rotation", rotation >= plugin.config().minRotation(),
                trim(rotation) + "deg, need " + trim(plugin.config().minRotation()));

        FeatureVector vector = FeatureExtractor.extract(window, data.baselineScale());
        line(sender, "features", vector != null,
                vector == null ? "window never moved, nothing to judge" : "extracted");

        line(sender, "recording", true, data.recording()
                ? "on (" + data.recordingLabel() + ") - windows are recorded, not scored"
                : "off");
        line(sender, "readings", data.windowsAnalysed() > 0,
                data.windowsAnalysed() + " windows scored, probability "
                        + trim(data.probability()) + ", buffer " + trim(data.buffer()));
    }

    private void line(CommandSender sender, String label, boolean ok, String detail) {
        sender.sendMessage(Msg.parse("<dark_gray> - <gray>" + label + " "
                + (ok ? "<green>v" : "<red>x") + " <gray>" + detail));
    }

    private static String trim(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length <= 1 ? onlineNames(args.length == 0 ? "" : args[0]) : List.of();
    }
}
