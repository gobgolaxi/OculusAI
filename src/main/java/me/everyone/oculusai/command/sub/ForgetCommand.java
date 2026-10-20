package me.everyone.oculusai.command.sub;

import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.player.PlayerData;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;

public final class ForgetCommand extends SubCommand {

    public ForgetCommand(OculusAI plugin) {
        super(plugin, "forget", List.of("clear", "reset"), "oculusai.alerts",
                "/oai forget <player>", "Drop the current suspicion and start over.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            replyKey(sender, "forget.usage", Map.of("value", usage()));
            return;
        }

        PlayerData data = plugin.data().byName(args[0]);
        if (data == null) {
            replyKey(sender, "shared.player-offline");
            return;
        }

        boolean hadCase = plugin.punishments().pending(data.uuid());
        double buffer = data.buffer();
        int discarded = data.recordedCount();

        plugin.punishments().forget(data.uuid());
        plugin.analysis().forget(data.uuid());
        // Журнал теперь переживает перезапуск, поэтому забытый игрок должен
        // уходить и оттуда: иначе он остаётся в списке подозрительных с
        // буфером, которого у него больше нет.
        plugin.journal().forget(data.uuid());
        data.recording(false, null);
        data.clearRecorded();
        data.clearWindow();
        data.resetBuffer();

        replyKey(sender, "forget.done", Map.of(
                "player", data.name(),
                "buffer", Msg.round(buffer, 1),
                "case", hadCase ? plugin.lang().text("forget.case-cancelled") : "",
                "dropped", discarded > 0
                        ? plugin.lang().text("forget.dropped", Map.of("value", Integer.toString(discarded)))
                        : ""));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? onlineNames(args[0]) : List.of();
    }
}
