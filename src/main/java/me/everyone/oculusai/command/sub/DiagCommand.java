package me.everyone.oculusai.command.sub;

import java.util.List;
import java.util.Locale;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.detection.FeatureExtractor;
import me.everyone.oculusai.detection.FeatureVector;
import me.everyone.oculusai.detection.RotationSample;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DiagCommand extends SubCommand {

    public DiagCommand(OculusAI plugin) {
        super(plugin, "diag", List.of("debug", "why"), "oculusai.diag",
                "/oai diag [player]",
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
            reply(sender, "<red>Console must name a player: <white>/oai diag &lt;player&gt;");
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

        boolean permissionBypass = target.hasPermission("oculusai.bypass");
        boolean managedBypass = plugin.bypass().managed(target.getUniqueId());
        line(sender, "bypass", !(permissionBypass || managedBypass),
                permissionBypass
                        ? "has oculusai.bypass (a wildcard permission grants it too)"
                        : managedBypass
                                ? "disabled with /oai bypass - run it again to enable"
                                : "no");
        line(sender, "excluded", !plugin.api().excluded(target),
                plugin.api().excluded(target)
                        ? "world/region excluded by the service" : "no");
        // Without this line a flying player reads as "no windows, no reason",
        // because the samples are dropped before anything else can count them.
        line(sender, "elytra", !target.isGliding(),
                target.isGliding()
                        ? "gliding - samples are not taken while flying" : "not gliding");
        line(sender, "service", plugin.api().reachable(),
                plugin.api().reachable() ? "reachable" : "paused, not answering");

        // Which plan and which model the service says this key is on. A
        // lapsed subscription is served by the trimmed model, and that is a
        // change in what the readings mean - so it belongs here rather than
        // only in the panel.
        String plan = plugin.api().servicePlan();
        if (!plan.isBlank()) {
            line(sender, "plan", !plugin.api().modelDemoted(),
                    plugin.api().modelDemoted()
                            ? plan + ", answering with " + plugin.api().serviceModel()
                                    + " - the subscription no longer reaches the full model"
                            : plan + ", answering with " + plugin.api().serviceModel());
        }

        // Captures the service has not taken yet. Silent when there are none,
        // because an empty queue is the normal state and says nothing.
        int held = plugin.uploads().size();
        if (held > 0) {
            line(sender, "captures", false, held + " capture(s), "
                    + plugin.uploads().windows() + " windows not sent yet -"
                    + " retrying every " + me.everyone.oculusai.network.UploadQueue.RETRY_SECONDS + "s");
        }

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
