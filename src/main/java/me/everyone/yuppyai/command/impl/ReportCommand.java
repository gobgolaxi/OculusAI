package me.everyone.yuppyai.command.impl;

import java.util.List;
import java.util.Map;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ReportCommand extends SubCommand {

    public ReportCommand(YuppyAI plugin) {
        super(plugin, "report", List.of("complain"), "yuppyai.report",
                "/yai report <ник> [причина]", "Report a player to the panel.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            replyKey(sender, "report.usage", Map.of("value", usage()));
            return;
        }
        Player target = plugin.getServer().getPlayerExact(args[0]);
        String name = target != null ? target.getName() : args[0];
        String uuid = target != null ? target.getUniqueId().toString() : "";
        String reason = args.length > 1
                ? String.join(" ", List.of(args).subList(1, args.length))
                : plugin.lang().text("report.no-reason");

        plugin.api().reportPlayer(name, uuid, reason, sender.getName());
        replyKey(sender, "report.sent", Map.of("player", name));
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length <= 1 ? onlineNames(args.length == 0 ? "" : args[0]) : List.of();
    }
}
