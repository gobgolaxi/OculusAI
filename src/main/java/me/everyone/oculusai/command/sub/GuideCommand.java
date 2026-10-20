package me.everyone.oculusai.command.sub;

import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import me.everyone.oculusai.util.Msg;
import org.bukkit.command.CommandSender;

public final class GuideCommand extends SubCommand {

    private static final List<String> LINES = List.of(
            "guide.what",
            "guide.watch",
            "guide.check",
            "guide.act",
            "guide.numbers",
            "guide.test");

    public GuideCommand(OculusAI plugin) {
        super(plugin, "guide", List.of("howto", "faq"), "oculusai.command",
                "/oai guide", "How to use the anticheat.", false);
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
