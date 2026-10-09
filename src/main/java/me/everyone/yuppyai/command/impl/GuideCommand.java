package me.everyone.yuppyai.command.impl;

import java.util.List;
import java.util.Map;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.util.Msg;
import org.bukkit.command.CommandSender;

public final class GuideCommand extends SubCommand {

    private static final List<String> LINES = List.of(
            "guide.what",
            "guide.watch",
            "guide.check",
            "guide.act",
            "guide.numbers",
            "guide.test");

    public GuideCommand(YuppyAI plugin) {
        super(plugin, "guide", List.of("howto", "faq"), "yuppyai.command",
                "/yai guide", "How to use the anticheat.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        sender.sendMessage(plugin.config().prefix() + plugin.lang().text("guide.title"));
        for (String key : LINES) {
            sender.sendMessage(Msg.parse(" <dark_gray>- <gray>")
                    + plugin.lang().text(key, Map.of(
                            "alert", Msg.round(plugin.config().alertAt(), 1),
                            "punish", Msg.round(plugin.config().punishAt(), 1))));
        }
    }
}
