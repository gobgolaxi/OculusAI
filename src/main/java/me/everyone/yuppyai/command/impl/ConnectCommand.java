package me.everyone.yuppyai.command.impl;

import com.google.gson.JsonObject;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.command.SubCommand;
import me.everyone.yuppyai.util.Msg;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;

public final class ConnectCommand extends SubCommand {

    private static final long POLL_TICKS = 60L;
    private static final long TIMEOUT_MS = 10L * 60L * 1000L;

    private BukkitTask poll;

    public ConnectCommand(YuppyAI plugin) {
        super(plugin, "connect", List.of("link", "bind"), "yuppyai.connect",
                "/yai connect", "Bind this server to your account.", false);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (poll != null) {
            poll.cancel();
            poll = null;
        }
        replyKey(sender, "connect.asking");
        plugin.api().linkStart(Bukkit.getServer().getName(), Bukkit.getBukkitVersion())
                .thenAccept(response -> plugin.getServer().getScheduler().runTask(plugin,
                        () -> offer(sender, response)));
    }

    private void offer(CommandSender sender, JsonObject response) {
        if (response == null || !response.has("url") || !response.has("token")) {
            replyKey(sender, "connect.failed");
            return;
        }
        String url = response.get("url").getAsString();
        String token = response.get("token").getAsString();

        sender.sendMessage(plugin.config().prefix() + plugin.lang().text("connect.open"));
        sendLink(sender, url);
        watch(sender, token, System.currentTimeMillis());
    }

    private void sendLink(CommandSender sender, String url) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Msg.parse("<aqua>" + url));
            return;
        }
        TextComponent component = new TextComponent(Msg.parse("<aqua><u>" + url));
        component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(plugin.lang().text("connect.hover"))));
        player.spigot().sendMessage(component);
    }

    private void watch(CommandSender sender, String token, long startedAt) {
        poll = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
                stop();
                replyKey(sender, "connect.expired");
                return;
            }
            plugin.api().linkStatus(token).thenAccept(response ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> settle(sender, response)));
        }, POLL_TICKS, POLL_TICKS);
    }

    private void settle(CommandSender sender, JsonObject response) {
        if (response == null || !response.has("state")) {
            return;
        }
        String state = response.get("state").getAsString();
        if (state.equals("waiting")) {
            return;
        }
        stop();
        if (!state.equals("linked") || !response.has("key")) {
            replyKey(sender, "connect.expired");
            return;
        }
        String key = response.get("key").getAsString();
        plugin.getConfig().set("api.key", key);
        plugin.saveConfig();
        plugin.reloadEverything();
        replyKey(sender, "connect.done", Map.of("address",
                response.has("address") ? response.get("address").getAsString() : "-"));
    }

    private void stop() {
        if (poll != null) {
            poll.cancel();
            poll = null;
        }
    }
}
