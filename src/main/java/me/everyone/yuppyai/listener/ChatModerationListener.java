package me.everyone.yuppyai.listener;

import me.everyone.yuppyai.YuppyAI;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class ChatModerationListener implements Listener {

    private final YuppyAI plugin;

    public ChatModerationListener(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.autoMod().enabled() || plugin.autoMod().bypass(event.getPlayer())) {
            return;
        }

        var decision = plugin.autoMod().check(event.getPlayer(), event.getMessage()).join();
        if (!decision.blocked()) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.config().prefix()
                + plugin.lang().textOr("automod.blocked", "<red>Your message was blocked by AutoMod."));
        plugin.getServer().getScheduler().runTask(plugin,
                () -> plugin.autoMod().notifyBlocked(event.getPlayer(), event.getMessage(), decision));
    }
}
