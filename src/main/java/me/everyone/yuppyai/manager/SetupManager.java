package me.everyone.yuppyai.manager;

import java.util.Map;
import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.util.Msg;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

public final class SetupManager implements Manager, Listener {

    private static final long JOIN_DELAY_TICKS = 40L;
    private static final long REMINDER_TICKS = 20L * 60L * 10L;

    private final YuppyAI plugin;
    private BukkitTask reminder;

    public SetupManager(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (plugin.config().connected()) {
            return;
        }
        console();
        reminder = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (plugin.config().connected()) {
                stop();
                return;
            }
            console();
        }, REMINDER_TICKS, REMINDER_TICKS);
    }

    @Override
    public void disable() {
        stop();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.config().connected() || !event.getPlayer().hasPermission("yuppyai.alerts")) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> notify(event.getPlayer()), JOIN_DELAY_TICKS);
    }

    public void notify(Player player) {
        if (plugin.config().connected() || !player.isOnline()) {
            return;
        }
        String site = plugin.config().site();
        player.sendMessage(plugin.config().prefix() + plugin.lang().text("setup.not-connected"));
        player.sendMessage(plugin.config().prefix() + plugin.lang().text("setup.how"));

        TextComponent link = new TextComponent(TextComponent.fromLegacyText(
                Msg.parse(plugin.config().prefix() + "<aqua><u>" + site)));
        link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, site));
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(TextComponent.fromLegacyText(
                        Msg.parse(plugin.lang().text("setup.hover"))))));
        player.spigot().sendMessage(link);
        player.sendMessage(plugin.config().prefix()
                + plugin.lang().text("setup.command", Map.of("value", "/yai connect")));
    }

    private void console() {
        plugin.getLogger().warning(plugin.lang().plain("setup.not-connected"));
        plugin.getLogger().warning(plugin.lang().plain("setup.how") + " " + plugin.config().site());
        plugin.getLogger().warning(plugin.lang().plain("setup.command")
                .replace("%value%", "/yai connect"));
    }

    private void stop() {
        if (reminder != null) {
            reminder.cancel();
            reminder = null;
        }
    }
}
