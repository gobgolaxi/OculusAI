package me.everyone.oculusai.command.impl;

import java.util.List;
import java.util.Map;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.command.SubCommand;
import org.bukkit.command.CommandSender;

public final class ReloadCommand extends SubCommand {

    public ReloadCommand(OculusAI plugin) {
        super(plugin, "reload", List.of("rl"), "oculusai.command",
                "/oai reload", "Re-read config.yml.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        long start = System.currentTimeMillis();
        plugin.reloadEverything();
        replyKey(sender, "reload.done",
                Map.of("value", Long.toString(System.currentTimeMillis() - start)));
    }
}
