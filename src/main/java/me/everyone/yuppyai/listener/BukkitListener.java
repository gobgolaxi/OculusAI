package me.everyone.yuppyai.listener;

import me.everyone.yuppyai.YuppyAI;
import me.everyone.yuppyai.data.PlayerData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class BukkitListener implements Listener {

    private final YuppyAI plugin;

    public BukkitListener(YuppyAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.data().add(event.getPlayer());
        plugin.journal().refreshFor(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        cleanup(event.getPlayer(), true);
    }

    private void cleanup(org.bukkit.entity.Player player, boolean dropState) {
        PlayerData leaving = plugin.data().get(player);
        if (leaving != null && leaving.recording() && leaving.recordedCount() > 0
                && leaving.recordingLabel() != null) {
            plugin.datasets().commit(leaving, leaving.recordingLabel());
        }

        plugin.tracker().forget(player);
        plugin.displays().forget(player.getUniqueId());
        plugin.punishments().forget(player.getUniqueId());
        plugin.journal().forgetWatcher(player.getUniqueId());
        if (dropState) {
            if (leaving != null) {
                leaving.resetBuffer();
            }
            plugin.data().remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!plugin.journal().isVanished(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getNewGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.config().prefix()
                + plugin.lang().text("vanish.gamemode-blocked"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PlayerData data = plugin.data().get(event.getEntity());
        if (data == null) {
            return;
        }

        data.resetAfterDeath();
        plugin.analysis().forget(data.uuid());
        plugin.tracker().forget(event.getEntity());
        plugin.displays().forget(data.uuid());
        plugin.punishments().forget(data.uuid());
    }
}
