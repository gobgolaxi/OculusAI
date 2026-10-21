package me.everyone.oculusai.player;

import java.util.Locale;
import me.everyone.oculusai.OculusAI;
import me.everyone.oculusai.player.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final OculusAI plugin;

    public PlayerListener(OculusAI plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.data().add(event.getPlayer());
        plugin.journal().refreshFor(event.getPlayer());
        Player joining = event.getPlayer();
        plugin.worker().execute(() -> plugin.history().preload(
                joining.getUniqueId(), joining.getName()));

        // Holograms are packet entities in the viewer's client, and a client
        // that has just connected has none of them. Both directions matter:
        // this player may be a viewer whose AlwaysProb has to come back, and
        // everybody else's AlwaysProb needs a hologram over the new arrival.
        plugin.displays().resyncAlwaysProb(joining);
        plugin.displays().resyncAlwaysProb();
    }

    /** A respawn recreates the player entity client-side, so whatever was
     *  mounted on it is gone. Next tick, because at the time the event fires
     *  the player is still at the old location. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.ticks().after(1L, () -> {
            plugin.displays().resyncAlwaysProb(player);
            plugin.displays().resyncAlwaysProb();
        });
    }

    /** A world change throws the client's whole entity store away. The old
     *  holograms are dead ids now; everybody watching needs a fresh set. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        plugin.displays().resyncAlwaysProb(event.getPlayer());
        plugin.displays().resyncAlwaysProb();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {
        cleanup(event.getPlayer(), true);
    }

    private void cleanup(Player player, boolean dropState) {
        PlayerData leaving = plugin.data().get(player);
        if (leaving != null && leaving.recording() && leaving.recordedCount() > 0
                && leaving.recordingLabel() != null) {
            plugin.datasets().commit(leaving, leaving.recordingLabel());
        }

        PlayerData present = plugin.data().get(player);
        if (present != null && present.windowsAnalysed() > 0) {
            plugin.history().note(player.getUniqueId(), player.getName(),
                    dropState ? "kick" : "quit",
                    "buffer " + String.format(Locale.ROOT, "%.1f", present.buffer()));
        }

        plugin.tracker().forget(player);
        plugin.displays().forget(player.getUniqueId());
        plugin.monitors().forget(player.getUniqueId());
        plugin.punishments().forget(player.getUniqueId());
        plugin.checks().forget(player.getUniqueId());
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
        if (event.getNewGameMode() == GameMode.SPECTATOR) {
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
